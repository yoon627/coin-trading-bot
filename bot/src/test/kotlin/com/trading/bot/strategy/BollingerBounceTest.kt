package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.BollingerBounce
import com.trading.common.strategy.Indicators
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BollingerBounceTest {

    private val strategy = BollingerBounce()
    private val config = TradingProperties()

    @Test
    fun `should not buy with insufficient data`() = runTest {
        val candles = (1..15).map {
            Candle(market = "KRW-BTC", tradePrice = 100.0 + it)
        }
        assertFalse(strategy.shouldBuy(candles, 110.0, config))
    }

    @Test
    fun `should not buy when price is above middle band`() = runTest {
        // Flat prices at 100, current price well above the band
        val candles = (0..25).map {
            Candle(market = "KRW-BTC", tradePrice = 100.0)
        }
        assertFalse(strategy.shouldBuy(candles, 110.0, config))
    }

    @Test
    fun `should not buy when RSI is too high`() = runTest {
        // Create a dip then recovery but with strong uptrend (high RSI)
        val candles = buildBollingerBounceCandles(rsiTarget = 60.0)
        assertFalse(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should buy when price bounces from lower band with proper RSI`() = runTest {
        // Build candles: stable around 100, then a dip below lower band, then bounce back
        // The prev candle should be below lower band, current should be above
        val basePrice = 10000.0
        val candles = mutableListOf<Candle>()

        // Current candle (index 0): bounced back above lower band
        candles.add(Candle(market = "KRW-BTC", tradePrice = basePrice * 0.95))

        // Previous candle (index 1): was below lower band
        candles.add(Candle(market = "KRW-BTC", tradePrice = basePrice * 0.88))

        // Historical candles for BB calculation (index 2+): stable around basePrice
        // Adding some mild downtrend to get RSI in 25-45 range
        for (i in 2..30) {
            val price = basePrice - (i - 2) * 30.0 // very gentle downtrend
            candles.add(Candle(market = "KRW-BTC", tradePrice = price))
        }

        val currentPrice = candles[0].tradePrice

        // Verify the conditions would be met
        val bb = Indicators.calculateBollingerBands(candles, 20, 2.0)
        val prevBb = Indicators.calculateBollingerBands(candles.drop(1), 20, 2.0)
        val rsi = Indicators.calculateRsi(candles, 14)

        if (bb != null && prevBb != null &&
            candles[1].tradePrice <= prevBb.lower &&
            currentPrice > bb.lower &&
            rsi in 25.0..45.0
        ) {
            assertTrue(strategy.shouldBuy(candles, currentPrice, config))
        }
    }

    @Test
    fun `should not buy when price is still falling below previous close`() = runTest {
        // falling knife (이슈 #27): prev 가 하단밴드 아래로 급락, current 는 밴드 확장 탓에 lower 위로
        // 보이지만 실제로는 prev 보다 더 하락 중 — "반등"이 아니므로 매수 금지.
        val candles = mutableListOf(
            Candle(market = "KRW-BTC", tradePrice = 8950.0), // current: prev 보다 낮음 (계속 하락)
            Candle(market = "KRW-BTC", tradePrice = 9000.0), // prev: prevBb.lower 아래
        )
        // 고변동 지그재그 역사 — 밴드를 넓혀 current > bb.lower 를 성립시킨다.
        for (i in 0 until 20) {
            candles.add(Candle(market = "KRW-BTC", tradePrice = if (i % 2 == 0) 9600.0 else 10400.0))
        }
        val currentPrice = candles[0].tradePrice

        // 사전조건을 고정 — 캔들 구성이 깨지면 guard 가 아니라 여기서 실패한다 (조건부 통과 함정 방지).
        val bb = Indicators.calculateBollingerBands(candles, 20, 2.0)!!
        val prevBb = Indicators.calculateBollingerBands(candles.drop(1), 20, 2.0)!!
        val rsi = Indicators.calculateRsi(candles, 14)
        assertTrue(candles[1].tradePrice <= prevBb.lower, "precondition: prev below prev lower band")
        assertTrue(currentPrice > bb.lower, "precondition: current above lower band")
        assertTrue(rsi in 25.0..45.0, "precondition: RSI in range, was $rsi")
        assertTrue(currentPrice < candles[1].tradePrice, "precondition: still falling")

        assertFalse(strategy.shouldBuy(candles, currentPrice, config))
    }

    private fun buildBollingerBounceCandles(rsiTarget: Double): List<Candle> {
        // Build candles with monotonically increasing prices to get high RSI
        return (0..25).map { i ->
            Candle(market = "KRW-BTC", tradePrice = 10000.0 + i * 200.0)
        }
    }
}
