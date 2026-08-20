package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle

/**
 * 무릎 매수(추세 전환형) — 하락이 끝나고 반등이 확인된 지점에서 진입한다.
 *
 * 바닥을 맞히려 하지 않고(떨어지는 칼), 이미 오른 것을 쫓지도 않는다(허리 위). 저점 대비 위치가
 * 이 전략의 핵심이고 나머지 조건은 그 위치가 우연이 아님을 확인하는 장치다.
 */
class KneeReversal : TradingStrategy {
    override val name = "knee_reversal"

    private companion object {
        // 라이브 store 는 최대 60봉, 백테 window 는 정확히 50봉을 넘긴다 — 둘 다에서 유효한 상한.
        const val PEAK_WINDOW = 40

        // 청산(ShoulderExit)이 직전 epoch 를 만들려고 한 봉을 더 쓰므로 진입도 같은 최소치를 요구한다.
        // 40봉에서 진입만 되면 그 포지션의 차트 청산이 하루 동안 평가되지 못하는 비대칭이 생긴다.
        const val MIN_CANDLES = PEAK_WINDOW + 1
        const val TROUGH_WINDOW = 20
        const val MIN_DECLINE = 0.15
        val KNEE_RANGE = 0.03..0.12
        val REBOUND_RSI = 35.0..55.0
        const val RSI_PERIOD = 14
        const val VOLUME_WINDOW = 10
    }

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < MIN_CANDLES) return false

        val peak = Indicators.highestHigh(candles, PEAK_WINDOW)
        val trough = Indicators.lowestLow(candles, TROUGH_WINDOW)
        // isFinite 까지 보는 이유: NaN 이 섞이면 아래 비교가 전부 false 라 하락·무릎 조건이 조용히
        // 건너뛰어진다. 매수 게이트는 fail-open 이 아니라 fail-safe 여야 한다.
        if (!peak.isFinite() || !trough.isFinite() || peak <= 0 || trough <= 0) return false

        // 고점과 저점을 직접 비교해 아래 무릎 판정과 겹치지 않게 한다.
        if ((peak - trough) / peak < MIN_DECLINE) return false

        // 아래면 아직 떨어지는 중, 위면 이미 허리.
        if ((currentPrice - trough) / trough !in KNEE_RANGE) return false

        if (currentPrice <= candles[1].tradePrice) return false
        // RSI 는 리스트 전체로 Wilder smoothing 을 돌아 window 길이가 값에 들어간다. 다른 지표와 같은
        // 40봉으로 잘라야 백테(50봉)와 라이브(21~60봉)가 같은 판정을 낸다.
        if (Indicators.calculateRsi(candles.take(PEAK_WINDOW), RSI_PERIOD) !in REBOUND_RSI) return false

        val avgVolume = candles.take(VOLUME_WINDOW).map { it.candleAccTradeVolume }.average()
        return avgVolume <= 0 || candles[0].candleAccTradeVolume >= avgVolume
    }

    // 어깨 청산 — candles 종가 기반(currentPrice 미사용). 판정식은 ShoulderExit 참조.
    override suspend fun shouldSell(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean = ShoulderExit.isTriggered(candles)
}
