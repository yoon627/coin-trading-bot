package com.trading.research.risk

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.OrderSide
import com.trading.research.portfolio.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RiskManagerTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    private fun pos(entry: Double = 100.0, peak: Double = 100.0, openedAt: Long = 0L) =
        Position(asset, quantity = 1.0, avgEntryPrice = entry, peakPriceSinceEntry = peak, openedAtBarIndex = openedAt)

    @Test
    fun `stop loss fires when loss exceeds threshold`() {
        val rm = RiskManager(RiskPolicy(stopLossPct = 0.03, trailingStopPct = null, takeProfitPct = null, timeExitBars = null))
        val orders = rm.evaluate(pos(entry = 100.0), lastPrice = 96.0, currentBarIndex = 10)
        assertEquals(1, orders.size)
        assertEquals("STOP_LOSS", orders[0].tag)
        assertEquals(OrderSide.SELL, orders[0].side)
    }

    @Test
    fun `trailing stop fires only when profitable and retraced`() {
        val rm = RiskManager(RiskPolicy(stopLossPct = 0.03, trailingStopPct = 0.02, takeProfitPct = null, timeExitBars = null))
        // entry 100, peak 120 → 2% retrace = 117.6 triggers
        val p = pos(entry = 100.0, peak = 120.0)
        val out = rm.evaluate(p, lastPrice = 117.0, currentBarIndex = 5)
        assertEquals(1, out.size)
        assertEquals("TRAILING_STOP", out[0].tag)
    }

    @Test
    fun `time exit fires when hold exceeds maxBars`() {
        val rm = RiskManager(RiskPolicy(stopLossPct = null, trailingStopPct = null, takeProfitPct = null, timeExitBars = 7))
        val out = rm.evaluate(pos(openedAt = 0), lastPrice = 100.0, currentBarIndex = 8)
        assertEquals(1, out.size)
        assertEquals("TIME_EXIT", out[0].tag)
    }

    @Test
    fun `take profit fires when gain exceeds threshold`() {
        val rm = RiskManager(RiskPolicy(stopLossPct = null, trailingStopPct = null, takeProfitPct = 0.05, timeExitBars = null))
        val out = rm.evaluate(pos(entry = 100.0), lastPrice = 106.0, currentBarIndex = 3)
        assertEquals(1, out.size)
        assertEquals("TAKE_PROFIT", out[0].tag)
    }

    @Test
    fun `no exit when none of the rules trigger`() {
        val rm = RiskManager(RiskPolicy(stopLossPct = 0.05, trailingStopPct = 0.02, takeProfitPct = 0.10, timeExitBars = 30))
        val out = rm.evaluate(pos(entry = 100.0, peak = 102.0), lastPrice = 101.0, currentBarIndex = 5)
        assertEquals(0, out.size)
    }
}
