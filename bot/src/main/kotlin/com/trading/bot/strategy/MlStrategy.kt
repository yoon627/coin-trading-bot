package com.trading.bot.strategy

import com.trading.bot.ml.MlModelService
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.Indicators
import com.trading.common.strategy.TradingStrategy
import org.springframework.stereotype.Component

@Component
class MlStrategy(private val mlModelService: MlModelService) : TradingStrategy {
    override val name = "ml_model"

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 50) return false

        val ticker = candles.firstOrNull()?.market ?: return false
        if (!mlModelService.hasModel(ticker)) return false

        val prediction = mlModelService.predict(ticker, candles) ?: return false

        // Only buy if model is confident (probability > 0.6)
        // + market filter: price above 50-day MA
        val ma50 = Indicators.calculateMa(candles, 50)
        val aboveMa50 = ma50 <= 0 || currentPrice > ma50

        return prediction.signal && prediction.probability > 0.6 && aboveMa50
    }
}
