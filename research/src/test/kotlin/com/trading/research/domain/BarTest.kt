package com.trading.research.domain

import com.trading.common.domain.Exchange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class BarTest {
    @Test
    fun `toNormalizedCandle preserves OHLCV and market pair`() {
        val asset = Asset(Exchange.UPBIT, "BTC/KRW")
        val bar = Bar(
            openTime = Instant.parse("2024-01-01T00:00:00Z"),
            closeTime = Instant.parse("2024-01-02T00:00:00Z"),
            open = 100.0, high = 110.0, low = 95.0, close = 105.0,
            volume = 1.5, quoteVolume = 155.0,
        )

        val candle = bar.toNormalizedCandle(asset)

        assertEquals("BTC/KRW", candle.market)
        assertEquals(100.0, candle.openPrice)
        assertEquals(110.0, candle.highPrice)
        assertEquals(95.0, candle.lowPrice)
        assertEquals(105.0, candle.closePrice)
        assertEquals(1.5, candle.volume)
    }
}
