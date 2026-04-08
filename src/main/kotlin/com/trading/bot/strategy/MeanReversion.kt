package com.trading.bot.strategy

import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.Candle
import org.springframework.stereotype.Component

@Component
class MeanReversion : TradingStrategy {
    override val name = "mean_reversion"

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 21) return false

        val ma20 = Indicators.calculateMa(candles, 20)
        if (ma20 <= 0) return false

        // Price deviation from MA20
        val deviation = (currentPrice - ma20) / ma20

        // Buy when price is significantly below MA (oversold deviation)
        val oversold = deviation < -0.03 // More than 3% below MA20

        // Bollinger band width check: volatility is not too extreme
        val bb = Indicators.calculateBollingerBands(candles, 20, 2.0) ?: return false
        val normalVolatility = bb.width < 0.15

        // RSI showing recovery from oversold
        val rsi = Indicators.calculateRsi(candles, 14)
        val rsiRecovering = rsi in 25.0..40.0

        // Volume confirmation: current volume > average
        val avgVolume = candles.take(10).map { it.candleAccTradeVolume }.average()
        val currentVolume = candles[0].candleAccTradeVolume
        val volumeOk = avgVolume <= 0 || currentVolume >= avgVolume * 0.8

        return oversold && normalVolatility && rsiRecovering && volumeOk
    }
}
