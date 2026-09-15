package com.trading.bot.engine

import com.trading.bot.client.UpbitApiException
import com.trading.bot.client.FILL_POLL_ATTEMPTS
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.FillOutcome
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderTrade
import com.trading.bot.domain.Ticker
import com.trading.bot.domain.TradePnl
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.TradeExecutionRepository
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.entity.TradeExecutionEntity
import com.trading.bot.persistence.entity.TradeRecordEntity
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import reactor.core.publisher.Mono
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.reactive.TransactionalOperator

class TradeExecutionServiceTest {

    private lateinit var tradeRecordRepository: TradeRecordRepository
    private lateinit var tradeExecutionRepository: TradeExecutionRepository
    private lateinit var discordNotifier: DiscordNotifier
    private lateinit var transactionalOperator: TransactionalOperator
    private lateinit var service: TradeExecutionService
    private lateinit var client: UpbitClient

    @BeforeEach
    fun setup() {
        tradeRecordRepository = mockk(relaxed = true)
        tradeExecutionRepository = mockk(relaxed = true)
        discordNotifier = mockk(relaxed = true)
        client = mockk()
        // manual trade (executeBuy/SellAll/SellVolume) 가 통합 saveAndNotify 를 거치도록 변경되어
        // tradeExecutionRepository.save 도 호출됨. 명시 stub 이 없으면 relaxed mockk 의 Mono 가
        // emit 안 해 awaitSingle 가 무한 대기 → UncompletedCoroutinesError.
        every { tradeExecutionRepository.save(any()) } returns Mono.just(mockk<TradeExecutionEntity>(relaxed = true))
        // 수동 매도가 exchangeOrderId 를 채우면서 saveAudit 의 멱등 조회를 타게 됐다 — 같은 이유로 기본 stub 이 필요하다.
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(any(), any()) } returns Mono.just(false)
        // 트랜잭션 래핑은 통과(pass-through)시켜 내부 mono 가 그대로 실행되게 함.
        transactionalOperator = mockk()
        every { transactionalOperator.transactional(any<Mono<Any>>()) } answers { firstArg() }
        service = TradeExecutionService(
            tradeRecordRepository, tradeExecutionRepository, discordNotifier, transactionalOperator,
            TradingProperties(),
        )
    }

    @Test
    fun `executeBuy places order and saves record`() = runTest {
        coEvery { client.placeOrder(any()) } returns Order(uuid = "order-123")
        coEvery { client.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 50000000.0))
        coEvery { client.getAccounts() } returns listOf(Account(currency = "KRW", balance = "5000000"))
        coEvery { tradeRecordRepository.save(any()) } returns TradeRecordEntity(
            id = 1, ticker = "KRW-BTC", side = "BUY", price = 50000000.0,
            volume = 0.002, totalAmount = 100000.0, userId = 1L,
        )

        val saved = slot<TradeRecord>()
        coEvery { tradeRecordRepository.save(capture(saved)) } returns TradeRecordEntity(
            id = 1, ticker = "KRW-BTC", side = "BUY", price = 50000000.0,
            volume = 0.002, totalAmount = 100000.0, userId = 1L,
        )

        val result = service.executeBuy(client, "KRW-BTC", 100000.0, "volatility_breakout", 1L)

        assertTrue(result.success)
        assertEquals("order-123", result.orderUuid)
        coVerify { tradeRecordRepository.save(any()) }
        // 수동 경로는 placeOrder 즉시 응답뿐이라 체결 대금을 모른다 — 요청액을 실측인 척 넣지 않는다(#146).
        assertNull(saved.captured.orderAmount)
        coVerify { discordNotifier.sendTradeEmbed(any(), any(), any(), any()) }
    }

    @Test
    fun `executeSellAll returns failure when no holdings`() = runTest {
        coEvery { client.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "5000000")
        )

        val result = service.executeSellAll(client, "KRW-BTC", "volatility_breakout", 1L)

        assertFalse(result.success)
        assertEquals("no holdings for BTC", result.error)
    }

    @Test
    fun `executeSellAll sells all holdings and records trade`() = runTest {
        coEvery { client.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.5", avgBuyPrice = "48000000"),
            Account(currency = "KRW", balance = "1000000"),
        )
        coEvery { client.placeOrder(any()) } returns Order(uuid = "sell-456")
        coEvery { client.getOrder("sell-456") } returns Order(uuid = "sell-456", state = "done", executedVolume = "0.5")
        coEvery { client.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 50000000.0))
        coEvery { tradeRecordRepository.save(any()) } returns TradeRecordEntity(
            id = 2, ticker = "KRW-BTC", side = "SELL", price = 50000000.0,
            volume = 0.5, totalAmount = 25000000.0, pnlPercent = 4.17, userId = 1L,
        )

        val result = service.executeSellAll(client, "KRW-BTC", "volatility_breakout", 1L)

        assertTrue(result.success)
        assertEquals("sell-456", result.orderUuid)
        coVerify { tradeRecordRepository.save(any()) }
    }

    @Test
    fun `executeSellAll records net pnl after round-trip fee`() = runTest {
        coEvery { client.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.5", avgBuyPrice = "48000000"),
        )
        coEvery { client.placeOrder(any()) } returns Order(uuid = "sell-net")
        coEvery { client.getOrder("sell-net") } returns Order(uuid = "sell-net", state = "done", executedVolume = "0.5")
        coEvery { client.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 50000000.0))
        val recordSlot = slot<TradeRecord>()
        coEvery { tradeRecordRepository.save(capture(recordSlot)) } returns TradeRecordEntity(
            id = 4, ticker = "KRW-BTC", side = "SELL", price = 50000000.0,
            volume = 0.5, totalAmount = 25000000.0, userId = 1L,
        )

        service.executeSellAll(client, "KRW-BTC", "volatility_breakout", 1L)

        // MANUAL 매도도 net 통일: gross (50M−48M)/48M = +4.1667%p − 왕복수수료 0.1%p
        assertEquals((50.0 / 48.0 - 1.0) * 100.0 - 0.1, recordSlot.captured.pnlPercent!!, 1e-9)
    }

    @Test
    fun `executeSellVolume records nothing when neither the tick nor the filled funds are available`() = runTest {
        // 체결은 알아도 대금을 전혀 못 구하면 totalAmount=0 행이 되어 라운드트립이 전액 손실(−평단×수량)로 계산한다 — 적지 않는다.
        coEvery { client.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "1.0", avgBuyPrice = "48000000"),
        )
        coEvery { client.placeOrder(any()) } returns Order(uuid = "sell-noprice")
        coEvery { client.getOrder("sell-noprice") } returns Order(uuid = "sell-noprice", state = "done", executedVolume = "0.3")
        coEvery { client.getTicker("KRW-BTC") } returns emptyList()

        val result = service.executeSellVolume(client, "KRW-BTC", "0.3", "rsi_bounce", 1L)

        assertEquals(FillOutcome.UNCONFIRMED, result.fill)
        coVerify(exactly = 0) { tradeRecordRepository.save(any()) }
    }

    // --- 수동주문 unknown-state: placeOrder 성공 후 후처리 실패는 2xx+uuid(recorded=false), placeOrder 실패만 failure ---

    @Test
    fun `executeBuy returns success recorded false when persistence fails`() = runTest {
        // placeOrder 접수 성공했으나 DB 저장 실패 — 주문은 나갔으므로 failure(재시도 유발) 대신 success+uuid+recorded=false.
        coEvery { client.placeOrder(any()) } returns Order(uuid = "order-x")
        coEvery { client.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 50000000.0))
        coEvery { client.getAccounts() } returns listOf(Account(currency = "KRW", balance = "5000000"))
        coEvery { tradeRecordRepository.save(any()) } throws RuntimeException("db down")

        val result = service.executeBuy(client, "KRW-BTC", 100000.0, "manual", 1L)

        assertTrue(result.success)
        assertEquals("order-x", result.orderUuid)
        assertFalse(result.recorded)
        coVerify(exactly = 1) { client.placeOrder(any()) } // 재시도 없음(단일 접수)
    }

    @Test
    fun `executeBuy propagates placeOrder exception to advice`() = runTest {
        // placeOrder 실패는 삼키지 않고 전파 → UpbitErrorHandlerAdvice 가 429/error_name 을 매핑. 삼키면 그 매핑 우회 + rawBody 노출.
        coEvery { client.placeOrder(any()) } throws UpbitApiException(429, "too_many_requests", "rate", "raw")

        val ex = runCatching { service.executeBuy(client, "KRW-BTC", 100000.0, "manual", 1L) }.exceptionOrNull()

        assertTrue(ex is UpbitApiException)
    }

    @Test
    fun `executeSellAll returns success recorded false when persistence fails`() = runTest {
        coEvery { client.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.5", avgBuyPrice = "48000000")
        )
        coEvery { client.placeOrder(any()) } returns Order(uuid = "sell-x")
        coEvery { client.getOrder("sell-x") } returns Order(uuid = "sell-x", state = "done", executedVolume = "0.5")
        coEvery { client.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 50000000.0))
        coEvery { tradeRecordRepository.save(any()) } throws RuntimeException("db down")

        val result = service.executeSellAll(client, "KRW-BTC", "manual", 1L)

        assertTrue(result.success)
        assertEquals("sell-x", result.orderUuid)
        assertFalse(result.recorded)
    }

    @Test
    fun `executeSellAll propagates placeOrder exception to advice`() = runTest {
        coEvery { client.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.5", avgBuyPrice = "48000000")
        )
        coEvery { client.placeOrder(any()) } throws UpbitApiException(429, "too_many_requests", "rate", "raw")

        val ex = runCatching { service.executeSellAll(client, "KRW-BTC", "manual", 1L) }.exceptionOrNull()

        assertTrue(ex is UpbitApiException)
    }

    @Test
    fun `executeSellVolume returns success recorded false when persistence fails`() = runTest {
        coEvery { client.placeOrder(any()) } returns Order(uuid = "sv-x")
        coEvery { client.getOrder("sv-x") } returns Order(uuid = "sv-x", state = "done", executedVolume = "0.3")
        coEvery { client.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 52000000.0))
        coEvery { client.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "1.0", avgBuyPrice = "48000000")
        )
        coEvery { tradeRecordRepository.save(any()) } throws RuntimeException("db down")

        val result = service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L)

        assertTrue(result.success)
        assertEquals("sv-x", result.orderUuid)
        assertFalse(result.recorded)
    }

    @Test
    fun `executeSellVolume preserves pnl when volume exhausts holding`() = runTest {
        // 전량 매도: 평단(avgBuyPrice)을 매도 전에 확보해야 한다. placeOrder 후엔 통화 잔고가 사라져 avgBuyPrice=0 → pnl null.
        coEvery { client.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.3", avgBuyPrice = "48000000")
        )
        coEvery { client.placeOrder(any()) } returns Order(uuid = "sv-full")
        coEvery { client.getOrder("sv-full") } returns Order(uuid = "sv-full", state = "done", executedVolume = "0.3")
        coEvery { client.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 50000000.0))
        val recordSlot = slot<TradeRecord>()
        coEvery { tradeRecordRepository.save(capture(recordSlot)) } returns TradeRecordEntity(
            id = 9, ticker = "KRW-BTC", side = "SELL", price = 50000000.0,
            volume = 0.3, totalAmount = 15000000.0, userId = 1L,
        )

        service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L)

        // 평단 매도 전 확보 → net pnl = (50/48-1)*100 − 왕복수수료 0.1%p (null 아님)
        assertEquals((50.0 / 48.0 - 1.0) * 100.0 - 0.1, recordSlot.captured.pnlPercent!!, 1e-9)
    }

    @Test
    fun `executeSellVolume propagates placeOrder exception to advice`() = runTest {
        // avgBuyPrice 조회(getAccounts)는 placeOrder 전에 수행되므로 stub 필요. placeOrder 예외는 전파(advice 매핑).
        coEvery { client.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.3", avgBuyPrice = "48000000")
        )
        coEvery { client.placeOrder(any()) } throws UpbitApiException(429, "too_many_requests", "rate", "raw")

        val ex = runCatching { service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L) }.exceptionOrNull()

        assertTrue(ex is UpbitApiException)
    }

    @Test
    fun `executeSellVolume sells specified volume`() = runTest {
        coEvery { client.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "1.0", avgBuyPrice = "48000000"),
            Account(currency = "KRW", balance = "1000000"),
        )
        coEvery { client.placeOrder(any()) } returns Order(uuid = "sell-789")
        coEvery { client.getOrder("sell-789") } returns Order(uuid = "sell-789", state = "done", executedVolume = "0.3")
        coEvery { client.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 52000000.0))
        coEvery { tradeRecordRepository.save(any()) } returns TradeRecordEntity(
            id = 3, ticker = "KRW-BTC", side = "SELL", price = 52000000.0,
            volume = 0.3, totalAmount = 15600000.0, pnlPercent = 8.33, userId = 1L,
        )

        val result = service.executeSellVolume(client, "KRW-BTC", "0.3", "rsi_bounce", 1L)

        assertTrue(result.success)
        assertEquals("sell-789", result.orderUuid)
    }

    @Test
    fun `saveAndNotify skips duplicate by exchangeOrderId (idempotent)`() = runTest {
        // #20: 재시작 후 같은 주문 uuid 로 reconcile 이 다시 기록을 시도하면 저장·알림 모두 skip.
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "dup-1") } returns Mono.just(true)
        val record = TradeRecord(
            ticker = "KRW-BTC", side = TradeSide.BUY, price = 50000000.0, volume = 0.001,
            totalAmount = 50000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
            exchangeOrderId = "dup-1", userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = null,
        )

        service.saveAndNotify(record, client, null, null)

        coVerify(exactly = 0) { tradeRecordRepository.save(any()) }
        coVerify(exactly = 0) { tradeExecutionRepository.save(any()) }
        verify(exactly = 0) { discordNotifier.sendTradeEmbed(any(), any(), any(), any()) }
    }

    @Test
    fun `saveAndNotify records when exchangeOrderId not seen before`() = runTest {
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "new-1") } returns Mono.just(false)
        val record = TradeRecord(
            ticker = "KRW-BTC", side = TradeSide.BUY, price = 50000000.0, volume = 0.001,
            totalAmount = 50000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
            exchangeOrderId = "new-1", userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = null,
        )

        service.saveAndNotify(record, client, null, null)

        coVerify(exactly = 1) { tradeExecutionRepository.save(any()) }
    }

    @Test
    fun `commitFill keeps audit committed when notification fails`() = runTest {
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "fill-1") } returns Mono.just(false)
        coEvery { client.getAccounts() } returns emptyList()
        every { discordNotifier.sendTradeEmbed(any(), any(), any(), any()) } throws IllegalStateException("discord down")
        val statePersisted = AtomicBoolean(false)
        val record = TradeRecord(
            ticker = "KRW-BTC", side = TradeSide.BUY, price = 50000000.0, volume = 0.001,
            totalAmount = 50000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
            exchangeOrderId = "fill-1", userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = null,
        )

        val recorded = service.commitFill(
            persistState = { statePersisted.set(true) },
            record = record,
        )
        val notificationFailure = runCatching {
            service.notifyTrade(record, client, null, null)
        }.exceptionOrNull()

        assertTrue(recorded)
        assertTrue(statePersisted.get())
        assertTrue(notificationFailure is IllegalStateException)
        coVerify(exactly = 1) { tradeRecordRepository.save(record) }
        coVerify(exactly = 1) { tradeExecutionRepository.save(any()) }
    }

    @Test
    fun `manual order reports recorded false when notification fails`() = runTest {
        coEvery { client.placeOrder(any()) } returns Order(uuid = "notify-fail")
        coEvery { client.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 50000000.0))
        coEvery { client.getAccounts() } returns emptyList()
        coEvery { tradeRecordRepository.save(any()) } returns TradeRecordEntity(
            id = 10, ticker = "KRW-BTC", side = "BUY", price = 50000000.0,
            volume = 0.002, totalAmount = 100000.0, userId = 1L,
        )
        every { discordNotifier.sendTradeEmbed(any(), any(), any(), any()) } throws IllegalStateException("discord down")

        val result = service.executeBuy(
            client = client,
            market = "KRW-BTC",
            amount = 100000.0,
            strategy = "manual",
            userId = 1L,
        )

        assertTrue(result.success)
        assertEquals("notify-fail", result.orderUuid)
        assertFalse(result.recorded, "주문은 접수됐지만 알림 실패는 수동 후처리 실패로 노출돼야 한다")
        coVerify(exactly = 1) { tradeRecordRepository.save(any()) }
    }

    // --- 감사 기록의 수수료 ---
    // 공식은 saveAudit 한 곳에 모으되, **어떤 기준을 쓸지는 경로가 정한다**(#133). totalAmount 가 그 체결의
    // 대금이 아닌 경로(엔진 매수 = 포지션 전체 원가)가 있어서, 무조건 유도하면 수수료가 부풀려진다.

    @Test
    fun `saveAudit derives the fee for buy rows too`() = runTest {
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "fee-buy") } returns Mono.just(false)
        val entity = slot<TradeExecutionEntity>()
        every { tradeExecutionRepository.save(capture(entity)) } returns
            Mono.just(mockk<TradeExecutionEntity>(relaxed = true))

        service.saveAudit(
            TradeRecord(
                ticker = "KRW-BTC", side = TradeSide.BUY, price = 50000000.0, volume = 0.002,
                totalAmount = 100000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
                exchangeOrderId = "fee-buy", userId = 1L,
                fee = FeeBasis.Estimate,
                orderAmount = null,
            )
        )

        // 편도 = 왕복(0.001)의 절반 → 100,000 × 0.0005 = 50원
        assertEquals(50.0, entity.captured.fee, 1e-9)
    }

    @Test
    fun `saveAudit stores the measured fee verbatim instead of deriving it`() = runTest {
        // totalAmount(1,040,000)로 유도하면 520원이 된다. 실측이 있으면 그 값이 이긴다 — 이게 #133 의 요지다.
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "fee-measured") } returns Mono.just(false)
        val entity = slot<TradeExecutionEntity>()
        every { tradeExecutionRepository.save(capture(entity)) } returns
            Mono.just(mockk<TradeExecutionEntity>(relaxed = true))

        service.saveAudit(
            TradeRecord(
                ticker = "KRW-BTC", side = TradeSide.BUY, price = 52000000.0, volume = 0.02,
                totalAmount = 1040000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
                fee = FeeBasis.Measured(12.3),
                orderAmount = null,
                exchangeOrderId = "fee-measured", userId = 1L,
            )
        )

        assertEquals(12.3, entity.captured.fee, 1e-9)
    }

    @Test
    fun `saveAudit leaves the fee unrecorded rather than deriving a wrong one`() = runTest {
        // basis 를 모르는 경로(주문 응답 없음). 0 = 미기록은 V21 이 세운 규약이다.
        // 여기서 추정으로 떨어지면 부풀려진 값이 맞는 값과 구분되지 않는다.
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "fee-unknown") } returns Mono.just(false)
        val entity = slot<TradeExecutionEntity>()
        every { tradeExecutionRepository.save(capture(entity)) } returns
            Mono.just(mockk<TradeExecutionEntity>(relaxed = true))

        service.saveAudit(
            TradeRecord(
                ticker = "KRW-BTC", side = TradeSide.BUY, price = 52000000.0, volume = 0.02,
                totalAmount = 1040000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
                fee = FeeBasis.Unrecorded,
                orderAmount = null,
                exchangeOrderId = "fee-unknown", userId = 1L,
            )
        )

        assertEquals(0.0, entity.captured.fee, 1e-9)
    }

    @Test
    fun `saveAudit refuses a measured fee that would poison the column`() = runTest {
        // FeeBasis.Measured 는 public 생성자라 파싱 가드를 우회한 값이 들어올 수 있다.
        // NaN 이 double precision 컬럼에 들어가면 이후 SUM(fee) 이 영구히 NaN 이 된다.
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "fee-nan") } returns Mono.just(false)
        val entity = slot<TradeExecutionEntity>()
        every { tradeExecutionRepository.save(capture(entity)) } returns
            Mono.just(mockk<TradeExecutionEntity>(relaxed = true))

        service.saveAudit(
            TradeRecord(
                ticker = "KRW-BTC", side = TradeSide.BUY, price = 52000000.0, volume = 0.02,
                totalAmount = 1040000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
                fee = FeeBasis.Measured(Double.NaN),
                orderAmount = null,
                exchangeOrderId = "fee-nan", userId = 1L,
            )
        )

        assertEquals(0.0, entity.captured.fee, 1e-9)
        assertFalse(entity.captured.fee.isNaN(), "NaN 은 컬럼에 절대 들어가면 안 된다")
    }

    @Test
    fun `saveAudit carries the strategy and realized amount onto the execution row`() = runTest {
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "audit-sell") } returns Mono.just(false)
        val entity = slot<TradeExecutionEntity>()
        every { tradeExecutionRepository.save(capture(entity)) } returns
            Mono.just(mockk<TradeExecutionEntity>(relaxed = true))

        service.saveAudit(
            TradeRecord(
                ticker = "KRW-BTC", side = TradeSide.SELL, price = 52000000.0, volume = 0.002,
                totalAmount = 104000.0, pnlPercent = 3.9, pnlAmount = 3900.0, strategy = "knee_reversal",
                exchangeOrderId = "audit-sell", userId = 1L,
                fee = FeeBasis.Estimate,
                orderAmount = null,
            )
        )

        assertEquals("knee_reversal", entity.captured.strategy)
        assertEquals(3900.0, entity.captured.pnlAmount!!, 1e-9)
    }

    // --- 수동 매도의 체결 확정 (#105) ---
    // 주문 접수 응답에는 체결이 없다. terminal(done/cancel) 응답의 executed_volume 만 기록하고, 확인하지 못하면
    // 요청 수량으로 폴백하지 않는다(행 없음 + fill=UNCONFIRMED) — 틀린 수량은 라운드트립을 조기 청산으로 오판시킨다.

    private fun sellRecordEntity() = TradeRecordEntity(
        id = 100, ticker = "KRW-BTC", side = "SELL", price = 50000000.0, volume = 0.3, totalAmount = 0.0, userId = 1L,
    )

    private fun stubSellVolumeContext(uuid: String) {
        coEvery { client.getAccounts() } returns listOf(Account(currency = "BTC", balance = "1.0", avgBuyPrice = "48000000"))
        coEvery { client.placeOrder(any()) } returns Order(uuid = uuid)
        coEvery { client.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 50000000.0))
    }

    @Test
    fun `executeSellVolume records the executed volume rather than the requested one`() = runTest {
        stubSellVolumeContext("sv-partial")
        coEvery { client.getOrder("sv-partial") } returns Order(uuid = "sv-partial", state = "done", executedVolume = "0.29")
        val recordSlot = slot<TradeRecord>()
        coEvery { tradeRecordRepository.save(capture(recordSlot)) } returns sellRecordEntity()

        val result = service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L)

        assertEquals(FillOutcome.CONFIRMED, result.fill)
        assertTrue(result.recorded)
        val record = recordSlot.captured
        assertEquals(0.29, record.volume, 1e-12)
        assertEquals(50000000.0 * 0.29, record.totalAmount, 1e-6)
        val pnl = record.pnlPercent!!
        assertEquals(TradePnl.amount(pnl, 48000000.0, 0.29)!!, record.pnlAmount!!, 1e-6)
        assertEquals("sv-partial", record.exchangeOrderId)
    }

    @Test
    fun `executeSellVolume records a cancelled partial fill with measured fee and funds`() = runTest {
        stubSellVolumeContext("sv-cancel-partial")
        coEvery { client.getOrder("sv-cancel-partial") } returns Order(
            uuid = "sv-cancel-partial", state = "cancel", executedVolume = "0.2", paidFee = "5000",
            trades = listOf(OrderTrade(volume = "0.2", funds = "10000000", price = "50000000")),
        )
        val recordSlot = slot<TradeRecord>()
        coEvery { tradeRecordRepository.save(capture(recordSlot)) } returns sellRecordEntity()

        val result = service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L)

        assertEquals(FillOutcome.CONFIRMED, result.fill)
        val record = recordSlot.captured
        assertEquals(0.2, record.volume, 1e-12)
        assertEquals(FeeBasis.Measured(5000.0), record.fee)
        assertEquals(10000000.0, record.orderAmount!!, 1e-6)
    }

    @Test
    fun `executeSellVolume records nothing when the order ended without a fill`() = runTest {
        stubSellVolumeContext("sv-cancel-0")
        coEvery { client.getOrder("sv-cancel-0") } returns Order(uuid = "sv-cancel-0", state = "cancel", executedVolume = "0")

        val result = service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L, username = "u", discordWebhookUrl = "hook")

        assertTrue(result.success)
        assertEquals("sv-cancel-0", result.orderUuid)
        assertFalse(result.recorded)
        assertEquals(FillOutcome.NOT_FILLED, result.fill, "terminal + 체결 0 은 확정된 미체결이라 '미확인'과 구분한다")
        coVerify(exactly = 0) { tradeRecordRepository.save(any()) }
        verify(exactly = 0) { discordNotifier.sendTradeEmbed(any(), any(), any(), any()) }
        verify(exactly = 1) { discordNotifier.sendOrderUnrecorded(FillOutcome.NOT_FILLED, "KRW-BTC", "sv-cancel-0", "0.3", "cancel", "0", "hook", "u") }
    }

    @Test
    fun `executeSellVolume does not confirm a fill that is still open after polling`() = runTest {
        // wait 는 terminal 이 아니다 — executed>0 이어도 잔여 체결분이 더 올 수 있어 확정하면 그만큼 과소 기록된다(엔진 P2 와 동일).
        stubSellVolumeContext("sv-wait")
        coEvery { client.getOrder("sv-wait") } returns Order(uuid = "sv-wait", state = "wait", executedVolume = "0.1")

        val result = service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L)

        assertEquals(FillOutcome.UNCONFIRMED, result.fill)
        assertFalse(result.recorded)
        coVerify(exactly = 0) { tradeRecordRepository.save(any()) }
        coVerify(exactly = FILL_POLL_ATTEMPTS) { client.getOrder("sv-wait") }
    }

    @Test
    fun `executeSellVolume does not fall back to the requested volume when getOrder fails`() = runTest {
        stubSellVolumeContext("sv-err")
        coEvery { client.getOrder("sv-err") } throws UpbitApiException(500, "server_error", "boom", "raw")

        val result = service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L)

        assertTrue(result.success)
        assertEquals(FillOutcome.UNCONFIRMED, result.fill)
        coVerify(exactly = 0) { tradeRecordRepository.save(any()) }
        verify(exactly = 1) { discordNotifier.sendOrderUnrecorded(FillOutcome.UNCONFIRMED, "KRW-BTC", "sv-err", "0.3", null, null, null, null) }
    }

    @Test
    fun `executeSellVolume keeps polling until the order becomes terminal`() = runTest {
        stubSellVolumeContext("sv-late")
        coEvery { client.getOrder("sv-late") } returnsMany listOf(
            Order(uuid = "sv-late", state = "wait", executedVolume = "0.1"),
            Order(uuid = "sv-late", state = "done", executedVolume = "0.3"),
        )
        val recordSlot = slot<TradeRecord>()
        coEvery { tradeRecordRepository.save(capture(recordSlot)) } returns sellRecordEntity()

        val result = service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L)

        assertEquals(FillOutcome.CONFIRMED, result.fill)
        assertEquals(0.3, recordSlot.captured.volume, 1e-12)
        coVerify(exactly = 2) { client.getOrder("sv-late") }
    }

    @Test
    fun `executeSellAll records the executed volume and keeps the pre-order average price`() = runTest {
        coEvery { client.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.5", avgBuyPrice = "48000000"))
        coEvery { client.placeOrder(any()) } returns Order(uuid = "sa-partial")
        coEvery { client.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 50000000.0))
        coEvery { client.getOrder("sa-partial") } returns Order(uuid = "sa-partial", state = "cancel", executedVolume = "0.4")
        val recordSlot = slot<TradeRecord>()
        coEvery { tradeRecordRepository.save(capture(recordSlot)) } returns sellRecordEntity()

        val result = service.executeSellAll(client, "KRW-BTC", "manual", 1L)

        assertEquals(FillOutcome.CONFIRMED, result.fill)
        val record = recordSlot.captured
        assertEquals(0.4, record.volume, 1e-12)
        assertEquals((50.0 / 48.0 - 1.0) * 100.0 - 0.1, record.pnlPercent!!, 1e-9)
        assertEquals("sa-partial", record.exchangeOrderId)
    }

    @Test
    fun `manual order post-processing propagates cancellation instead of reporting recorded false`() = runTest {
        stubSellVolumeContext("sv-cancelled")
        coEvery { client.getOrder("sv-cancelled") } returns Order(uuid = "sv-cancelled", state = "done", executedVolume = "0.3")
        coEvery { tradeRecordRepository.save(any()) } throws CancellationException("request cancelled")

        val ex = runCatching { service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L) }.exceptionOrNull()

        assertTrue(ex is CancellationException, "취소는 후처리 실패(recorded=false)로 위장하지 않고 전파돼야 한다: $ex")
    }

    @Test
    fun `executeSellVolume treats a non-finite executed volume as unconfirmed`() = runTest {
        // toDoubleOrNull 은 "NaN"·"Infinity" 를 정상 파싱한다 — 그대로 두면 그 행이 SUM 을 영구 오염시킨다(Order.feeBasis 와 같은 함정).
        // NaN 은 어떤 비교도 false 라 isFinite() 없이도 걸러지고, Infinity 만이 그 가드를 실제로 검증한다.
        for (raw in listOf("NaN", "Infinity")) {
            stubSellVolumeContext("sv-$raw")
            coEvery { client.getOrder("sv-$raw") } returns Order(uuid = "sv-$raw", state = "done", executedVolume = raw)

            val result = service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L)

            assertEquals(FillOutcome.UNCONFIRMED, result.fill, raw)
        }
        coVerify(exactly = 0) { tradeRecordRepository.save(any()) }
    }

    @Test
    fun `executeSellVolume falls back to the estimated fee when paid_fee is absent`() = runTest {
        stubSellVolumeContext("sv-nofee")
        coEvery { client.getOrder("sv-nofee") } returns Order(uuid = "sv-nofee", state = "done", executedVolume = "0.3")
        val recordSlot = slot<TradeRecord>()
        coEvery { tradeRecordRepository.save(capture(recordSlot)) } returns sellRecordEntity()

        service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L)

        assertEquals(FeeBasis.Estimate, recordSlot.captured.fee, "매도 대금은 실측이라 추정이 정당하다 — Unrecorded(0) 로 떨어지면 과소계상")
        assertNull(recordSlot.captured.orderAmount)
    }

    @Test
    fun `executeSellVolume uses the measured funds as totalAmount when the tick is unavailable`() = runTest {
        // totalAmount=0 이면 라운드트립 gross 손익이 −(평단×수량) 즉 전액 손실로 계산된다 — 실측 대금이 있는데 0 을 적을 이유가 없다.
        coEvery { client.getAccounts() } returns listOf(Account(currency = "BTC", balance = "1.0", avgBuyPrice = "48000000"))
        coEvery { client.placeOrder(any()) } returns Order(uuid = "sv-notick")
        coEvery { client.getTicker("KRW-BTC") } returns emptyList()
        coEvery { client.getOrder("sv-notick") } returns Order(
            uuid = "sv-notick", state = "done", executedVolume = "0.2",
            trades = listOf(OrderTrade(volume = "0.2", funds = "10000000", price = "50000000")),
        )
        val recordSlot = slot<TradeRecord>()
        coEvery { tradeRecordRepository.save(capture(recordSlot)) } returns sellRecordEntity()

        service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L)

        assertEquals(0.0, recordSlot.captured.price)
        assertEquals(10000000.0, recordSlot.captured.totalAmount, 1e-6)
        assertNull(recordSlot.captured.pnlPercent)
    }

    @Test
    fun `manual sell recording completes even if the request is cancelled mid-flight`() = runTest {
        // 브라우저 이탈로 요청 코루틴이 취소돼도 접수된 주문의 기록은 완주해야 한다(NonCancellable). 취소가 polling 중에 들어온다.
        stubSellVolumeContext("sv-nc")
        coEvery { client.getOrder("sv-nc") } coAnswers {
            delay(100)
            Order(uuid = "sv-nc", state = "done", executedVolume = "0.3")
        }
        coEvery { tradeRecordRepository.save(any()) } returns sellRecordEntity()

        val job = launch { service.executeSellVolume(client, "KRW-BTC", "0.3", "manual", 1L) }
        advanceTimeBy(50)
        job.cancel()
        advanceUntilIdle()

        coVerify(exactly = 1) { tradeRecordRepository.save(any()) }
    }
}
