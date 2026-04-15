package com.trading.bot.ml

import com.trading.bot.domain.Candle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HyperparameterTunerTest {

    private val tuner = HyperparameterTuner()

    @Test
    fun `tune returns null with insufficient data`() {
        val candles = (0..40).map {
            Candle(market = "KRW-BTC", tradePrice = 10000.0)
        }
        assertNull(tuner.tune(candles))
    }

    @Test
    fun `tune returns result with adequate data`() {
        val candles = buildTrainingCandles(200)
        val result = tuner.tune(candles, targetPct = 2.0, horizon = 5)

        assertNotNull(result)
        result!!
        assertTrue(result.bestF1 >= 0.0)
        assertTrue(result.allResults.isNotEmpty())
        assertTrue(result.bestParams.ntrees in listOf(50, 100, 200))
        assertTrue(result.bestParams.maxDepth in listOf(3, 4, 6))
        assertTrue(result.bestParams.shrinkage in listOf(0.05, 0.1, 0.2))
    }

    @Test
    fun `tune allResults contain expected fields`() {
        val candles = buildTrainingCandles(200)
        val result = tuner.tune(candles) ?: return

        result.allResults.forEach { entry ->
            assertTrue(entry.containsKey("ntrees"))
            assertTrue(entry.containsKey("maxDepth"))
            assertTrue(entry.containsKey("shrinkage"))
            assertTrue(entry.containsKey("avg_f1"))
            assertTrue(entry.containsKey("folds"))
        }
    }

    @Test
    fun `tune results are sorted by F1 descending`() {
        val candles = buildTrainingCandles(200)
        val result = tuner.tune(candles) ?: return

        val f1Scores = result.allResults.map { it["avg_f1"] as Double }
        for (i in 0 until f1Scores.size - 1) {
            assertTrue(f1Scores[i] >= f1Scores[i + 1])
        }
    }

    private fun buildTrainingCandles(count: Int): List<Candle> {
        return (0 until count).map { i ->
            val cycle = (count - i) % 40
            val trend = if (cycle < 20) 1.0 else -1.0
            val base = 10000.0 + trend * cycle * 50.0 + (count - i) * 5.0
            Candle(
                market = "KRW-BTC",
                tradePrice = base,
                openingPrice = base - 20.0 * trend,
                highPrice = base + 80.0,
                lowPrice = base - 80.0,
                candleAccTradeVolume = 100.0 + (i % 20) * 10.0,
            )
        }
    }
}
