package com.trading.research.strategy

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class UniverseViewTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    private fun bar(close: Double, dayOffset: Long): Bar {
        val t = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(dayOffset * 86400)
        return Bar(t, t.plusSeconds(86400), close, close, close, close, 1.0, close)
    }

    @Test
    fun `recentBars returns last N bars in order`() {
        val history = mapOf(asset to (0L..9L).map { bar(100.0 + it, it) })
        val view = RollingUniverseView(history, currentBarIndex = mapOf(asset to 5L))
        val recent = view.recentBars(asset, 3)
        assertEquals(3, recent.size)
        assertEquals(103.0, recent[0].close)
        assertEquals(105.0, recent[2].close)
    }

    @Test
    fun `recentBars returns fewer if history shorter`() {
        val history = mapOf(asset to listOf(bar(100.0, 0), bar(101.0, 1)))
        val view = RollingUniverseView(history, currentBarIndex = mapOf(asset to 1L))
        val recent = view.recentBars(asset, 10)
        assertEquals(2, recent.size)
    }
}
