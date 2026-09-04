package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.Ohlc
import kotlin.math.abs

/**
 * ADX/DMI 추세 진입 — +DI 가 −DI 를 상향 교차하고 추세 강도(ADX)가 20 이상일 때 매수.
 *
 * 평활은 Wilder 가 아니라 **단순평균**이다(Indicators.calculateAtr 과 같은 관례). 값이 표준 Wilder ADX 와
 * 다르므로 외부 차트 지표와 직접 비교하면 안 된다.
 *
 * 입력은 최신순(index 0 = 최신)이다.
 */
class AdxTrend : TradingStrategy {
    override val name = "adx_trend"

    // ADX = DX 14개의 평균, DX 하나는 DI(14) 라 15봉 → 최장 lookback 은 index 13+14 = 27 → 28봉.
    override val minCandles = 28

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < minCandles) return false

        val current = directionalIndex(candles, 0) ?: return false
        val prev = directionalIndex(candles, 1) ?: return false
        val adx = adx(candles) ?: return false

        val bullishCross = prev.plusDi <= prev.minusDi && current.plusDi > current.minusDi
        return bullishCross && adx >= 20.0
    }

    private data class Di(val plusDi: Double, val minusDi: Double)

    /** [offset] 봉을 현재로 보는 +DI/−DI. 분모(TR 합)가 0 이면 방향을 정의할 수 없어 null. */
    private fun directionalIndex(candles: List<Ohlc>, offset: Int): Di? {
        if (candles.size < offset + PERIOD + 1) return null

        var trSum = 0.0
        var plusDmSum = 0.0
        var minusDmSum = 0.0

        for (i in offset until offset + PERIOD) {
            val cur = candles[i]
            val prev = candles[i + 1]

            trSum += maxOf(
                cur.high - cur.low,
                abs(cur.high - prev.close),
                abs(cur.low - prev.close),
            )

            val upMove = cur.high - prev.high
            val downMove = prev.low - cur.low
            if (upMove > downMove && upMove > 0) plusDmSum += upMove
            if (downMove > upMove && downMove > 0) minusDmSum += downMove
        }

        if (trSum <= 0.0) return null
        return Di(plusDi = 100.0 * plusDmSum / trSum, minusDi = 100.0 * minusDmSum / trSum)
    }

    /** DX 14개의 단순평균. DI 합이 0 인 구간은 방향성 없음(DX=0)으로 센다. */
    private fun adx(candles: List<Ohlc>): Double? {
        var sum = 0.0
        for (offset in 0 until PERIOD) {
            val di = directionalIndex(candles, offset) ?: return null
            val diSum = di.plusDi + di.minusDi
            sum += if (diSum <= 0.0) 0.0 else 100.0 * abs(di.plusDi - di.minusDi) / diSum
        }
        return sum / PERIOD
    }

    private companion object {
        const val PERIOD = 14
    }
}
