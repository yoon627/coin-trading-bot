package com.trading.bot.kis.marketdata

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class KisMarketCalendarTest {
    private val KST = ZoneId.of("Asia/Seoul")
    private fun at(instant: String) = KisMarketCalendarImpl(Clock.fixed(Instant.parse(instant), KST))

    // 2026-06-15 은 월요일.
    @Test fun `open at 0900 KST weekday`() = assertTrue(at("2026-06-15T00:00:00Z").isTradingNow()) // 09:00 KST

    @Test fun `closed before 0900`() = assertFalse(at("2026-06-14T23:59:00Z").isTradingNow()) // 08:59 KST (still Mon? 08:59 Mon)

    @Test fun `open at 1530 boundary`() = assertTrue(at("2026-06-15T06:30:00Z").isTradingNow()) // 15:30 KST

    @Test fun `closed after 1530`() = assertFalse(at("2026-06-15T06:31:00Z").isTradingNow()) // 15:31 KST

    @Test fun `closed on weekend`() = assertFalse(at("2026-06-20T03:00:00Z").isTradingNow()) // Sat 12:00 KST
}
