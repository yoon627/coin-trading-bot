package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.TradeExecutionRepository
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.entity.TradeExecutionEntity
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.atomic.AtomicBoolean
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
        // saveAudit 이 awaitSingle 하는 두 호출 — 명시 stub 이 없으면 relaxed mockk 의 Mono 가 emit 하지 않아
        // 무한 대기 → UncompletedCoroutinesError.
        every { tradeExecutionRepository.save(any()) } returns Mono.just(mockk<TradeExecutionEntity>(relaxed = true))
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
    fun `commitFill skips an order already recorded but still commits the state`() = runTest {
        // #20: 재시작 후 같은 주문 uuid 로 reconcile 이 다시 기록을 시도하면 저장을 건너뛰고 false — 호출자는 알림을 보내지 않는다.
        // pending 해소는 그래도 커밋돼야 다음 tick 이 같은 주문을 또 붙잡지 않는다.
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "dup-1") } returns Mono.just(true)
        val statePersisted = AtomicBoolean(false)
        val record = TradeRecord(
            ticker = "KRW-BTC", side = TradeSide.BUY, price = 50000000.0, volume = 0.001,
            totalAmount = 50000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
            exchangeOrderId = "dup-1", userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = null,
        )

        val recorded = service.commitFill(persistState = { statePersisted.set(true) }, record = record)

        assertFalse(recorded)
        assertTrue(statePersisted.get())
        coVerify(exactly = 0) { tradeRecordRepository.save(any()) }
        coVerify(exactly = 0) { tradeExecutionRepository.save(any()) }
    }

    @Test
    fun `commitFill records an order it has not seen before`() = runTest {
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "new-1") } returns Mono.just(false)
        val record = TradeRecord(
            ticker = "KRW-BTC", side = TradeSide.BUY, price = 50000000.0, volume = 0.001,
            totalAmount = 50000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
            exchangeOrderId = "new-1", userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = null,
        )

        val recorded = service.commitFill(persistState = {}, record = record)

        assertTrue(recorded)
        coVerify(exactly = 1) { tradeRecordRepository.save(record) }
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
}
