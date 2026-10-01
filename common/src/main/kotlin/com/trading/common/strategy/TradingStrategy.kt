package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.NormalizedCandle

interface TradingStrategy {
    val name: String

    /**
     * 이 전략이 매수 신호를 내려면 필요한 최소 캔들 수. 기본값이 없다 — 구현체가 자기 lookback 으로 선언한다.
     * 선언과 실제가 어긋나면 엔진이 짧은 캔들을 넘겨 전략이 조용히 false 를 반환한다
     * (StrategyMinCandlesTest 가 전 전략을 순회해 이 계약을 강제한다).
     */
    val minCandles: Int

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
