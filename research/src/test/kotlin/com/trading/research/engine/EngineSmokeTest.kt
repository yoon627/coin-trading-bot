package com.trading.research.engine

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import com.trading.research.domain.OrderRequest
import com.trading.research.domain.OrderSide
import com.trading.research.domain.SizingRule
import com.trading.research.execution.FlatFeeSlippageModel
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import com.trading.research.strategy.BarEvent
import com.trading.research.strategy.ResearchContext
import com.trading.research.strategy.ResearchStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class EngineSmokeTest {

    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    /** Strategy that buys on bar 5 and never exits voluntarily. */
    private class BuyOnceStrategy : ResearchStrategy {
        override val name = "BuyOnce"
        override val warmupBars = 0
        override suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest> {
            return if (event.barIndex == 5L && !ctx.portfolio.hasPosition(event.asset))
                listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.FixedFraction(0.5)))
            else emptyList()
        }
    }

    @Test
    fun `engine runs 10 bars and produces result with one trade`() = runTest {
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        val bars = (0L..9L).map { i ->
            val t = t0.plusSeconds(i * 86400)
            Bar(t, t.plusSeconds(86400), 100.0 + i, 100.0 + i, 100.0 + i, 100.0 + i, 1.0, 100.0 + i)
        }

        val config = BacktestRunConfig(
            strategy = BuyOnceStrategy(),
            history = mapOf(asset to bars),
            initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(feeRate = 0.0, slippageBps = 0.0),
            risk = RiskPolicy(stopLossPct = null, trailingStopPct = null, takeProfitPct = null, timeExitBars = null),
            killSwitch = KillSwitch(),
        )

        val result = Engine.run(config)

        assertEquals(1, result.fills.size) // only the entry filled (no exit rule, simulation end closes)
        assertTrue(result.equityCurve.size >= 10)
    }

    @Test
    fun `engine closes position via stop-loss at next bar open`() = runTest {
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        // Flat prices until entry (bar 5), then sharp fall to trigger a 1% stop-loss.
        // Bars 0..5 have close=100.0 so the strategy's bar-5 entry fills at bar 6's open.
        // Bar 6 open=100, close=90 → unrealized ≈ -10% triggers stop on bar 6 close.
        // Bar 7 open=80 is the expected exit fill price (next-bar-open, anti-lookahead).
        val prices = listOf(100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 80.0, 70.0, 60.0)
        val closes = listOf(100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 90.0, 70.0, 60.0, 50.0)
        val bars = prices.mapIndexed { i, open ->
            val t = t0.plusSeconds(i.toLong() * 86400)
            val close = closes[i]
            Bar(t, t.plusSeconds(86400), open, maxOf(open, close), minOf(open, close), close, 1.0, close)
        }

        val config = BacktestRunConfig(
            strategy = BuyOnceStrategy(),
            history = mapOf(asset to bars),
            initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(feeRate = 0.0, slippageBps = 0.0),
            risk = RiskPolicy(
                stopLossPct = 0.01,
                trailingStopPct = null,
                takeProfitPct = null,
                timeExitBars = null,
            ),
            killSwitch = KillSwitch(),
        )

        val result = Engine.run(config)

        assertTrue(
            result.tradesClosed.size >= 1,
            "expected at least one closed trade, got ${result.tradesClosed.size}",
        )
        val trade = result.tradesClosed.first()
        assertEquals("STOP_LOSS", trade.reason)
        // Anti-lookahead: stop triggered on bar-6 close, fills at bar-7 open = 80.0.
        assertEquals(80.0, trade.exitPrice, 1e-9)
        // Entry filled at bar-6 open = 100.0 (signal emitted on bar 5).
        assertEquals(100.0, trade.entryPrice, 1e-9)
        // entryBarIndex must come from Position.openedAtBarIndex (bar 6), not a defaulted 0.
        assertEquals(6L, trade.entryBarIndex)
        assertEquals(7L, trade.exitBarIndex)
    }
}
