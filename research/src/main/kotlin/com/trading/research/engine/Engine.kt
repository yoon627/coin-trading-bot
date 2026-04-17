package com.trading.research.engine

import com.trading.research.domain.Asset
import com.trading.research.domain.OrderRequest
import com.trading.research.execution.FillSimulator
import com.trading.research.execution.OrderBook
import com.trading.research.metrics.MetricsAccumulator
import com.trading.research.portfolio.Fill
import com.trading.research.portfolio.Portfolio
import com.trading.research.risk.RiskManager
import com.trading.research.sizing.SizingCalculator
import com.trading.research.strategy.ResearchContextImpl
import com.trading.research.strategy.RollingUniverseView

/**
 * Central orchestrator of the research backtest loop.
 *
 * Per-bar pipeline (see Task 13 design notes for rationale):
 *   1. Fill pending entry orders at this bar's OPEN (submitted on prior bars).
 *   2. Fill pending exit orders at this bar's OPEN (risk-triggered on prior bars).
 *   3. Kill-switch halt check (total drawdown).
 *   4. Risk evaluation on open positions using this bar's CLOSE → queue exits for next bar.
 *   5. Strategy.onBar(ctx, event) → queue entries for next bar (unless kill-switch blocks).
 *   6. Mark-to-market at close + peak/metric updates.
 *
 * Anti-lookahead invariants:
 *  - Strategy signals emitted on bar N are filled at bar N+1's open, never same-bar close-to-close.
 *  - Risk-triggered exits on bar N's close are filled at bar N+1's open, matching entry semantics.
 *  - [RollingUniverseView] is advanced BEFORE strategy.onBar so recentBars() includes the just-closed
 *    current bar (whose close the strategy already observed via the event) and nothing further.
 */
object Engine {

    suspend fun run(config: BacktestRunConfig): RunResult {
        val strategy = config.strategy
        val portfolio = Portfolio(config.initialCash)
        val clock = ResearchClock()
        val orderBook = OrderBook()
        val fillSim = FillSimulator(config.costModel, SizingCalculator)
        val risk = RiskManager(config.risk)
        val metrics = MetricsAccumulator()
        val allFills = mutableListOf<Fill>()
        val closedTrades = mutableListOf<ClosedTrade>()

        val initialIndices = config.history.keys.associateWith { 0L }
        val universe = RollingUniverseView(config.history, initialIndices)

        val stream = BarStream(config.history)

        val pendingExits = mutableListOf<OrderRequest>()
        val entryBarByAsset = mutableMapOf<Asset, Long>()

        for (event in stream) {
            clock.advanceTo(event.bar.closeTime)
            universe.advance(event.asset, event.barIndex)

            val entryFills = fillSim.fill(
                orders = orderBook.drain(),
                openPrices = mapOf(event.asset to event.bar.open),
                equityBefore = portfolio.totalEquity,
                assetDailyVol = mapOf(event.asset to 0.0),
                barIndex = event.barIndex,
            )
            entryFills.forEach { fill ->
                portfolio.applyFill(fill)
                allFills.add(fill)
                entryBarByAsset[fill.asset] = event.barIndex
            }

            val exitsForThisAsset = pendingExits.filter { it.asset == event.asset }
            val exitFills = fillSim.fillExit(
                orders = exitsForThisAsset,
                openPrices = mapOf(event.asset to event.bar.open),
                portfolio = portfolio,
                barIndex = event.barIndex,
            )
            exitFills.forEach { fill ->
                val position = portfolio.positions[fill.asset]
                    ?: error("exit fill without open position on ${fill.asset}")
                val entryIndex = entryBarByAsset[fill.asset] ?: 0L
                val pnl = (fill.fillPrice - position.avgEntryPrice) * fill.quantity - fill.fee
                closedTrades.add(
                    ClosedTrade(
                        asset = fill.asset,
                        entryBarIndex = entryIndex,
                        exitBarIndex = event.barIndex,
                        entryPrice = position.avgEntryPrice,
                        exitPrice = fill.fillPrice,
                        quantity = fill.quantity,
                        pnl = pnl,
                        reason = fill.tag,
                    ),
                )
                portfolio.applyFill(fill)
                allFills.add(fill)
                entryBarByAsset.remove(fill.asset)
            }
            pendingExits.removeAll { it.asset == event.asset }

            if (config.killSwitch.shouldHaltSimulation(portfolio.totalEquity)) break

            val position = portfolio.positions[event.asset]
            if (position != null) {
                val exits = risk.evaluate(position, event.bar.close, event.barIndex)
                pendingExits.addAll(exits)
            }

            val context = ResearchContextImpl(clock, portfolio.view(), universe, config.params)
            val signals = strategy.onBar(context, event)
            if (!config.killSwitch.shouldBlockEntries(clock.currentDate(), portfolio.totalEquity)) {
                orderBook.submitAll(signals)
            }

            portfolio.markToMarket(mapOf(event.asset to event.bar.close))
            config.killSwitch.onPeakUpdate(portfolio.totalEquity)
            metrics.recordDailyEquity(clock.currentDate(), portfolio.totalEquity)
        }

        return RunResult(
            strategyName = strategy.name,
            fills = allFills,
            equityCurve = metrics.curve(),
            finalEquity = portfolio.totalEquity,
            tradesClosed = closedTrades,
        )
    }
}
