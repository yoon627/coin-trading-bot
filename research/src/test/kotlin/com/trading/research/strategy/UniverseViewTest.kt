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

    @Test
    fun `recentBars returns empty for asset that has not been advanced yet`() {
        val btc = Asset(Exchange.UPBIT, "BTC/KRW")
        val eth = Asset(Exchange.UPBIT, "ETH/KRW")
        val history = mapOf(
            btc to (0L..9L).map { bar(100.0 + it, it) },
            eth to (0L..9L).map { bar(200.0 + it, it) },
        )
        // Only BTC has had a BarEvent; ETH has not advanced yet.
        // Returning ETH.bars[0] here would be lookahead in cross-asset strategies.
        val view = RollingUniverseView(history, currentBarIndex = mapOf(btc to 3L))
        assertEquals(emptyList<Bar>(), view.recentBars(eth, 5))
    }

    @Test
    fun `recentBars starts returning bars only after advance is called`() {
        val history = mapOf(asset to (0L..4L).map { bar(100.0 + it, it) })
        val view = RollingUniverseView(history, currentBarIndex = emptyMap())
        assertEquals(emptyList<Bar>(), view.recentBars(asset, 3))

        view.advance(asset, 0L)
        val afterFirstAdvance = view.recentBars(asset, 3)
        assertEquals(1, afterFirstAdvance.size)
        assertEquals(100.0, afterFirstAdvance[0].close)
    }
}
