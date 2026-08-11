package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle

/**
 * 무릎 매수(눌림목형) — 이미 상승 추세인 종목이 20일선까지 눌렸다가 돌아설 때 진입한다.
 *
 * [KneeReversal] 과 같은 "무릎"이지만 국면이 다르다. 이쪽은 추세를 거스르지 않고 조정만 이용한다.
 */
class KneePullback : TradingStrategy {
    override val name = "knee_pullback"

    private companion object {
        const val TREND_SHORT = 20
        const val TREND_LONG = 40

        // calculateMa 는 봉이 모자라면 예외 대신 0.0 을 주므로 장기선 주기보다 한 봉 더 요구한다.
        const val MIN_CANDLES = TREND_LONG + 1

        const val DIP_FLOOR = 0.97
        const val DIP_CEIL = 1.02
        val HEALTHY_RSI = 40.0..60.0
        const val RSI_PERIOD = 14
    }

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < MIN_CANDLES) return false

        val shortMa = Indicators.calculateMa(candles, TREND_SHORT)
        val longMa = Indicators.calculateMa(candles, TREND_LONG)
        if (!shortMa.isFinite() || !longMa.isFinite() || shortMa <= 0 || longMa <= 0) return false

        if (shortMa <= longMa) return false

        // 아직 20일선 위에 떠 있으면 조정을 기다린 게 아니다.
        if (currentPrice !in shortMa * DIP_FLOOR..shortMa * DIP_CEIL) return false

        if (currentPrice <= candles[1].tradePrice) return false
        if (currentPrice <= candles[0].openingPrice) return false

        return Indicators.calculateRsi(candles, RSI_PERIOD) in HEALTHY_RSI
    }

    // 어깨 청산 — candles 종가 기반(currentPrice 미사용). 판정식은 ShoulderExit 참조.
    override suspend fun shouldSell(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean = ShoulderExit.isTriggered(candles)
}
