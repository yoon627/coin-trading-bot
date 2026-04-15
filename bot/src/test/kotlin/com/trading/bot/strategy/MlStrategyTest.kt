package com.trading.bot.strategy

import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.Candle
import com.trading.bot.ml.MlModelService
import com.trading.bot.ml.PredictionResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MlStrategyTest {

    private val mlModelService = mockk<MlModelService>()
    private val strategy = MlStrategy(mlModelService)
    private val config = TradingProperties()

    @Test
    fun `should not buy with insufficient data`() = runTest {
        val candles = (1..30).map {
            Candle(market = "KRW-BTC", tradePrice = 100.0 + it)
        }
        assertFalse(strategy.shouldBuy(candles, 130.0, config))
    }

    @Test
    fun `should not buy when no model is trained`() = runTest {
        val candles = buildCandles(55)
        every { mlModelService.hasModel("KRW-BTC") } returns false

        assertFalse(strategy.shouldBuy(candles, 10000.0, config))
    }

    @Test
    fun `should not buy when model probability is low`() = runTest {
        val candles = buildCandles(55)
        every { mlModelService.hasModel("KRW-BTC") } returns true
        every { mlModelService.predict("KRW-BTC", candles) } returns PredictionResult(
            signal = false,
            probability = 0.3,
            features = emptyMap(),
        )

        assertFalse(strategy.shouldBuy(candles, 10000.0, config))
    }

    @Test
    fun `should not buy when model is confident but price below MA50`() = runTest {
        // Build candles with declining trend so current price is below MA50
        val candles = (0..55).map { i ->
            Candle(market = "KRW-BTC", tradePrice = 12000.0 - i * 100.0)
        }
        val currentPrice = candles[0].tradePrice // 12000 (top, but MA50 includes higher prices)

        every { mlModelService.hasModel("KRW-BTC") } returns true
        every { mlModelService.predict("KRW-BTC", candles) } returns PredictionResult(
            signal = true,
            probability = 0.8,
            features = emptyMap(),
        )

        // MA50 will be average of all prices, which is above current bottom prices
        // With this trend, price at index 0 = 12000, MA50 avg will be lower
        // Actually index 0 is highest here, so it would pass MA50 check
        // Let's flip the trend:
        val decliningCandles = (0..55).map { i ->
            Candle(market = "KRW-BTC", tradePrice = 10000.0 + i * 50.0)
        }
        // Index 0 = 10000, MA50 = avg(10000..12750) ≈ 11375 -> price below MA50
        every { mlModelService.predict("KRW-BTC", decliningCandles) } returns PredictionResult(
            signal = true,
            probability = 0.8,
            features = emptyMap(),
        )

        assertFalse(strategy.shouldBuy(decliningCandles, decliningCandles[0].tradePrice, config))
    }

    @Test
    fun `should buy when model is confident and price above MA50`() = runTest {
        // Build rising candles so current price is above MA50
        val candles = (0..55).map { i ->
            Candle(market = "KRW-BTC", tradePrice = 12000.0 - i * 30.0)
        }
        // Index 0 = 12000, older prices decrease, so MA50 will be below 12000
        val currentPrice = candles[0].tradePrice

        every { mlModelService.hasModel("KRW-BTC") } returns true
        every { mlModelService.predict("KRW-BTC", candles) } returns PredictionResult(
            signal = true,
            probability = 0.75,
            features = emptyMap(),
        )

        val ma50 = Indicators.calculateMa(candles, 50)
        if (currentPrice > ma50) {
            assertTrue(strategy.shouldBuy(candles, currentPrice, config))
        }
    }

    private fun buildCandles(count: Int): List<Candle> {
        return (0 until count).map { i ->
            Candle(market = "KRW-BTC", tradePrice = 10000.0 + (count - i) * 10.0)
        }
    }
}
