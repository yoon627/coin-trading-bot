package com.trading.research.engine

import com.trading.research.domain.OrderRequest
import com.trading.research.execution.FillSimulator
import com.trading.research.execution.OrderBook
import com.trading.research.metrics.MetricsAccumulator
import com.trading.research.portfolio.Fill
import com.trading.research.portfolio.Portfolio
import com.trading.research.risk.RiskManager
import com.trading.research.sizing.SizingCalculator
import com.trading.research.strategy.BarEvent
import com.trading.research.strategy.ResearchContextImpl
import com.trading.research.strategy.RollingUniverseView
import java.time.LocalDate

/**
 * Central orchestrator of the research backtest loop.
 *
 * Per-bar pipeline (see Task 13 design notes + Apr 2026 post-merge codex review for rationale):
 *   1. Fill pending entry orders for THIS asset at this bar's OPEN; re-queue orders for other assets.
 *   2. Fill pending exit orders for THIS asset at this bar's OPEN (risk-triggered on prior bars).
 *   3. Day rollover hook — uses pre-close equity so the new day's DD baseline is set at bar OPEN.
 *   4. Mark-to-market at this bar's CLOSE + peak update.
 *   5. Record daily equity for metrics — MUST precede the halt check so a breaching bar's close
 *      still lands in [equityCurve]; otherwise Sharpe/MaxDD exclude the terminal loss.
 *   6. Kill-switch halt check — a bar that breaches total-DD on its own close halts the loop
 *      before any further orders are queued for bar N+1.
 *   7. Risk evaluation on open positions using this bar's CLOSE → queue exits for next bar.
 *   8. Strategy.onBar(ctx, event) → queue entries for next bar.
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

        // Empty indices = "no asset has advanced yet". First universe.advance() per asset
        // populates its index, so recentBars() cannot read bars before that asset's first event.
        val universe = RollingUniverseView(config.history, currentBarIndex = emptyMap())

        val stream = BarStream(config.history)

        val pendingExits = mutableListOf<OrderRequest>()
        var lastDay: LocalDate? = null

        for (event in stream) {
            clock.advanceTo(event.bar.closeTime)
            universe.advance(event.asset, event.barIndex)

            applyEntryFills(event, portfolio, orderBook, fillSim, allFills)
            applyExitFills(event, portfolio, pendingExits, fillSim, allFills, closedTrades)

            lastDay = rolloverKillSwitchIfNeeded(config, clock, portfolio, lastDay)

            portfolio.markToMarket(mapOf(event.asset to event.bar.close))
            config.killSwitch.onPeakUpdate(portfolio.totalEquity)
            // Record the halting bar's close BEFORE breaking. Omitting it would truncate
            // the equity curve one bar short of finalEquity, producing Sharpe/MaxDD values
            // that silently exclude the terminal breach loss.
            metrics.recordDailyEquity(clock.currentDate(), portfolio.totalEquity)
            // TODO(multi-asset halt): on portfolios where several assets share a closeTime,
            // [BarStream] emits each as a separate event. The halt check here runs after
            // marking ONLY event.asset, so the first asset in the tie-break order may trip
            // the kill-switch on a partial mark even if later same-timestamp assets would
            // offset the drawdown. Safe for single-asset v1; fix requires batching events
            // by closeTime before the halt check.
            if (config.killSwitch.shouldHaltSimulation(portfolio.totalEquity)) break

            queueRiskExits(event, portfolio, risk, pendingExits)
            runStrategyAndSubmit(event, config, clock, portfolio, universe, orderBook)
        }

        return RunResult(
            strategyName = strategy.name,
            fills = allFills,
            equityCurve = metrics.curve(),
            finalEquity = portfolio.totalEquity,
            tradesClosed = closedTrades,
        )
    }

    /**
     * Drains the order book, fills orders matching [event.asset] at this bar's open, and
     * re-submits orders for OTHER assets so they remain eligible when their own bars arrive.
     * Without the re-submit, entry orders for asset X would be silently lost whenever a bar
     * for asset Y arrived first.
     */
    private fun applyEntryFills(
        event: BarEvent,
        portfolio: Portfolio,
        orderBook: OrderBook,
        fillSim: FillSimulator,
        allFills: MutableList<Fill>,
    ) {
        val allPending = orderBook.drain()
        val (matching, other) = allPending.partition { it.asset == event.asset }
        if (other.isNotEmpty()) orderBook.submitAll(other)
        if (matching.isEmpty()) return

        val fills = fillSim.fill(
            orders = matching,
            openPrices = mapOf(event.asset to event.bar.open),
            equityBefore = portfolio.totalEquity,
            // TODO: compute realized vol from universe for VolTarget sizing. Currently
            // VolTarget silently falls back to its default weight — acceptable for v1
            // since no in-scope legacy strategy uses vol-targeted sizing.
            assetDailyVol = mapOf(event.asset to 0.0),
            barIndex = event.barIndex,
        )
        fills.forEach { fill ->
            portfolio.applyFill(fill)
            allFills.add(fill)
        }
    }

    /**
     * Fills pending exit orders for [event.asset] at this bar's open and records ClosedTrade
     * entries. Entry bar index is read from [com.trading.research.portfolio.Position.openedAtBarIndex]
     * so trade attribution survives multi-entry pyramiding and never defaults to 0.
     */
    private fun applyExitFills(
        event: BarEvent,
        portfolio: Portfolio,
        pendingExits: MutableList<OrderRequest>,
        fillSim: FillSimulator,
        allFills: MutableList<Fill>,
        closedTrades: MutableList<ClosedTrade>,
    ) {
        val exitsForThisAsset = pendingExits.filter { it.asset == event.asset }
        if (exitsForThisAsset.isEmpty()) return

        val exitFills = fillSim.fillExit(
            orders = exitsForThisAsset,
            openPrices = mapOf(event.asset to event.bar.open),
            portfolio = portfolio,
            barIndex = event.barIndex,
        )
        exitFills.forEach { fill ->
            val position = portfolio.positions[fill.asset]
                ?: error("exit fill without open position on ${fill.asset}")
            val pnl = (fill.fillPrice - position.avgEntryPrice) * fill.quantity - fill.fee
            closedTrades.add(
                ClosedTrade(
                    asset = fill.asset,
                    entryBarIndex = position.openedAtBarIndex,
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
        }
        pendingExits.removeAll { it.asset == event.asset }
    }

    /**
     * Notifies the KillSwitch of a new trading day so daily-DD accounting resets at the
     * correct equity baseline. Without this, [com.trading.research.risk.KillSwitch.shouldBlockEntries]
     * never triggers because currentDay stays null.
     */
    private fun rolloverKillSwitchIfNeeded(
        config: BacktestRunConfig,
        clock: ResearchClock,
        portfolio: Portfolio,
        lastDay: LocalDate?,
    ): LocalDate {
        val today = clock.currentDate()
        if (today != lastDay) {
            config.killSwitch.onDayStart(today, portfolio.totalEquity)
        }
        return today
    }

    private fun queueRiskExits(
        event: BarEvent,
        portfolio: Portfolio,
        risk: RiskManager,
        pendingExits: MutableList<OrderRequest>,
    ) {
        val position = portfolio.positions[event.asset] ?: return
        val exits = risk.evaluate(position, event.bar.close, event.barIndex)
        pendingExits.addAll(exits)
    }

    private suspend fun runStrategyAndSubmit(
        event: BarEvent,
        config: BacktestRunConfig,
        clock: ResearchClock,
        portfolio: Portfolio,
        universe: RollingUniverseView,
        orderBook: OrderBook,
    ) {
        val context = ResearchContextImpl(clock, portfolio.view(), universe, config.params)
        val signals = config.strategy.onBar(context, event)
        if (signals.isEmpty()) return
        if (config.killSwitch.shouldBlockEntries(clock.currentDate(), portfolio.totalEquity)) return
        orderBook.submitAll(signals)
    }
}
