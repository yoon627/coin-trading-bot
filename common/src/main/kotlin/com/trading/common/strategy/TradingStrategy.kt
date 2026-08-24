package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.NormalizedCandle

interface TradingStrategy {
    val name: String

    /**
     * 이 전략이 신호를 내려면 필요한 최소 캔들 수 — 진입·청산 중 **큰 쪽**이다.
     *
     * 기본값 21 은 기본 [shouldSell](5/20 데드크로스)이 요구하는 값이라, 이 값을 override 하지 않은
     * 전략은 지금까지와 같은 봉 수를 본다. 더 긴 lookback 을 쓰는 전략은 반드시 올려야 한다 —
     * 선언과 실제가 어긋나면 엔진이 짧은 캔들을 넘겨 전략이 조용히 false 를 반환한다
     * (StrategyMinCandlesTest 가 전 전략을 순회해 이 계약을 강제한다).
     */
    val minCandles: Int get() = 21

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
