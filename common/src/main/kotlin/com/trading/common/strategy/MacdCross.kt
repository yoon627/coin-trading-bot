package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle

class MacdCross : TradingStrategy {
    override val name = "macd_cross"

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 36) return false

        val current = Indicators.calculateMacd(candles, 12, 26, 9) ?: return false
        val prev = Indicators.calculateMacd(candles.drop(1), 12, 26, 9) ?: return false

        // MACD line crosses above signal line (bullish crossover)
        val bullishCross = prev.macd <= prev.signal && current.macd > current.signal

        // Histogram is positive and growing
        val histogramPositive = current.histogram > 0

        // RSI not overbought
        val rsi = Indicators.calculateRsi(candles, 14)
        val rsiOk = rsi < 70.0

        return bullishCross && histogramPositive && rsiOk
    }

    // 진입(상향 교차)의 거울 청산: MACD 가 시그널선을 하향 교차(bearish crossover). candles 기반(currentPrice 미사용).
    override suspend fun shouldSell(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 36) return false

        val current = Indicators.calculateMacd(candles, 12, 26, 9) ?: return false
        val prev = Indicators.calculateMacd(candles.drop(1), 12, 26, 9) ?: return false

        // MACD line crosses below signal line (bearish crossover)
        return prev.macd >= prev.signal && current.macd < current.signal
    }
}
