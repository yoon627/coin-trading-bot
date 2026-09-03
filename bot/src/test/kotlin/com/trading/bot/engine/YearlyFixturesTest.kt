package com.trading.bot.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YearlyFixturesTest {

    @Test
    fun `every market has 365 newest-first complete bars ending 2026-09-02`() {
        val all = YearlyFixtures.loadAll()
        assertEquals(8, all.size)
        for ((market, candles) in all) {
            assertEquals(YearlyFixtures.BARS, candles.size, market)
            assertEquals("2026-09-02T09:00:00", candles.first().candleDateTimeKst, "$market 최신봉")
            assertEquals("2025-09-03T09:00:00", candles.last().candleDateTimeKst, "$market 최고(最古)봉")
            assertTrue(candles.all { it.tradePrice > 0.0 && it.highPrice >= it.lowPrice }, "$market 가격 무결성")
            assertEquals(candles.size, candles.map { it.candleDateTimeKst }.distinct().size, "$market 날짜 중복")
        }
    }
}
