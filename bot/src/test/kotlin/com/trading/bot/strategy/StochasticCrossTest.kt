package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.StochasticCross
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StochasticCrossTest {

    private val strategy = StochasticCross()
    private val config = TradingProperties()

    /**
     * 시간순(오래→최신) 종가를 최신순 캔들로 뒤집는다. 고가/저가는 110/90 고정이라
     * %K = (close - 90) / 20 * 100 으로 종가만으로 %K 를 지정할 수 있다.
     */
    private fun candlesOf(chronologicalCloses: List<Double>): List<Candle> =
        chronologicalCloses.reversed().map {
            Candle(market = "KRW-BTC", highPrice = 110.0, lowPrice = 90.0, tradePrice = it)
        }

    /** %K 값(0~100)에 대응하는 종가. */
    private fun closeForK(k: Double) = 90.0 + k * 0.2

    /** 최신 4봉의 %K 를 지정하고 나머지는 %K=25 로 채운다. */
    private fun seriesEndingWithK(k3: Double, k2: Double, k1: Double, k0: Double): List<Candle> =
        candlesOf(List(17) { closeForK(25.0) } + listOf(k3, k2, k1, k0).map(::closeForK))

    @Test
    fun `should buy when percentK crosses above percentD in oversold zone`() = runTest {
        // %K: 25 → 20 → 15 → 28. 직전 %D=20 (K 15 이하), 현재 %D≈21.67 → 상향 교차 + %K 28 < 30.
        val candles = seriesEndingWithK(k3 = 25.0, k2 = 20.0, k1 = 15.0, k0 = 28.0)
        assertTrue(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should not buy when the cross happens above the oversold level`() = runTest {
        // 같은 교차지만 교차 시점 %K 가 45 — 과매도 구간이 아니라 신호가 아니다.
        val candles = seriesEndingWithK(k3 = 25.0, k2 = 20.0, k1 = 15.0, k0 = 45.0)
        assertFalse(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should not buy when percentK is exactly at the oversold level`() = runTest {
        // 경계: %K == 30 은 "30 미만" 이 아니다.
        val candles = seriesEndingWithK(k3 = 25.0, k2 = 20.0, k1 = 15.0, k0 = 30.0)
        assertFalse(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should not buy when the range is flat`() = runTest {
        // 고가 == 저가 → %K 분모 0. 예외 없이 false.
        val candles = List(21) { Candle(market = "KRW-BTC", highPrice = 100.0, lowPrice = 100.0, tradePrice = 100.0) }
        assertFalse(strategy.shouldBuy(candles, 100.0, config))
    }

    @Test
    fun `should not buy with insufficient data`() = runTest {
        val candles = seriesEndingWithK(k3 = 25.0, k2 = 20.0, k1 = 15.0, k0 = 28.0).take(10)
        assertFalse(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }
}
