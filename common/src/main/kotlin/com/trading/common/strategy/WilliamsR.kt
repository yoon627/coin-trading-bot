package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.Ohlc

/**
 * Williams %R(14)이 과매도선(-80)을 상향 돌파할 때 매수.
 *
 * 백테 탐색 전용 — Spring bean 으로 등록하지 않는다.
 */
class WilliamsR : TradingStrategy {
    override val name = "williams_r"

    // 진입에 필요한 건 15봉이지만, 기본 shouldSell(5/20 데드크로스)이 21봉을 요구한다.
    override val minCandles = 21

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < PERIOD + 1) return false

        val current = williamsR(candles, 0) ?: return false
        val prev = williamsR(candles, 1) ?: return false

        return prev <= OVERSOLD && current > OVERSOLD
    }

    /** [offset] 봉(0=최신)을 현재봉으로 보는 %R. 입력이 최신순이라 window 는 offset 부터 과거 방향으로 PERIOD 개다. */
    private fun williamsR(candles: List<Ohlc>, offset: Int): Double? {
        if (candles.size < offset + PERIOD) return null
        val window = candles.subList(offset, offset + PERIOD)
        val highest = window.maxOf { it.high }
        val lowest = window.minOf { it.low }
        val range = highest - lowest
        // 무변동 구간에서 %R 은 정의되지 않는다 — 임의값으로 돌파를 만들지 말고 신호를 버린다.
        if (range <= 0.0) return null
        return (highest - window[0].close) / range * -100.0
    }

    private companion object {
        const val PERIOD = 14
        const val OVERSOLD = -80.0
    }
}
