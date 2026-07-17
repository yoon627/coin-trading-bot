package com.trading.bot.engine

import com.trading.bot.client.UpbitApiException
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.Ticker
import com.trading.bot.domain.TradeRecord
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

        val result = service.executeBuy(client, "KRW-BTC", 100000.0, "volatility_breakout", 1L)

        assertTrue(result.success)
        assertEquals("order-123", result.orderUuid)
        coVerify { tradeRecordRepository.save(any()) }
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
    fun `executeSellVolume records null pnl when ticker price unavailable`() = runTest {
        // getTicker 실패(현재가 미상) 시 −100.1% 가짜 기록 방지 — pnl 은 null 보존.
        coEvery { client.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "1.0", avgBuyPrice = "48000000"),
        )
        coEvery { client.placeOrder(any()) } returns Order(uuid = "sell-noprice")
        coEvery { client.getTicker("KRW-BTC") } returns emptyList()
        val recordSlot = slot<TradeRecord>()
        coEvery { tradeRecordRepository.save(capture(recordSlot)) } returns TradeRecordEntity(
            id = 5, ticker = "KRW-BTC", side = "SELL", price = 0.0,
            volume = 0.3, totalAmount = 0.0, userId = 1L,
        )

        service.executeSellVolume(client, "KRW-BTC", "0.3", "rsi_bounce", 1L)

        assertNull(recordSlot.captured.pnlPercent)
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
        coEvery { client.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 52000000.0))
        coEvery { tradeRecordRepository.save(any()) } returns TradeRecordEntity(
            id = 3, ticker = "KRW-BTC", side = "SELL", price = 52000000.0,
            volume = 0.3, totalAmount = 15600000.0, pnlPercent = 8.33, userId = 1L,
        )

        val result = service.executeSellVolume(client, "KRW-BTC", "0.3", "rsi_bounce", 1L)

        assertTrue(result.success)
        assertEquals("sell-789", result.orderUuid)
    }
}
