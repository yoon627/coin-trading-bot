package com.trading.common.strategy

import com.trading.common.domain.Ohlc

/**
 * 어깨 청산 — 꼭지를 맞히는 대신 상승이 꺾인 것을 확인하고 나온다.
 *
 * 이 판정을 쓰는 전략은 기본 청산(5/20 데드크로스)을 대체한다. 데드크로스를 OR 로 덧붙이지 않는 이유는
 * 조기 이탈이 목적인데 늦은 신호가 섞이면 그 의도가 사라지기 때문이다. 과열도 밴드 이탈도 없이 끝나는
 * 구간은 트레일링과 maxHoldDays 가 맡는다.
 *
 * 두 판정 모두 직전 봉과 비교하므로 지표를 현재/직전 두 epoch 로 각각 계산한다. 현재 밴드에 직전 종가를
 * 대보면 밴드가 확장·축소하는 봉에서 결과가 뒤집힌다.
 */
object ShoulderExit {

    private const val RSI_PERIOD = 14
    private const val OVERHEATED = 70.0
    private const val BB_PERIOD = 20
    private const val BB_MULTIPLIER = 2.0

    // RSI 가 볼 봉 수. 리스트 전체를 쓰면 백테(50봉)와 라이브(21~60봉)의 값이 갈린다.
    private const val RSI_WINDOW = 40

    // 직전 epoch 도 RSI_WINDOW 만큼 확보해야 두 판정이 같은 길이를 본다 — 40봉이면 drop(1) 이 39봉이라
    // 어긋난다. 이 상향으로 warm-up 21~40봉 구간에서는 차트 청산이 평가되지 않지만, 그 구간엔 무릎 진입
    // 자체가 없다(진입이 40/41봉을 요구).
    private const val MIN_CANDLES = RSI_WINDOW + 1

    fun isTriggered(candles: List<Ohlc>): Boolean {
        if (candles.size < MIN_CANDLES) return false
        val previous = candles.drop(1)

        val rsiPrev = Indicators.calculateRsi(previous.take(RSI_WINDOW), RSI_PERIOD)
        val rsiNow = Indicators.calculateRsi(candles.take(RSI_WINDOW), RSI_PERIOD)
        if (rsiPrev >= OVERHEATED && rsiNow < OVERHEATED) return true

        // 밴드가 가격보다 빨리 확장되면 상승 중인 봉도 "상단 안으로 복귀"로 보인다. 하락 봉만 인정해야
        // 가속 구간에서 조기 이탈하지 않는다 — BollingerBounce 가 하단에서 falling knife 를 거르는 것의 거울상.
        if (candles[0].close >= candles[1].close) return false

        val band = Indicators.calculateBollingerBands(candles, BB_PERIOD, BB_MULTIPLIER) ?: return false
        val prevBand = Indicators.calculateBollingerBands(previous, BB_PERIOD, BB_MULTIPLIER) ?: return false
        return candles[1].close > prevBand.upper && candles[0].close <= band.upper
    }
}
