package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle

class RsiBounce : TradingStrategy {
    override val name = "rsi_bounce"

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 16) return false

        val currentRsi = Indicators.calculateRsi(candles, 14)
        val prevRsi = Indicators.calculateRsi(candles.drop(1), 14)

        // RSI crossing above oversold level (30)
        return currentRsi > 30.0 && prevRsi <= 30.0
    }

    // 진입(RSI 30 상향돌파)의 청산: RSI 가 50 중립선을 하향 교차(반등 모멘텀 소진). candles 종가 기반(currentPrice 미사용).
    // calculateRsi 는 전체구간 누적 Wilder 라 drop(1)=직전 시점 RSI(shouldBuy·GoldenCross·MacdCross 와 동일 관례).
    // warm-up(size≈16) 경계에선 prev(seed)·cur(1스텝 평활) 비대칭이 있으나 운영 N≥21(store 백필 200)에서 비실효.
    override suspend fun shouldSell(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 16) return false

        val curRsi = Indicators.calculateRsi(candles, 14)
        val prevRsi = Indicators.calculateRsi(candles.drop(1), 14)

        // RSI crossing below neutral level (50)
        return prevRsi >= 50.0 && curRsi < 50.0
    }
}
