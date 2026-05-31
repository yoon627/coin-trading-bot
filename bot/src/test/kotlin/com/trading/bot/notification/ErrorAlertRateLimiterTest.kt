package com.trading.bot.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ErrorAlertRateLimiterTest {

    @Test
    fun `첫 발생은 허용되고 억제건수 0`() {
        val rl = ErrorAlertRateLimiter()
        val d = rl.decide("fp1", 0L)
        assertTrue(d.allow)
        assertEquals(0, d.suppressedSince)
    }

    @Test
    fun `쿨다운 내 동일 fingerprint 는 억제`() {
        val rl = ErrorAlertRateLimiter(dedupCooldownMs = 300_000)
        rl.decide("fp1", 0L)
        assertFalse(rl.decide("fp1", 100_000L).allow)
    }

    @Test
    fun `쿨다운 경과 후 허용 + 그동안 억제건수 요약`() {
        val rl = ErrorAlertRateLimiter(dedupCooldownMs = 300_000)
        rl.decide("fp1", 0L)        // allow
        rl.decide("fp1", 100_000L)  // suppress 1
        rl.decide("fp1", 200_000L)  // suppress 2
        val d = rl.decide("fp1", 400_000L) // 쿨다운 경과 → allow
        assertTrue(d.allow)
        assertEquals(2, d.suppressedSince)
    }

    @Test
    fun `다른 fingerprint 는 독립적으로 허용`() {
        val rl = ErrorAlertRateLimiter()
        assertTrue(rl.decide("fp1", 0L).allow)
        assertTrue(rl.decide("fp2", 0L).allow)
    }

    @Test
    fun `전역 분당 상한 초과 시 억제`() {
        val rl = ErrorAlertRateLimiter(globalPerMinute = 3)
        assertTrue(rl.decide("a", 0L).allow)
        assertTrue(rl.decide("b", 0L).allow)
        assertTrue(rl.decide("c", 0L).allow)
        assertFalse(rl.decide("d", 0L).allow) // 4번째, 1분 내
    }

    @Test
    fun `전역 윈도우 경과 후 다시 허용`() {
        val rl = ErrorAlertRateLimiter(globalPerMinute = 2)
        rl.decide("a", 0L)
        rl.decide("b", 0L)
        assertFalse(rl.decide("c", 30_000L).allow) // 1분 내
        assertTrue(rl.decide("c", 61_000L).allow)  // 1분 경과
    }

    @Test
    fun `size cap 초과 시 오래된 entry 가 evict 되어 즉시 허용`() {
        val rl = ErrorAlertRateLimiter(maxEntries = 2, dedupCooldownMs = 300_000)
        rl.decide("a", 0L)
        rl.decide("b", 1L)
        rl.decide("c", 2L) // size 초과 → 가장 오래된 a evict (FIFO)
        assertTrue(rl.decide("a", 3L).allow) // a 기록 없으니 즉시 allow
    }
}
