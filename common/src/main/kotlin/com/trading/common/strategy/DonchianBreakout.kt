package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle

/**
 * Donchian 채널 상단 돌파 — 현재 종가가 **직전 20봉(현재 봉 제외)** 의 최고가를 넘으면 매수.
 *
 * 입력은 최신순(index 0 = 최신)이라 비교 대상 window 는 index 1..20 이다.
 */
class DonchianBreakout : TradingStrategy {
    override val name = "donchian_breakout"

    // 현재 봉 1 + 직전 20봉.
    override val minCandles = 21

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < minCandles) return false

        val priorHigh = candles.subList(1, 1 + CHANNEL_PERIOD).maxOf { it.high }
        return candles[0].close > priorHigh
    }

    private companion object {
        const val CHANNEL_PERIOD = 20
    }
}
