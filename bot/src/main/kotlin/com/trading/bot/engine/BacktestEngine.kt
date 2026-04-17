package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.Indicators
import com.trading.common.strategy.TradingStrategy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BacktestConfig(
    val investRatio: Double = 1.0,
    val takeProfitPct: Double = 5.0,
    val maxLossPct: Double = 3.0,
    val kValue: Double = 0.5,
    val feeRate: Double = 0.0005,
    val trailingStopPct: Double = 2.0,
    val maxHoldDays: Int = 7,
    val useMarketFilter: Boolean = true,
)

data class BacktestTrade(
    val buyIndex: Int,
    val sellIndex: Int,
    val buyPrice: Double,
    val sellPrice: Double,
    val pnlPercent: Double,
    val holdDays: Int,
    val reason: String,
)

data class BacktestResult(
    val strategyName: String,
    val ticker: String,
    val totalTrades: Int,
    val winTrades: Int,
    val lossTrades: Int,
    val winRate: Double,
    val totalReturnPct: Double,
    val avgReturnPct: Double,
    val maxDrawdownPct: Double,
    val sharpeRatio: Double,
    val profitFactor: Double,
    val buyAndHoldPct: Double,
    val avgHoldDays: Double,
    val trades: List<BacktestTrade>,
)

class BacktestEngine(
    private val strategies: List<TradingStrategy>,
    private val tradingProperties: TradingProperties,
) {
    companion object {
        private const val MIN_CANDLES = 50
        private const val INITIAL_BALANCE = 1_000_000.0
        private const val MAX_PROFIT_FACTOR = 999.0
    }

    suspend fun run(
        strategyName: String,
        candles: List<Candle>,
        ticker: String,
        config: BacktestConfig = BacktestConfig(),
    ): BacktestResult? {
        val strategy = strategies.find { it.name == strategyName } ?: return null
        val chronological = candles.reversed()
        if (chronological.size < MIN_CANDLES) return null

        val simulation = simulateTrades(strategy, chronological, config)
        return buildResult(strategyName, ticker, chronological, simulation, config)
    }

    private suspend fun simulateTrades(
        strategy: TradingStrategy,
        chronological: List<Candle>,
        config: BacktestConfig,
    ): SimulationState {
        val state = SimulationState()

        for (i in MIN_CANDLES until chronological.size) {
            val currentPrice = chronological[i].tradePrice
            val window = chronological.subList(max(0, i - (MIN_CANDLES - 1)), i + 1).reversed()

            if (state.position) {
                processExit(state, i, currentPrice, config)
            } else {
                processEntry(state, strategy, i, currentPrice, window, config)
            }
        }

        closeOpenPosition(state, chronological, config)
        return state
    }

    private fun processExit(state: SimulationState, index: Int, currentPrice: Double, config: BacktestConfig) {
        state.peakPrice = max(state.peakPrice, currentPrice)
        val pnl = ((currentPrice - state.buyPrice) / state.buyPrice) * 100.0
        val dropFromPeak = ((state.peakPrice - currentPrice) / state.peakPrice) * 100.0
        val holdDays = index - state.buyIndex

        val reason = when {
            pnl <= -config.maxLossPct -> "STOP_LOSS"
            dropFromPeak >= config.trailingStopPct && pnl > 0 -> "TRAILING_STOP"
            pnl >= config.takeProfitPct -> "TAKE_PROFIT"
            holdDays >= config.maxHoldDays -> "TIME_EXIT"
            else -> null
        } ?: return

        val netPnl = pnl - (config.feeRate * 2 * 100)
        state.balance *= (1 + netPnl / 100.0)
        state.trades.add(BacktestTrade(state.buyIndex, index, state.buyPrice, currentPrice, netPnl, holdDays, reason))
        state.returns.add(netPnl)
        state.peakBalance = max(state.peakBalance, state.balance)
        state.maxDrawdown = max(state.maxDrawdown, (state.peakBalance - state.balance) / state.peakBalance * 100)
        state.position = false
    }

    private suspend fun processEntry(
        state: SimulationState,
        strategy: TradingStrategy,
        index: Int,
        currentPrice: Double,
        window: List<Candle>,
        config: BacktestConfig,
    ) {
        if (config.useMarketFilter) {
            val ma50 = Indicators.calculateMa(window, min(MIN_CANDLES, window.size))
            if (ma50 > 0 && currentPrice < ma50) return
        }

        if (strategy.shouldBuy(window, currentPrice, tradingProperties)) {
            state.buyPrice = currentPrice
            state.peakPrice = currentPrice
            state.buyIndex = index
            state.position = true
        }
    }

    private fun closeOpenPosition(state: SimulationState, chronological: List<Candle>, config: BacktestConfig) {
        if (!state.position) return
        val lastPrice = chronological.last().tradePrice
        val pnl = ((lastPrice - state.buyPrice) / state.buyPrice) * 100.0 - (config.feeRate * 2 * 100)
        state.balance *= (1 + pnl / 100.0)
        state.trades.add(BacktestTrade(state.buyIndex, chronological.size - 1, state.buyPrice, lastPrice, pnl, chronological.size - 1 - state.buyIndex, "END"))
        state.returns.add(pnl)
    }

    private fun buildResult(
        strategyName: String,
        ticker: String,
        chronological: List<Candle>,
        state: SimulationState,
        config: BacktestConfig,
    ): BacktestResult {
        val firstPrice = chronological[MIN_CANDLES].tradePrice
        val lastPrice = chronological.last().tradePrice
        val buyAndHold = ((lastPrice - firstPrice) / firstPrice) * 100.0

        val winTrades = state.trades.count { it.pnlPercent > 0 }
        val lossTrades = state.trades.count { it.pnlPercent <= 0 }
        val totalReturn = ((state.balance - INITIAL_BALANCE) / INITIAL_BALANCE) * 100.0

        val grossProfit = state.returns.filter { it > 0 }.sum()
        val grossLoss = state.returns.filter { it < 0 }.map { -it }.sum()
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) MAX_PROFIT_FACTOR else 0.0

        val avgReturn = if (state.returns.isNotEmpty()) state.returns.average() else 0.0
        val stdDev = if (state.returns.size > 1) {
            val mean = state.returns.average()
            sqrt(state.returns.map { (it - mean) * (it - mean) }.average())
        } else 0.0
        val sharpe = if (stdDev > 0) avgReturn / stdDev else 0.0
        val avgHold = if (state.trades.isNotEmpty()) state.trades.map { it.holdDays }.average() else 0.0

        return BacktestResult(
            strategyName = strategyName,
            ticker = ticker,
            totalTrades = state.trades.size,
            winTrades = winTrades,
            lossTrades = lossTrades,
            winRate = if (state.trades.isNotEmpty()) winTrades.toDouble() / state.trades.size * 100.0 else 0.0,
            totalReturnPct = totalReturn,
            avgReturnPct = avgReturn,
            maxDrawdownPct = state.maxDrawdown,
            sharpeRatio = sharpe,
            profitFactor = min(profitFactor, MAX_PROFIT_FACTOR),
            buyAndHoldPct = buyAndHold,
            avgHoldDays = avgHold,
            trades = state.trades,
        )
    }

    suspend fun compareAll(
        candles: List<Candle>,
        ticker: String,
        config: BacktestConfig = BacktestConfig(),
    ): List<BacktestResult> {
        return strategies.mapNotNull { strategy ->
            run(strategy.name, candles, ticker, config)
        }
    }

    private class SimulationState(
        var balance: Double = INITIAL_BALANCE,
        var peakBalance: Double = INITIAL_BALANCE,
        var maxDrawdown: Double = 0.0,
        var position: Boolean = false,
        var buyPrice: Double = 0.0,
        var peakPrice: Double = 0.0,
        var buyIndex: Int = 0,
        val trades: MutableList<BacktestTrade> = mutableListOf(),
        val returns: MutableList<Double> = mutableListOf(),
    )
}
