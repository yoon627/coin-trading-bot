package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.ExitGates
import com.trading.common.strategy.Indicators
import com.trading.common.strategy.TradingStrategy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// 디폴트는 라이브(TradingProperties)와 정합 — 직접 생성(스윕 등) 베이스라인이 라이브를 대표하도록 (#27).
// parity 는 BacktestEngineTest 의 `config defaults match live trading defaults` 가 가드한다.
data class BacktestConfig(
    val investRatio: Double = 1.0,
    val takeProfitPct: Double = 2.0,
    val maxLossPct: Double = 5.0,
    val kValue: Double = 0.5,
    val feeRate: Double = 0.0005,
    val trailingStopPct: Double = 2.0,
    val trailingArmPct: Double = 0.0,
    val maxHoldDays: Int = 1,
    val useMarketFilter: Boolean = false,
    val chartExitEnabled: Boolean = false,
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
            // 신호는 봉 i 종가까지의 정보로만 판단(look-ahead 방지). 매수/매도(chartExit) 공용 window.
            val window = chronological.subList(max(0, i - (MIN_CANDLES - 1)), i + 1).reversed()

            if (state.position) {
                processExit(state, strategy, i, currentPrice, window, config)
            } else {
                // 체결은 다음 봉(i+1) 시가로.
                val fillIndex = i + 1
                if (fillIndex >= chronological.size) continue
                val fillPrice = chronological[fillIndex].openingPrice
                processEntry(state, strategy, fillIndex, currentPrice, fillPrice, window, config)
            }
        }

        closeOpenPosition(state, chronological, config)
        return state
    }

    private suspend fun processExit(
        state: SimulationState,
        strategy: TradingStrategy,
        index: Int,
        currentPrice: Double,
        window: List<Candle>,
        config: BacktestConfig,
    ) {
        state.peakPrice = max(state.peakPrice, currentPrice)
        val pnl = ((currentPrice - state.buyPrice) / state.buyPrice) * 100.0
        val peakPnl = ((state.peakPrice - state.buyPrice) / state.buyPrice) * 100.0
        val dropFromPeak = ((state.peakPrice - currentPrice) / state.peakPrice) * 100.0
        val holdDays = index - state.buyIndex

        // 실거래(TradingEngine.decideSell)와 동일 우선순위: 손익% 안전망 > chartExit > 시간청산.
        // 트레일링은 라이브와 같은 ExitGates 조건식, maxHoldDays coerce 도 라이브(DailyResetManager)와 대칭.
        val reason = when {
            pnl <= -config.maxLossPct -> "STOP_LOSS"
            ExitGates.isTrailingStopTriggered(pnl, peakPnl, dropFromPeak, config.trailingStopPct, config.trailingArmPct) -> "TRAILING_STOP"
            pnl >= config.takeProfitPct -> "TAKE_PROFIT"
            config.chartExitEnabled && strategy.shouldSell(window, currentPrice, tradingProperties) -> "CHART_EXIT"
            holdDays >= config.maxHoldDays.coerceAtLeast(1) -> "TIME_EXIT"
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
        fillIndex: Int,
        signalPrice: Double,
        fillPrice: Double,
        window: List<Candle>,
        config: BacktestConfig,
    ) {
        if (fillPrice <= 0) return
        if (config.useMarketFilter) {
            val ma50 = Indicators.calculateMa(window, min(MIN_CANDLES, window.size))
            if (ma50 > 0 && signalPrice < ma50) return
        }

        // 신호는 봉 i 종가(signalPrice)로 판단, 체결가는 다음 봉 시가(fillPrice).
        if (strategy.shouldBuy(window, signalPrice, tradingProperties)) {
            state.buyPrice = fillPrice
            state.peakPrice = fillPrice
            state.buyIndex = fillIndex
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
