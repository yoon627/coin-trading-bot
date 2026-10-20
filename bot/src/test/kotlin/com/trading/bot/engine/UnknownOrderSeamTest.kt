package com.trading.bot.engine

import ch.qos.logback.classic.Level
import com.trading.bot.characterization.MutableClock
import com.trading.bot.characterization.capturingLogs
import com.trading.bot.characterization.krw
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.Ticker
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.exchange.OrderLookupException
import com.trading.bot.exchange.UnknownOrderPolicy
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 응답을 못 받은 주문 판정의 거래소별 seam. Upbit 클라이언트는 이 경로의 예외를 던지지 않는다. */
class UnknownOrderSeamTest {

    private val clock = MutableClock(Instant.parse("2026-10-18T00:00:00Z"), TradingDay.KST)

    /** 주문을 모르는 거래소. 잔고는 주문 전과 같아 흔적이 없다. */
    private class NeverFindsOrders : ExchangeClient {
        override suspend fun getAccounts(): List<Account> = emptyList()
        override suspend fun getDayCandles(market: String, count: Int): List<Candle> = emptyList()
        override suspend fun getTicker(markets: String): List<Ticker> = emptyList()
        override suspend fun placeOrder(request: OrderRequest): Order = error("not used")
        override suspend fun getOrder(uuid: String): Order = error("not used")
        override suspend fun getOrderByIdentifier(identifier: String): Order? = null
    }

    private fun lostBuy() = TradingState("KRW-BTC").apply { beginBuyOrder("ctb-1", "test", priorVolume = 0.0) }

    @Test
    fun `without client order ids a missing order is never released as not placed`() = runTest {
        val resolver = UnknownOrderResolver(
            NeverFindsOrders(), clock,
            UnknownOrderPolicy(notPlacedMisses = 2, notPlacedAfter = Duration.ofSeconds(60), supportsClientOrderId = false),
        )
        val state = lostBuy()

        val verdicts = List(4) {
            resolver.resolve("KRW-BTC", TradeSide.BUY, state, "ctb-1").also { clock.advance(60) }
        }

        assertTrue(verdicts.none { it == UnknownOrderVerdict.NotPlaced }, verdicts.toString())
        assertTrue(verdicts.all { it is UnknownOrderVerdict.NeedsReview }, verdicts.toString())
    }

    @Test
    fun `with client order ids the same misses release the order under the upbit rule`() = runTest {
        val resolver = UnknownOrderResolver(NeverFindsOrders(), clock, UnknownOrderPolicy.UPBIT)
        val state = lostBuy()

        val first = resolver.resolve("KRW-BTC", TradeSide.BUY, state, "ctb-1")
        clock.advance(60)
        val second = resolver.resolve("KRW-BTC", TradeSide.BUY, state, "ctb-1")

        assertEquals(UnknownOrderVerdict.Undecided(UnknownOrderVerdict.Reason.AWAITING_CONFIRMATION), first)
        assertEquals(UnknownOrderVerdict.NotPlaced, second)
    }

    @Test
    fun `a not-yet-visible answer between misses restarts the miss streak`() = runTest {
        val client = mockk<ExchangeClient>(relaxed = true)
        coEvery { client.getOrderByIdentifier("ctb-1") } returns null andThenThrows OrderLookupException.NotYetVisible() andThen null
        val resolver = UnknownOrderResolver(client, clock, UnknownOrderPolicy.UPBIT)
        val state = lostBuy()

        val verdicts = List(3) {
            resolver.resolve("KRW-BTC", TradeSide.BUY, state, "ctb-1").also { clock.advance(60) }
        }

        assertEquals(UnknownOrderVerdict.Undecided(UnknownOrderVerdict.Reason.AWAITING_CONFIRMATION), verdicts.last())
    }

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val manager = PositionManager(upbit, TradingProperties(), mockk<TradingStateService>(relaxed = true), 1L, clock = clock)

    private suspend fun pendingBuyByIdentifier(): TradingState {
        val state = TradingState("KRW-BTC")
        coEvery { upbit.getAccounts() } returns listOf(krw())
        coEvery { upbit.placeOrder(any()) } throws RuntimeException("ReadTimeout")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        check(state.pendingBuyIdentifier != null)
        return state
    }

    @Test
    fun `an order not yet visible waits without counting toward the halt`() = runTest {
        val state = pendingBuyByIdentifier()
        coEvery { upbit.getOrderByIdentifier(any()) } throws OrderLookupException.NotYetVisible()

        repeat(TradingProperties().reconcileHaltThreshold + 1) { manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0) }

        assertEquals(0, state.reconcileFailureCount)
        assertFalse(state.halted)
        assertTrue(state.hasPendingBuy())
    }

    @Test
    fun `an order that needs review halts the ticker with one error and keeps the pending`() = runTest {
        val state = pendingBuyByIdentifier()
        coEvery { upbit.getOrderByIdentifier(any()) } throws OrderLookupException.NeedsReview("두 건이 맞는다")

        val errors = capturingLogs(PositionManager::class.java) { logs ->
            repeat(3) { manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0) }
            logs.list.filter { it.level == Level.ERROR && "[REVIEW]" in it.formattedMessage }
        }

        assertTrue(state.halted)
        assertTrue("두 건이 맞는다" in state.haltReason.orEmpty(), state.haltReason)
        assertEquals(1, errors.size, errors.toString())
        assertTrue(state.hasPendingBuy())
    }

    @Test
    fun `review reasons that change per lookup alert once and fit the stored halt reason`() = runTest {
        val state = pendingBuyByIdentifier()
        var lookup = 0
        coEvery { upbit.getOrderByIdentifier(any()) } answers {
            lookup++
            throw OrderLookupException.NeedsReview("후보 $lookup 건: " + "가".repeat(300))
        }

        val errors = capturingLogs(PositionManager::class.java) { logs ->
            repeat(3) { manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0) }
            logs.list.filter { it.level == Level.ERROR && "[REVIEW]" in it.formattedMessage }
        }

        assertEquals(1, errors.size, errors.toString())
        assertTrue(state.haltReason.orEmpty().length <= 200, state.haltReason)
        assertTrue("후보 1 건" in state.haltReason.orEmpty(), state.haltReason)
    }

    @Test
    fun `a sell that needs review halts new entries but keeps the pending sell`() = runTest {
        val state = TradingState("KRW-BTC", position = true, holdVolume = 0.001, avgBuyPrice = 50_000_000.0).apply {
            beginSellOrder("ctb-s", SellReason.STOP_LOSS, clock.instant(), 0.001, 49_000_000.0, 0.001)
        }
        coEvery { upbit.getOrderByIdentifier("ctb-s") } throws OrderLookupException.NeedsReview("체결 후보 없음")

        manager.reconcilePendingSell("KRW-BTC", state, 49_000_000.0)

        assertTrue(state.halted)
        assertEquals("ctb-s", state.pendingSellIdentifier)
    }
}
