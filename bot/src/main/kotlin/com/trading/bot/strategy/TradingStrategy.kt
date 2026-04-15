package com.trading.bot.strategy

import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.Candle
import com.trading.common.domain.NormalizedCandle

interface TradingStrategy {
    val name: String

    suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean

    /**
     * NormalizedCandle 기반 매수 판단. 기본 구현은 Candle로 변환 후 위임.
     */
    suspend fun shouldBuyNormalized(
        candles: List<NormalizedCandle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        val legacyCandles = candles.map { it.toLegacyCandle() }
        return shouldBuy(legacyCandles, currentPrice, config)
    }
}

fun NormalizedCandle.toLegacyCandle(): Candle = Candle(
    market = this.market,
    openingPrice = this.openPrice,
    highPrice = this.highPrice,
    lowPrice = this.lowPrice,
    tradePrice = this.closePrice,
    candleAccTradeVolume = this.volume,
    candleAccTradePrice = this.quoteVolume,
)
