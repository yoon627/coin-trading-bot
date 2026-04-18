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
    fun `total-drawdown kill-switch halts on the breaching bar, not one bar later`() = runTest {
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        // Bar 0 close=100 (peak established at entry)
        // Bar 1 open=100 close=90  → buy fills at 100, equity ≈ 9000 (10% DD, safe under 20%)
        // Bar 2 open=90  close=70  → equity ≈ 7000 (30% DD — must halt AT this bar's close)
        // Bar 3 open=70  close=60  → must never be processed after the fix
        val opens  = listOf(100.0, 100.0, 90.0, 70.0)
        val closes = listOf(100.0,  90.0, 70.0, 60.0)
        val bars = opens.indices.map { i ->
            val t = t0.plusSeconds(i.toLong() * 86400)
            val open = opens[i]; val close = closes[i]
            Bar(t, t.plusSeconds(86400), open, maxOf(open, close), minOf(open, close), close, 1.0, close)
        }

        // Strategy buys on bar 0: signal queued for bar 1 open fill so position exposure mirrors the drop.
        val strategy = object : ResearchStrategy {
            override val name = "BuyOnBar0"
            override val warmupBars = 0
            override suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest> {
                return if (event.barIndex == 0L && !ctx.portfolio.hasPosition(event.asset))
                    listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.FixedFraction(1.0)))
                else emptyList()
            }
        }

        val config = BacktestRunConfig(
            strategy = strategy,
            history = mapOf(asset to bars),
            initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(feeRate = 0.0, slippageBps = 0.0),
            risk = RiskPolicy(
                stopLossPct = null,
                trailingStopPct = null,
                takeProfitPct = null,
                timeExitBars = null,
            ),
            killSwitch = KillSwitch(totalDdHaltPct = 0.20),
        )

        val result = Engine.run(config)

        // Expected semantics after the Apr-2026 post-merge reorder + codex-review follow-up:
        //   - Halt observes bar-2 close (-30% from peak) in the SAME bar-2 iteration (not deferred).
        //   - Bar 2 IS recorded before the break — otherwise the curve silently omits the breach
        //     loss and Sharpe/MaxDD look better than they were.
        //   - Bar 3 is never processed (no fills, no record).
        assertEquals(3, result.equityCurve.size, "halt bar's close must land in equity curve")
        assertEquals(
            7000.0,
            result.equityCurve.last().equity,
            1e-6,
            "last equity-curve entry must be the breaching bar's close",
        )
    }

    @Test
    fun `total-drawdown halt waits for all same-closeTime marks before triggering`() = runTest {
        // Two assets share every closeTime. On bar 1 A crashes (-70%) and B rallies (+70%).
        // With per-event halting, A's partial mark at bar 1 showed ~35% DD and halted
        // before B's mark could offset — a false halt that blocks multi-asset backtests.
        // Post-batching, the halt check runs once per closeTime group, after both marks.
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        val t1 = t0.plusSeconds(86400)
        val t2 = t0.plusSeconds(2 * 86400)

        val assetA = Asset(Exchange.UPBIT, "A/KRW")
        val assetB = Asset(Exchange.UPBIT, "B/KRW")

        // Bar 0: both flat at 100. Bar 1: A closes at 30, B closes at 170. Symmetric.
        val barsA = listOf(
            Bar(t0, t1, 100.0, 100.0, 100.0, 100.0, 1.0, 100.0),
            Bar(t1, t2, 100.0, 100.0, 30.0, 30.0, 1.0, 30.0),
        )
        val barsB = listOf(
            Bar(t0, t1, 100.0, 100.0, 100.0, 100.0, 1.0, 100.0),
            Bar(t1, t2, 100.0, 170.0, 100.0, 170.0, 1.0, 170.0),
        )

        // Strategy queues a Notional(5000) buy for each asset on its bar-0 event; both fill at bar 1 open.
        val strategy = object : ResearchStrategy {
            override val name = "BuyEachOnBar0"
            override val warmupBars = 0
            override suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest> {
                return if (event.barIndex == 0L && !ctx.portfolio.hasPosition(event.asset))
                    listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.Notional(5000.0)))
                else emptyList()
            }
        }

        val config = BacktestRunConfig(
            strategy = strategy,
            history = mapOf(assetA to barsA, assetB to barsB),
            initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(feeRate = 0.0, slippageBps = 0.0),
            risk = RiskPolicy(null, null, null, null),
            killSwitch = KillSwitch(totalDdHaltPct = 0.20),
        )

        val result = Engine.run(config)

        // Both bars processed fully → one equity-curve entry per closeTime date.
        assertEquals(2, result.equityCurve.size, "halt must not fire on the partial A-only mark")
        // Last entry reflects the fully-marked bar-1 portfolio: 50*30 (A) + 50*170 (B) = 10_000.
        assertEquals(
            10_000.0,
            result.equityCurve.last().equity,
            1e-6,
            "final equity must reflect both assets' bar-1 closes, not just A's",
        )
    }

    @Test
    fun `daily-DD entry block waits for all same-closeTime marks`() = runTest {
        // Same partial-mark issue as total-DD halt batching, but for the entry-block gate.
        // On bar 1, A's close (50) alone shows 15% daily DD vs dayStart (10_000) — enough to
        // trip shouldBlockEntries. B's same-closeTime close (150) restores equity to the
        // 10% threshold. Pre-fix: the gate was evaluated per event, so any signal emitted
        // on A's bar-1 event was dropped based on asset tie-break order. Post-fix: signals
        // buffer per closeTime group and submit at the last event using fully-marked equity.
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        val t1 = t0.plusSeconds(86400)
        val t2 = t0.plusSeconds(2 * 86400)
        val t3 = t0.plusSeconds(3 * 86400)

        val assetA = Asset(Exchange.UPBIT, "A/KRW")
        val assetB = Asset(Exchange.UPBIT, "B/KRW")

        // Bar 0 flat at 100 seeds bar-1 open fills of Notional(3000) each.
        // Bar 1 close: A=50 (partial DD 15%), B=150 (full DD 0%).
        // Bar 2 flat at bar-1 close so the extra bar-1 signal can fill cleanly at bar-2 open.
        val barsA = listOf(
            Bar(t0, t1, 100.0, 100.0, 100.0, 100.0, 1.0, 100.0),
            Bar(t1, t2, 100.0, 100.0, 50.0, 50.0, 1.0, 50.0),
            Bar(t2, t3, 50.0, 50.0, 50.0, 50.0, 1.0, 50.0),
        )
        val barsB = listOf(
            Bar(t0, t1, 100.0, 100.0, 100.0, 100.0, 1.0, 100.0),
            Bar(t1, t2, 100.0, 150.0, 100.0, 150.0, 1.0, 150.0),
            Bar(t2, t3, 150.0, 150.0, 150.0, 150.0, 1.0, 150.0),
        )

        val strategy = object : ResearchStrategy {
            override val name = "ExtraBuyOnBar1A"
            override val warmupBars = 0
            override suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest> {
                // Bar 0: seed positions for both assets; fills at bar 1 open.
                if (event.barIndex == 0L) {
                    return listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.Notional(3000.0)))
                }
                // Bar 1 event A only: emit a small extra BUY on A. Under partial-mark gating,
                // shouldBlockEntries(8500) fires (15% DD) and the signal is dropped. Under the
                // group-gated fix, the decision is deferred to the end of the closeTime group
                // where full-mark equity is 10_000 → no block → signal submits → bar 2 fill.
                if (event.barIndex == 1L && event.asset == assetA) {
                    return listOf(OrderRequest(assetA, OrderSide.BUY, SizingRule.Notional(500.0)))
                }
                return emptyList()
            }
        }

        val config = BacktestRunConfig(
            strategy = strategy,
            history = mapOf(assetA to barsA, assetB to barsB),
            initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(feeRate = 0.0, slippageBps = 0.0),
            risk = RiskPolicy(null, null, null, null),
            killSwitch = KillSwitch(dailyDdHaltPct = 0.10, totalDdHaltPct = null),
        )

        val result = Engine.run(config)

        // 2 bar-0 seed fills at bar 1 open + 1 extra A fill at bar 2 open = 3 total.
        // Pre-fix this is 2 because the bar-1 extra signal is dropped by the partial mark.
        assertEquals(
            3,
            result.fills.size,
            "daily-DD entry block must not fire on partial per-asset marks; got ${result.fills}",
        )
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
