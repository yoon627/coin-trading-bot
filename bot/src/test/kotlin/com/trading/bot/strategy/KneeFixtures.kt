package com.trading.bot.strategy

import com.trading.common.domain.Candle

/**
 * knee_reversal / knee_pullback 용 합성 캔들.
 *
 * 반환은 항상 index 0 = 최신(엔진·Indicators 관례). 기본 파라미터는 각 전략의 전 조건을 만족하되
 * 임계에서 충분히 떨어진 값으로 잡아, 지표 구현이 조금 달라져도 경계에서 흔들리지 않게 했다.
 * O/H/L/C 를 모두 채우는 이유: Candle 의 O/H/L 기본값은 0.0 이라, 비워두면 lowestLow/highestHigh 가
 * 0 이 되고 분모 0 → NaN 이 되어 조건이 조용히 false 가 된다.
 */
internal object KneeFixtures {

    fun candle(open: Double, high: Double, low: Double, close: Double, volume: Double = 100.0) = Candle(
        market = "KRW-BTC",
        openingPrice = open,
        highPrice = high,
        lowPrice = low,
        tradePrice = close,
        candleAccTradeVolume = volume,
    )

    /**
     * 고가권 횡보 → 하락 → 반등. 기본값(50봉)은 H40 대비 L20 낙폭 20%, 저점 대비 반등 7.5%, RSI 43.
     */
    fun reversal(
        flatBars: Int = 25,
        dropBars: Int = 18,
        reboundBars: Int = 7,
        top: Double = 10_000.0,
        bottom: Double = 8_200.0,
        reboundPct: Double = 0.07,
        lastVolume: Double = 160.0,
        lastCloseFactor: Double = 1.0,
        troughWickFactor: Double = 1.0,
    ): List<Candle> {
        val chrono = mutableListOf<Candle>()
        repeat(flatBars) { chrono += candle(top, top * 1.02, top * 0.99, top) }

        val step = (top - bottom) / dropBars
        for (i in 1..dropBars) {
            val c = top - step * i
            // 마지막 하락 봉의 저가만 눌러 20봉 저점을 낮춘다. 종가 계열이 그대로라 RSI 는 변하지 않아
            // "저점 대비 위치(무릎)" 조건만 단독으로 깨뜨릴 수 있다.
            val low = if (i == dropBars) c * troughWickFactor else c - step * 0.4
            chrono += candle(c + step * 0.5, c + step * 0.6, low, c)
        }

        val rStep = (bottom * (1 + reboundPct) - bottom) / reboundBars
        for (i in 1..reboundBars) {
            val last = i == reboundBars
            val c = (bottom + rStep * i) * if (last) lastCloseFactor else 1.0
            chrono += candle(
                open = c - rStep * 0.6,
                high = c + rStep * 0.3,
                low = c - rStep * 0.8,
                close = c,
                volume = if (last) lastVolume else 100.0,
            )
        }
        return chrono.reversed()
    }

    /**
     * 완만한 상승 추세 → 조정 → 반등 양봉. 기본값(45봉)은 ma20 > ma40, price/ma20 = 0.983, RSI 50.
     */
    fun pullback(
        bars: Int = 45,
        start: Double = 8_000.0,
        rise: Double = 0.004,
        dipBars: Int = 6,
        dipPct: Double = 0.05,
        reboundPct: Double = 0.008,
        lastOpenFactor: Double? = null,
        lastCloseFactor: Double = 1.0,
        sawPct: Double = 0.0,
        sawPeriod: Int = 6,
        tailBars: Int = 0,
    ): List<Candle> {
        val chrono = mutableListOf<Candle>()
        var p = start
        repeat(bars - dipBars - 1) { i ->
            p *= (1 + rise)
            // 주기적 되밀림(톱니)은 추세·눌림 구조를 유지한 채 RSI 만 끌어내린다 — RSI 조건 단독 검증용.
            val c = if (sawPct > 0 && i % sawPeriod == sawPeriod - 1) p * (1 - sawPct) else p
            chrono += candle(c * 0.997, c * 1.006, c * 0.994, c)
        }

        val peak = chrono.last().tradePrice
        for (i in 1..dipBars) {
            val c = peak * (1 - dipPct * i / dipBars)
            chrono += candle(c * 1.004, c * 1.006, c * 0.996, c)
        }

        val low = chrono.last().tradePrice
        val close = low * (1 + reboundPct) * lastCloseFactor
        val open = lastOpenFactor?.let { close * it } ?: (low * 0.999)
        chrono += candle(open, maxOf(close, open) * 1.003, minOf(low, open) * 0.996, close, 120.0)

        // 백테는 신호 다음 봉 시가에 체결하므로, 진입 신호가 마지막 봉이면 거래가 성립하지 않는다.
        // tailBars 를 주면 신호 뒤에 봉이 남는다(기본 0 — 단위 테스트는 candles[0] 이 신호 봉이어야 한다).
        var tail = close
        repeat(tailBars) {
            tail *= (1 + rise)
            chrono += candle(tail * 0.997, tail * 1.006, tail * 0.994, tail)
        }
        return chrono.reversed()
    }

    /**
     * 강한 상승으로 RSI 를 70 위로 올린 뒤 완만히 조정 — 마지막 봉에서 RSI 가 70 을 하향 돌파한다.
     * 단봉 급락으로는 Wilder smoothing 때문에 70 을 못 뚫어서, 조정을 여러 봉에 걸쳐 준다.
     */
    fun overheatFade(
        bars: Int = 45,
        start: Double = 10_000.0,
        rise: Double = 0.012,
        fadeBars: Int = 3,
        fadePct: Double = 0.025,
    ): List<Candle> {
        val chrono = mutableListOf<Candle>()
        var p = start
        repeat(bars - fadeBars) {
            p *= (1 + rise)
            chrono += candle(p * 0.995, p * 1.008, p * 0.99, p)
        }
        repeat(fadeBars) {
            p *= (1 - fadePct)
            chrono += candle(p * 1.004, p * 1.006, p * 0.996, p)
        }
        return chrono.reversed()
    }

    /**
     * 지그재그 횡보(RSI 중립 유지) + 직전 봉만 밴드 상단 위로 스파이크 → 현재 봉 복귀.
     * 횡보를 완전 평탄하게 두면 avgLoss=0 이라 RSI 가 100 이 되어 과열 조건까지 켜지므로 지그재그로 만든다.
     */
    fun bandReturn(
        bars: Int = 45,
        base: Double = 10_000.0,
        zigzagPct: Double = 0.006,
        spikePct: Double = 0.08,
        backPct: Double = 0.0,
    ): List<Candle> {
        val chrono = mutableListOf<Candle>()
        for (i in 0 until bars - 2) {
            val p = base * (if (i % 2 == 0) 1 + zigzagPct else 1 - zigzagPct)
            chrono += candle(p, p * 1.001, p * 0.999, p)
        }
        val spikeClose = base * (1 + spikePct)
        chrono += candle(base, spikeClose * 1.002, base * 0.998, spikeClose)
        val backClose = base * (1 + backPct)
        chrono += candle(spikeClose, spikeClose * 1.001, backClose * 0.998, backClose)
        return chrono.reversed()
    }

    /** 스파이크 없는 지그재그 횡보 — 어깨 청산이 발동하지 않아야 하는 대조군. */
    fun zigzagFlat(bars: Int = 45, base: Double = 10_000.0, zigzagPct: Double = 0.006) =
        bandReturn(bars = bars, base = base, zigzagPct = zigzagPct, spikePct = 0.0, backPct = 0.0)

    /**
     * 무릎 진입 이후 상승 → 과열 → 조정까지 이어지는 긴 시계열.
     * 백테에서 매수 신호와 어깨 청산(CHART_EXIT)이 모두 발생하는지 보는 용도다.
     */
    fun reversalThenRally(
        rallyBars: Int = 24,
        rallyPct: Double = 0.012,
        fadeBars: Int = 4,
        fadePct: Double = 0.025,
    ): List<Candle> {
        // 백테는 51번째 봉부터 신호를 평가하므로(BacktestEngine.kt:87) 무릎 구간이 그 뒤에 와야 한다.
        // 횡보를 50봉 더 깔아, 무릎 시점의 50봉 window 가 reversal() 기본형과 같은 모양이 되게 한다.
        val chrono = reversal(flatBars = 75).reversed().toMutableList()
        var p = chrono.last().tradePrice
        repeat(rallyBars) {
            p *= (1 + rallyPct)
            chrono += candle(p * 0.995, p * 1.008, p * 0.99, p)
        }
        repeat(fadeBars) {
            p *= (1 - fadePct)
            chrono += candle(p * 1.004, p * 1.006, p * 0.996, p)
        }
        return chrono.reversed()
    }

    /** O/H/L 이 비어 있는 불량 캔들 — 0 분모 가드 확인용. */
    fun zeroOhlc(bars: Int = 45, close: Double = 100.0) =
        List(bars) { candle(0.0, 0.0, 0.0, close) }
}
