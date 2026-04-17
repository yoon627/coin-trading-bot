package com.trading.bot.ml

import com.trading.bot.config.MlProperties
import com.trading.common.domain.Candle
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MlModelServiceTest {

    private val tempDir = Files.createTempDirectory("ml-test").toFile().also { it.deleteOnExit() }
    private val service = MlModelService(MlProperties(modelDir = tempDir.absolutePath, autoLoadOnStartup = false))

    @Test
    fun `hasModel returns false when no model trained`() {
        assertFalse(service.hasModel("KRW-BTC"))
    }

    @Test
    fun `getMetrics returns null when no model trained`() {
        assertNull(service.getMetrics("KRW-BTC"))
    }

    @Test
    fun `predict returns null when no model trained`() {
        val candles = buildCandles(55)
        assertNull(service.predict("KRW-BTC", candles))
    }

    @Test
    fun `train returns error with insufficient data`() {
        val candles = (0..40).map {
            Candle(market = "KRW-BTC", tradePrice = 10000.0)
        }
        val result = service.train("KRW-BTC", candles)

        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test
    fun `train succeeds with adequate data`() {
        val candles = buildTrainingCandles(200)
        val result = service.train("KRW-BTC", candles)

        assertTrue(result.success, "Training should succeed: ${result.error}")
        assertNotNull(result.metrics)
        result.metrics!!.let { metrics ->
            assertTrue(metrics.accuracy in 0.0..1.0)
            assertTrue(metrics.precision in 0.0..1.0)
            assertTrue(metrics.recall in 0.0..1.0)
            assertTrue(metrics.trainSize > 0)
            assertTrue(metrics.testSize > 0)
            assertTrue(metrics.featureImportance.isNotEmpty())
        }
    }

    @Test
    fun `predict works after training`() {
        val trainingCandles = buildTrainingCandles(200)
        val result = service.train("KRW-BTC", trainingCandles)
        assertTrue(result.success, "Training should succeed: ${result.error}")

        val predictionCandles = buildCandles(55)
        val prediction = service.predict("KRW-BTC", predictionCandles)

        assertNotNull(prediction)
        prediction!!
        assertTrue(prediction.probability in 0.0..1.0)
        assertEquals(FeatureExtractor.FEATURE_NAMES.size, prediction.features.size)
    }

    @Test
    fun `hasModel returns true after training`() {
        val candles = buildTrainingCandles(200)
        service.train("KRW-BTC", candles)

        assertTrue(service.hasModel("KRW-BTC"))
        assertFalse(service.hasModel("KRW-ETH"))
    }

    private fun buildCandles(count: Int): List<Candle> {
        return (0 until count).map { i ->
            val base = 10000.0 + (count - i) * 20.0
            Candle(
                market = "KRW-BTC",
                tradePrice = base,
                openingPrice = base - 10.0,
                highPrice = base + 50.0,
                lowPrice = base - 50.0,
                candleAccTradeVolume = 100.0 + i * 2.0,
            )
        }
    }

    private fun buildTrainingCandles(count: Int): List<Candle> {
        // Create a mix of trends to give the model something to learn
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
