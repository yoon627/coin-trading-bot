package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.NormalizedCandle

interface TradingStrategy {
    val name: String

    /** 매수 신호에 필요한 최소 캔들 수. 실제 lookback 보다 작으면 전략이 조용히 false 를 반환한다. */
    val minCandles: Int

    suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean

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
