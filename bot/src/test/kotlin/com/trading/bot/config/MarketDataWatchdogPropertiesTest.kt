package com.trading.bot.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MarketDataWatchdogPropertiesTest {

    private val props = MarketDataWatchdogProperties(staleMs = 60_000)

    @Test
    fun `isStale is false when nothing received yet`() {
        assertFalse(props.isStale(now = 100_000, lastReceivedAt = 0))
    }

    @Test
    fun `isStale is false within threshold`() {
        assertFalse(props.isStale(now = 100_000, lastReceivedAt = 50_000)) // 50s < 60s
    }

    @Test
    fun `isStale is true beyond threshold`() {
        assertTrue(props.isStale(now = 100_000, lastReceivedAt = 30_000)) // 70s > 60s
    }

    @Test
    fun `defaults are conservative and enabled`() {
        val d = MarketDataWatchdogProperties()
        assertTrue(d.enabled)
        assertEquals(60_000, d.staleMs)
        assertEquals(20_000, d.intervalMs)
        assertEquals(30_000, d.initialDelayMs)
        assertEquals(1_000, d.restartBackoffMs)
    }
}
