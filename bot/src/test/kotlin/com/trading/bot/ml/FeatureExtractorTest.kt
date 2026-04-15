package com.trading.bot.ml

import com.trading.bot.domain.Candle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FeatureExtractorTest {

    @Test
    fun `extract returns null with insufficient candles`() {
        val candles = (0..30).map {
            Candle(market = "KRW-BTC", tradePrice = 10000.0 + it * 10.0)
        }
        assertNull(FeatureExtractor.extract(candles))
    }

    @Test
    fun `extract returns 20 features with sufficient data`() {
        val candles = buildCandles(55)
        val features = FeatureExtractor.extract(candles)

        assertNotNull(features)
        assertEquals(20, features!!.size)
        assertEquals(FeatureExtractor.FEATURE_NAMES.size, features.size)
    }

    @Test
    fun `extract returns all finite values`() {
        val candles = buildCandles(55)
        val features = FeatureExtractor.extract(candles)!!

        features.forEachIndexed { i, value ->
            assertTrue(value.isFinite(), "Feature ${FeatureExtractor.FEATURE_NAMES[i]} is not finite: $value")
        }
    }

    @Test
    fun `rsi features are normalized between 0 and 1`() {
        val candles = buildCandles(55)
        val features = FeatureExtractor.extract(candles)!!

        val rsi14 = features[0]
        val rsi7 = features[1]
        assertTrue(rsi14 in 0.0..1.0, "RSI14 should be 0-1: $rsi14")
        assertTrue(rsi7 in 0.0..1.0, "RSI7 should be 0-1: $rsi7")
    }

    @Test
    fun `volume ratios are clamped to 0-5`() {
        val candles = buildCandles(55)
        val features = FeatureExtractor.extract(candles)!!

        val volRatio5 = features[11]
        val volRatio20 = features[12]
        assertTrue(volRatio5 in 0.0..5.0, "Volume ratio 5 out of range: $volRatio5")
        assertTrue(volRatio20 in 0.0..5.0, "Volume ratio 20 out of range: $volRatio20")
    }

    @Test
    fun `createDataset returns null with insufficient data`() {
        val candles = (0..40).map {
            Candle(market = "KRW-BTC", tradePrice = 10000.0)
        }
        assertNull(FeatureExtractor.createDataset(candles))
    }

    @Test
    fun `createDataset returns labeled dataset with sufficient data`() {
        val candles = buildCandles(100)
        val result = FeatureExtractor.createDataset(candles, targetPct = 2.0, horizon = 5)

        assertNotNull(result)
        val (features, labels) = result!!
        assertTrue(features.isNotEmpty())
        assertEquals(features.size, labels.size)
        // Each feature vector should have 20 elements
        features.forEach { assertEquals(20, it.size) }
        // Labels should be 0 or 1
        labels.forEach { assertTrue(it == 0 || it == 1) }
    }

    @Test
    fun `createDataset labels are correct for uptrend`() {
        // Build candles with strong uptrend - should have some positive labels
        val candles = (0..100).map { i ->
            Candle(
                market = "KRW-BTC",
                tradePrice = 10000.0 + (100 - i) * 100.0,  // strong uptrend in chronological order
                openingPrice = 10000.0 + (100 - i) * 95.0,
                highPrice = 10000.0 + (100 - i) * 110.0,
                lowPrice = 10000.0 + (100 - i) * 85.0,
                candleAccTradeVolume = 100.0,
            )
        }
        val result = FeatureExtractor.createDataset(candles, targetPct = 2.0, horizon = 5)

        assertNotNull(result)
        val (_, labels) = result!!
        val positiveRate = labels.count { it == 1 }.toDouble() / labels.size
        assertTrue(positiveRate > 0, "Uptrend should produce some positive labels")
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
}
