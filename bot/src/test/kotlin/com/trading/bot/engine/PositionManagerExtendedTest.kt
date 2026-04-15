package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PositionManagerExtendedTest {

    private lateinit var upbitClient: UpbitClient
    private val properties = TradingProperties(
        takeProfitPct = 5.0,
        maxLossPct = 3.0,
        trailingStopPct = 2.0,
        maxInvestAmount = 100000.0,
    )
    private lateinit var manager: PositionManager

    @BeforeEach
    fun setup() {
        upbitClient = mockk(relaxed = true)
        manager = PositionManager(upbitClient, properties)
    }

    // --- syncPosition tests ---

    @Test
    fun `syncPosition updates state when holding exists`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.5", avgBuyPrice = "50000000")
        )
        val state = TradingState("KRW-BTC")

        manager.syncPosition("KRW-BTC", state)

        assertTrue(state.position)
        assertEquals(50000000.0, state.avgBuyPrice)
        assertEquals(0.5, state.holdVolume)
    }

    @Test
    fun `syncPosition does not modify state when no holding`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "1000000")
        )
        val state = TradingState("KRW-BTC")

        manager.syncPosition("KRW-BTC", state)

        assertFalse(state.position)
    }

    @Test
    fun `syncPosition handles API error gracefully`() = runTest {
        coEvery { upbitClient.getAccounts() } throws RuntimeException("API error")
        val state = TradingState("KRW-BTC")

        manager.syncPosition("KRW-BTC", state)

        assertFalse(state.position) // unchanged
    }

    // --- buy tests ---

    @Test
    fun `buy returns null when insufficient funds`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "1000") // too low
        )
        val state = TradingState("KRW-BTC")

        val result = manager.buy("KRW-BTC", state, 50000000.0, "test_strategy")
        assertNull(result)
    }

    @Test
    fun `buy returns TradeRecord on success`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "200000")
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "buy-123")

        val state = TradingState("KRW-BTC")
        val result = manager.buy("KRW-BTC", state, 50000000.0, "volatility_breakout")

        assertNotNull(result)
        assertEquals("KRW-BTC", result!!.ticker)
        assertEquals(TradeSide.BUY, result.side)
        assertEquals(50000000.0, result.price)
        assertEquals("volatility_breakout", result.strategy)
        assertTrue(state.position)
    }

    @Test
    fun `buy uses max invest amount when balance exceeds it`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "5000000") // 5M KRW
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "buy-456")

        val state = TradingState("KRW-BTC")
        val result = manager.buy("KRW-BTC", state, 50000000.0, "test")

        assertNotNull(result)
        assertEquals(100000.0, result!!.totalAmount) // capped at maxInvestAmount
    }

    // --- sell tests ---

    @Test
    fun `sell returns null when no position`() = runTest {
        val state = TradingState("KRW-BTC", position = false)
        val result = manager.sell("KRW-BTC", state, 50000000.0, SellReason.MANUAL)
        assertNull(result)
    }

    @Test
    fun `sell returns TradeRecord with PnL on success`() = runTest {
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "sell-789")

        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)

        val result = manager.sell("KRW-BTC", state, 52000000.0, SellReason.TAKE_PROFIT)

        assertNotNull(result)
        assertEquals(TradeSide.SELL, result!!.side)
        assertEquals("TAKE_PROFIT", result.reason)
        assertTrue(result.pnlPercent!! > 0) // profit
        assertFalse(state.position) // position closed
    }

    @Test
    fun `sell handles API error gracefully`() = runTest {
        coEvery { upbitClient.placeOrder(any()) } throws RuntimeException("Network error")

        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)

        val result = manager.sell("KRW-BTC", state, 52000000.0, SellReason.MANUAL)
        assertNull(result)
        assertTrue(state.position) // state unchanged on failure
    }

    // --- checkTakeProfit tests ---

    @Test
    fun `checkTakeProfit returns false when no position`() {
        val state = TradingState("KRW-BTC", position = false)
        assertFalse(manager.checkTakeProfit(state, 50000000.0))
    }

    @Test
    fun `checkTakeProfit returns true when PnL exceeds threshold`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        // 5% profit: 50M -> 52.5M
        assertTrue(manager.checkTakeProfit(state, 52500000.0))
    }

    @Test
    fun `checkTakeProfit returns false when PnL below threshold`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        // 2% profit: not enough (threshold is 5%)
        assertFalse(manager.checkTakeProfit(state, 51000000.0))
    }

    // --- checkStopLoss tests ---

    @Test
    fun `checkStopLoss returns true when loss exceeds threshold`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        // -3% loss: 50M -> 48.5M
        assertTrue(manager.checkStopLoss(state, 48500000.0))
    }

    @Test
    fun `checkStopLoss returns false when loss is small`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        // -1% loss
        assertFalse(manager.checkStopLoss(state, 49500000.0))
    }

    // --- checkTrailingStop tests ---

    @Test
    fun `checkTrailingStop returns false when not in profit`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        state.updatePeakPrice(51000000.0)
        // Price dropped but still below buy price
        assertFalse(manager.checkTrailingStop(state, 49000000.0))
    }

    @Test
    fun `checkTrailingStop returns true when drop from peak exceeds threshold`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        state.updatePeakPrice(55000000.0) // peak at 55M (+10%)
        // Drop from peak: (55M - 53.8M) / 55M = 2.18% > 2% threshold
        assertTrue(manager.checkTrailingStop(state, 53800000.0))
    }

    @Test
    fun `checkTrailingStop returns false when drop is small`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        state.updatePeakPrice(55000000.0) // peak at 55M
        // Drop from peak: (55M - 54.5M) / 55M = 0.9% < 2% threshold
        assertFalse(manager.checkTrailingStop(state, 54500000.0))
    }
}
