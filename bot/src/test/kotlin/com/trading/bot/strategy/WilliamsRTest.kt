package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.WilliamsR
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WilliamsRTest {

    private val strategy = WilliamsR()
    private val config = TradingProperties()

    /**
     * 시간순(오래→최신) 종가를 최신순 캔들로 뒤집는다. 고가/저가는 110/90 고정이라
     * %R = (110 - close) / 20 * -100 으로 종가만으로 %R 을 지정할 수 있다.
     */
    private fun candlesOf(chronologicalCloses: List<Double>): List<Candle> =
        chronologicalCloses.reversed().map {
            Candle(market = "KRW-BTC", highPrice = 110.0, lowPrice = 90.0, tradePrice = it)
        }

    /** 최신 2봉 종가만 지정하고 나머지는 중립(%R = -50)으로 채운다. */
    private fun seriesEndingWith(prevClose: Double, currentClose: Double): List<Candle> =
        candlesOf(List(19) { 100.0 } + listOf(prevClose, currentClose))

    @Test
    fun `should buy when percentR crosses above minus eighty`() = runTest {
        // 직전 %R = -90 (과매도), 현재 %R = -25 → 상향 돌파.
        val candles = seriesEndingWith(prevClose = 92.0, currentClose = 105.0)
        assertTrue(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should not buy when percentR stays in mid range`() = runTest {
        // 직전·현재 모두 %R = -50 → 과매도 진입 자체가 없다.
        val candles = seriesEndingWith(prevClose = 100.0, currentClose = 100.0)
        assertFalse(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should treat minus eighty as the boundary`() = runTest {
        // 경계: 직전 -80(과매도 인정), 현재도 -80 이면 "위로 올라옴" 이 아니다.
        assertFalse(strategy.shouldBuy(seriesEndingWith(94.0, 94.0), 94.0, config))
        // 한 틱만 위면 돌파.
        assertTrue(strategy.shouldBuy(seriesEndingWith(94.0, 95.0), 95.0, config))
    }

    @Test
    fun `should not buy when the range is flat`() = runTest {
        // 고가 == 저가 → %R 분모 0. 예외 없이 false.
        val candles = List(21) { Candle(market = "KRW-BTC", highPrice = 100.0, lowPrice = 100.0, tradePrice = 100.0) }
        assertFalse(strategy.shouldBuy(candles, 100.0, config))
    }

    @Test
    fun `should not buy with insufficient data`() = runTest {
        val candles = seriesEndingWith(prevClose = 92.0, currentClose = 105.0).take(10)
        assertFalse(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }
}
