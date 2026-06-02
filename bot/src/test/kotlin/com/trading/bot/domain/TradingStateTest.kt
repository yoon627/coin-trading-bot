package com.trading.bot.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TradingStateTest {

    @Test
    fun `pnlPercent calculates positive PnL correctly`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        assertEquals(4.0, state.pnlPercent(52000000.0), 0.01)
    }

    @Test
    fun `pnlPercent calculates negative PnL correctly`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        assertEquals(-2.0, state.pnlPercent(49000000.0), 0.01)
    }

    @Test
    fun `pnlPercent returns 0 when avgBuyPrice is 0`() {
        val state = TradingState("KRW-BTC")
        assertEquals(0.0, state.pnlPercent(50000000.0))
    }

    @Test
    fun `dropFromPeakPercent calculates correctly`() {
        val state = TradingState("KRW-BTC")
        state.updatePeakPrice(55000000.0)
        // Drop: (55M - 53M) / 55M * 100 = 3.636%
        assertEquals(3.636, state.dropFromPeakPercent(53000000.0), 0.01)
    }

    @Test
    fun `dropFromPeakPercent returns 0 when peak is 0`() {
        val state = TradingState("KRW-BTC")
        assertEquals(0.0, state.dropFromPeakPercent(50000000.0))
    }

    @Test
    fun `updatePeakPrice only increases`() {
        val state = TradingState("KRW-BTC")
        state.updatePeakPrice(50000000.0)
        assertEquals(50000000.0, state.peakPrice)
        state.updatePeakPrice(55000000.0)
        assertEquals(55000000.0, state.peakPrice)
        state.updatePeakPrice(52000000.0) // lower, should not update
        assertEquals(55000000.0, state.peakPrice)
    }

    @Test
    fun `markBought sets initial position correctly`() {
        val state = TradingState("KRW-BTC")
        assertFalse(state.position)

        state.markBought(50000000.0, 0.001)

        assertTrue(state.position)
        assertEquals(50000000.0, state.avgBuyPrice)
        assertEquals(0.001, state.holdVolume)
        assertNotNull(state.buyDate)
        assertNotNull(state.lastTradeTime)
    }

    @Test
    fun `markBought sets boughtToday so daily entry gate engages`() {
        val state = TradingState("KRW-BTC")
        assertFalse(state.boughtToday)

        state.markBought(50000000.0, 0.001)

        assertTrue(state.boughtToday)
    }

    @Test
    fun `markBought calculates weighted average for additional buys`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001) // 50K spent
        state.markBought(60000000.0, 0.001) // 60K spent

        // Average: (50M * 0.001 + 60M * 0.001) / (0.001 + 0.001) = 55M
        assertEquals(55000000.0, state.avgBuyPrice, 0.01)
        assertEquals(0.002, state.holdVolume, 0.0001)
    }

    @Test
    fun `markSold resets all position state`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        assertTrue(state.position)

        state.markSold()

        assertFalse(state.position)
        assertEquals(0.0, state.avgBuyPrice)
        assertEquals(0.0, state.holdVolume)
        assertEquals(0.0, state.peakPrice)
        assertNull(state.buyDate)
        assertNotNull(state.lastTradeTime)
    }

    @Test
    fun `resetDaily clears boughtToday flag`() {
        val state = TradingState("KRW-BTC", boughtToday = true)
        state.resetDaily()
        assertFalse(state.boughtToday)
    }

    @Test
    fun `peak price updates during markBought`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        assertEquals(50000000.0, state.peakPrice)

        state.markBought(55000000.0, 0.001) // higher price
        assertEquals(55000000.0, state.peakPrice)
    }

    @Test
    fun `markBought stores entryStrategy on initial entry`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001, "macd_cross")
        assertEquals("macd_cross", state.entryStrategy)
    }

    @Test
    fun `markBought preserves entryStrategy on additional buys`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001, "macd_cross")
        state.markBought(60000000.0, 0.001, "golden_cross") // 추가매수 — 최초 진입 전략 유지
        assertEquals("macd_cross", state.entryStrategy)
    }

    @Test
    fun `markSold clears entryStrategy`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001, "macd_cross")
        state.markSold()
        assertNull(state.entryStrategy)
    }
}
