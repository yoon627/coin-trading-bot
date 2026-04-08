package com.trading.bot.engine

import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.Candle
import com.trading.bot.strategy.Indicators
import com.trading.bot.strategy.TradingStrategy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BacktestConfig(
    val investRatio: Double = 1.0,
    val takeProfitPct: Double = 5.0,
    val maxLossPct: Double = 3.0,
    val kValue: Double = 0.5,
    val feeRate: Double = 0.0005,
    val trailingStopPct: Double = 2.0,   // trailing stop: 고점 대비 N% 하락 시 매도
    val maxHoldDays: Int = 7,            // 최대 보유일
    val useMarketFilter: Boolean = true, // 하락장 필터
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
    suspend fun run(
        strategyName: String,
        candles: List<Candle>,
        ticker: String,
        config: BacktestConfig = BacktestConfig(),
    ): BacktestResult? {
        val strategy = strategies.find { it.name == strategyName } ?: return null
        val chronological = candles.reversed()
        if (chronological.size < 50) return null

        val trades = mutableListOf<BacktestTrade>()
        var balance = 1_000_000.0
        var peakBalance = balance
        var maxDrawdown = 0.0
        var position = false
        var buyPrice = 0.0
        var peakPrice = 0.0
        var buyIndex = 0
        val returns = mutableListOf<Double>()

        for (i in 50 until chronological.size) {
            val currentPrice = chronological[i].tradePrice
            val window = chronological.subList(max(0, i - 49), i + 1).reversed()

            if (position) {
                peakPrice = max(peakPrice, currentPrice)
                val pnl = ((currentPrice - buyPrice) / buyPrice) * 100.0
                val dropFromPeak = ((peakPrice - currentPrice) / peakPrice) * 100.0
                val holdDays = i - buyIndex

                val reason = when {
                    pnl <= -config.maxLossPct -> "STOP_LOSS"
                    dropFromPeak >= config.trailingStopPct && pnl > 0 -> "TRAILING_STOP"
                    pnl >= config.takeProfitPct -> "TAKE_PROFIT"
                    holdDays >= config.maxHoldDays -> "TIME_EXIT"
                    else -> null
                }

                if (reason != null) {
                    val netPnl = pnl - (config.feeRate * 2 * 100)
                    balance *= (1 + netPnl / 100.0)
                    trades.add(BacktestTrade(buyIndex, i, buyPrice, currentPrice, netPnl, holdDays, reason))
                    returns.add(netPnl)
                    peakBalance = max(peakBalance, balance)
                    maxDrawdown = max(maxDrawdown, (peakBalance - balance) / peakBalance * 100)
                    position = false
                }
            } else {
                // Market regime filter: only buy if price > 50-day MA (uptrend)
                if (config.useMarketFilter) {
                    val ma50 = Indicators.calculateMa(window, min(50, window.size))
                    if (ma50 > 0 && currentPrice < ma50) continue
                }

                val shouldBuy = strategy.shouldBuy(window, currentPrice, tradingProperties)
                if (shouldBuy) {
                    buyPrice = currentPrice
                    peakPrice = currentPrice
                    buyIndex = i
                    position = true
                }
            }
        }

        // Close open position
        if (position) {
            val lastPrice = chronological.last().tradePrice
            val pnl = ((lastPrice - buyPrice) / buyPrice) * 100.0 - (config.feeRate * 2 * 100)
            balance *= (1 + pnl / 100.0)
            trades.add(BacktestTrade(buyIndex, chronological.size - 1, buyPrice, lastPrice, pnl, chronological.size - 1 - buyIndex, "END"))
            returns.add(pnl)
        }

        // Buy & Hold baseline
        val firstPrice = chronological[50].tradePrice
        val lastPrice = chronological.last().tradePrice
        val buyAndHold = ((lastPrice - firstPrice) / firstPrice) * 100.0

        val winTrades = trades.count { it.pnlPercent > 0 }
        val lossTrades = trades.count { it.pnlPercent <= 0 }
        val totalReturn = ((balance - 1_000_000.0) / 1_000_000.0) * 100.0

        val grossProfit = returns.filter { it > 0 }.sum()
        val grossLoss = returns.filter { it < 0 }.map { -it }.sum()
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) 999.0 else 0.0

        val avgReturn = if (returns.isNotEmpty()) returns.average() else 0.0
        val stdDev = if (returns.size > 1) {
            val mean = returns.average()
            sqrt(returns.map { (it - mean) * (it - mean) }.average())
        } else 0.0
        val sharpe = if (stdDev > 0) avgReturn / stdDev else 0.0
        val avgHold = if (trades.isNotEmpty()) trades.map { it.holdDays }.average() else 0.0

        return BacktestResult(
            strategyName = strategyName,
            ticker = ticker,
            totalTrades = trades.size,
            winTrades = winTrades,
            lossTrades = lossTrades,
            winRate = if (trades.isNotEmpty()) winTrades.toDouble() / trades.size * 100.0 else 0.0,
            totalReturnPct = totalReturn,
            avgReturnPct = avgReturn,
            maxDrawdownPct = maxDrawdown,
            sharpeRatio = sharpe,
            profitFactor = min(profitFactor, 999.0),
            buyAndHoldPct = buyAndHold,
            avgHoldDays = avgHold,
            trades = trades,
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
}
