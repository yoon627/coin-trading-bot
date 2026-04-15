package com.trading.bot.strategy

import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.Candle

interface TradingStrategy {
    val name: String
    suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean
}
