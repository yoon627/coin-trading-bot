package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle

class MeanReversion : TradingStrategy {
    override val name = "mean_reversion"

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 21) return false

        val ma20 = Indicators.calculateMa(candles, 20)
        if (ma20 <= 0) return false

        // Price deviation from MA20
        val deviation = (currentPrice - ma20) / ma20

        // Buy when price is significantly below MA (oversold deviation)
        val oversold = deviation < -0.03 // More than 3% below MA20

        // Bollinger band width check: volatility is not too extreme
        val bb = Indicators.calculateBollingerBands(candles, 20, 2.0) ?: return false
        val normalVolatility = bb.width < 0.15

        // RSI showing recovery from oversold
        val rsi = Indicators.calculateRsi(candles, 14)
        val rsiRecovering = rsi in 25.0..40.0

        // Volume confirmation: current volume > average
        val avgVolume = candles.take(10).map { it.candleAccTradeVolume }.average()
        val currentVolume = candles[0].candleAccTradeVolume
        val volumeOk = avgVolume <= 0 || currentVolume >= avgVolume * 0.8

        return oversold && normalVolatility && rsiRecovering && volumeOk
    }

    // 진입(MA20 -3% 이탈)의 청산: 종가가 MA20 으로 상향 복귀(평균 회귀 완료). candles 종가 기반(currentPrice 미사용).
    // bb.middle(SMA20)==calculateMa(,20) 라 BollingerBounce 청산과 수치 동일 — 각 전략이 자기 지표 맥락을 유지(진입 신호는 차별).
    override suspend fun shouldSell(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 21) return false

        val ma20 = Indicators.calculateMa(candles, 20)
        val prevMa20 = Indicators.calculateMa(candles.drop(1), 20)
        if (ma20 <= 0 || prevMa20 <= 0) return false

        // 직전 종가는 MA20 아래, 현재 종가는 MA20 위로 복귀
        return candles[1].tradePrice < prevMa20 && candles[0].tradePrice >= ma20
    }
}
