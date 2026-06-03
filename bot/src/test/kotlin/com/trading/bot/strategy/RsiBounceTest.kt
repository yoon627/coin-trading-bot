package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.Indicators
import com.trading.common.strategy.RsiBounce
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RsiBounceTest {

    private val strategy = RsiBounce()
    private val config = TradingProperties()

    @Test
    fun `should not buy with insufficient data`() = runTest {
        val candles = (1..10).map {
            Candle(market = "KRW-BTC", tradePrice = 100.0 + it)
        }
        assertFalse(strategy.shouldBuy(candles, 110.0, config))
    }

    @Test
    fun `should not buy when RSI is high`() = runTest {
        // Monotonically increasing prices -> high RSI
        val candles = (16 downTo 0).map {
            Candle(market = "KRW-BTC", tradePrice = 100.0 + it * 5.0)
        }
        assertFalse(strategy.shouldBuy(candles, 180.0, config))
    }

    @Test
    fun `should sell on RSI 50 downward cross`() = runTest {
        // [0]=최신 급락, [1..17]=시간 역순 단조상승.
        // prevRsi(최신 제외)=100, curRsi(급락 포함)≈39 → RSI 50 하향교차.
        val candles = buildList {
            add(Candle(market = "KRW-BTC", tradePrice = 1100.0)) // [0] 1300→1100 (Δ200 > 13×10)
            for (i in 1..17) add(Candle(market = "KRW-BTC", tradePrice = 1300.0 - (i - 1) * 10.0))
        }
        assertTrue(strategy.shouldSell(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should sell on RSI 50 cross from mid-range`() = runTest {
        // prevRsi 가 100 극단이 아닌 중간값(50~75)에서 50 하향교차하는지 — 잡음 구간 검증.
        // 시간순(오래→최신): +10/-5 지그재그(상승 우세, RSI 중간) 후 최신 봉 급락.
        val chrono = buildList {
            var p = 9000.0
            add(p)
            for (k in 0 until 15) {
                p += if (k % 2 == 0) 10.0 else -5.0
                add(p)
            }
            add(p - 50.0) // 최신 급락
        }
        val candles = chrono.reversed().map { Candle(market = "KRW-BTC", tradePrice = it) }

        // 사전조건을 단언으로 고정(조건부 assert 회피): prev 는 중간값, cur 는 50 미만.
        val prevRsi = Indicators.calculateRsi(candles.drop(1), 14)
        val curRsi = Indicators.calculateRsi(candles, 14)
        assertTrue(prevRsi in 50.0..75.0, "prevRsi 중간값 사전조건 (actual=$prevRsi)")
        assertTrue(curRsi < 50.0, "curRsi<50 사전조건 (actual=$curRsi)")

        assertTrue(strategy.shouldSell(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should not sell on uptrend keeping RSI above 50`() = runTest {
        // 단조상승 → RSI 항상 100 → 하향교차 없음.
        val candles = (17 downTo 0).map { Candle(market = "KRW-BTC", tradePrice = 1000.0 + it * 10.0) }
        assertFalse(strategy.shouldSell(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should not sell with insufficient data`() = runTest {
        val candles = (1..10).map { Candle(market = "KRW-BTC", tradePrice = 100.0 + it) }
        assertFalse(strategy.shouldSell(candles, 110.0, config))
    }
}
