package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
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

    /**
     * 차트/지표 기반 청산 판단. 기본 구현은 공통 데드크로스(5/20 MA 하향 교차).
     * default 는 candles 만 사용 — currentPrice 는 가격 기반 청산을 구현하는 override 를 위한 파라미터다.
     * 전략별로 진입 신호와 대칭인 청산을 원하면 이 메서드를 override 한다.
     */
    suspend fun shouldSell(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean = Indicators.checkDeadCross(candles, 5, 20)

    /**
     * NormalizedCandle 기반 매도 판단. 기본 구현은 Candle 로 변환 후 [shouldSell] 위임.
     */
    suspend fun shouldSellNormalized(
        candles: List<NormalizedCandle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        val legacyCandles = candles.map { it.toLegacyCandle() }
        return shouldSell(legacyCandles, currentPrice, config)
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
