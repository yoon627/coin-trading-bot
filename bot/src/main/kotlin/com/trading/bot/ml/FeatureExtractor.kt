package com.trading.bot.ml

import com.trading.common.domain.Candle
import com.trading.common.strategy.Indicators
import kotlin.math.ln
import kotlin.math.max

object FeatureExtractor {

    val FEATURE_NAMES = listOf(
        "rsi_14", "rsi_7",
        "macd_hist", "macd_signal_dist",
        "bb_position", "bb_width",
        "ma5_dist", "ma20_dist", "ma50_dist",
        "ma5_slope", "ma20_slope",
        "volume_ratio_5", "volume_ratio_20",
        "price_change_1d", "price_change_3d", "price_change_7d",
        "high_low_range", "candle_body_ratio",
        "volatility_10d",
        "log_return_1d",
    )

    /**
     * Extract feature vector from a candle window (newest-first).
     * Returns null if insufficient data.
     */
    fun extract(candles: List<Candle>): DoubleArray? {
        if (candles.size < 50) return null

        val current = candles[0]
        val price = current.tradePrice
        if (price <= 0) return null

        // RSI
        val rsi14 = Indicators.calculateRsi(candles, 14) / 100.0
        val rsi7 = Indicators.calculateRsi(candles, 7) / 100.0

        // MACD
        val macd = Indicators.calculateMacd(candles, 12, 26, 9)
        val macdHist = if (macd != null) macd.histogram / price * 100 else 0.0
        val macdSignalDist = if (macd != null) (macd.macd - macd.signal) / price * 100 else 0.0

        // Bollinger Bands
        val bb = Indicators.calculateBollingerBands(candles, 20, 2.0)
        val bbPosition = if (bb != null && bb.upper > bb.lower) {
            (price - bb.lower) / (bb.upper - bb.lower)
        } else 0.5
        val bbWidth = bb?.width ?: 0.0

        // MA distances (normalized)
        val ma5 = Indicators.calculateMa(candles, 5)
        val ma20 = Indicators.calculateMa(candles, 20)
        val ma50 = Indicators.calculateMa(candles, 50)
        val ma5Dist = if (ma5 > 0) (price - ma5) / ma5 else 0.0
        val ma20Dist = if (ma20 > 0) (price - ma20) / ma20 else 0.0
        val ma50Dist = if (ma50 > 0) (price - ma50) / ma50 else 0.0

        // MA slopes (current vs 3 days ago)
        val prevMa5 = Indicators.calculateMa(candles.drop(3), 5)
        val prevMa20 = Indicators.calculateMa(candles.drop(3), 20)
        val ma5Slope = if (prevMa5 > 0) (ma5 - prevMa5) / prevMa5 else 0.0
        val ma20Slope = if (prevMa20 > 0) (ma20 - prevMa20) / prevMa20 else 0.0

        // Volume ratios
        val vol5 = candles.take(5).map { it.candleAccTradeVolume }.average()
        val vol20 = candles.take(20).map { it.candleAccTradeVolume }.average()
        val currentVol = current.candleAccTradeVolume
        val volRatio5 = if (vol5 > 0) currentVol / vol5 else 1.0
        val volRatio20 = if (vol20 > 0) currentVol / vol20 else 1.0

        // Price changes
        val priceChange1d = if (candles.size > 1) (price - candles[1].tradePrice) / candles[1].tradePrice else 0.0
        val priceChange3d = if (candles.size > 3) (price - candles[3].tradePrice) / candles[3].tradePrice else 0.0
        val priceChange7d = if (candles.size > 7) (price - candles[7].tradePrice) / candles[7].tradePrice else 0.0

        // Candle patterns
        val highLowRange = if (current.highPrice > 0) (current.highPrice - current.lowPrice) / current.highPrice else 0.0
        val body = current.tradePrice - current.openingPrice
        val range = current.highPrice - current.lowPrice
        val bodyRatio = if (range > 0) body / range else 0.0

        // Volatility (10-day std of returns)
        val volatility = if (candles.size > 10) {
            val returns = (0 until 10).map {
                val c = candles[it].tradePrice
                val p = candles[it + 1].tradePrice
                if (p > 0) (c - p) / p else 0.0
            }
            val mean = returns.average()
            kotlin.math.sqrt(returns.map { (it - mean) * (it - mean) }.average())
        } else 0.0

        // Log return
        val logReturn = if (candles.size > 1 && candles[1].tradePrice > 0) {
            ln(price / candles[1].tradePrice)
        } else 0.0

        return doubleArrayOf(
            rsi14, rsi7,
            macdHist, macdSignalDist,
            bbPosition, bbWidth,
            ma5Dist, ma20Dist, ma50Dist,
            ma5Slope, ma20Slope,
            volRatio5.coerceIn(0.0, 5.0), volRatio20.coerceIn(0.0, 5.0),
            priceChange1d, priceChange3d, priceChange7d,
            highLowRange, bodyRatio,
            volatility,
            logReturn,
        )
    }

    /**
     * Create labeled dataset from chronological candles.
     * Label: 1 if price goes up by [targetPct]% within [horizon] days, else 0.
     */
    fun createDataset(
        candles: List<Candle>, // newest-first
        targetPct: Double = 2.0,
        horizon: Int = 5,
    ): Pair<Array<DoubleArray>, IntArray>? {
        val chronological = candles.reversed()
        if (chronological.size < 60) return null

        val features = mutableListOf<DoubleArray>()
        val labels = mutableListOf<Int>()

        for (i in 50 until chronological.size - horizon) {
            val window = chronological.subList(max(0, i - 49), i + 1).reversed()
            val feature = extract(window) ?: continue

            // Label: max price in next [horizon] days vs current
            val currentPrice = chronological[i].tradePrice
            val futureMax = (i + 1..minOf(i + horizon, chronological.size - 1))
                .maxOfOrNull { chronological[it].highPrice } ?: currentPrice
            val maxReturn = (futureMax - currentPrice) / currentPrice * 100

            labels.add(if (maxReturn >= targetPct) 1 else 0)
            features.add(feature)
        }

        if (features.size < 20) return null
        return features.toTypedArray() to labels.toIntArray()
    }
}
