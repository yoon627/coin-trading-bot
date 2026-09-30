package com.trading.bot.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// 테스트용 호출 — fingerprint 를 요약으로 쓴다(운영 호출부 appender 는 마스킹한 줄을 넘긴다).
private fun ErrorAlertRateLimiter.decide(fingerprint: String, nowMs: Long, logger: String = "") =
    decide(fingerprint, nowMs, logger) { fingerprint }

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
        assertFalse(rl.decide("c", 30_000L).allow) // 1분 내 — 요약으로 보류된다
        assertTrue(rl.decide("d", 61_000L).allow)  // 1분 경과 — 새 알림은 다시 개별로
    }

    @Test
    fun `상한에 걸린 새 fingerprint 는 요약으로 보류되고 첫 보류만 예약을 요청한다`() {
        val rl = ErrorAlertRateLimiter(globalPerMinute = 1)
        rl.decide("a", 0L)
        val first = rl.decide("b", 1L, "com.x.Engine") { "b 요약" }
        assertFalse(first.allow)
        assertTrue(first.scheduleDigest)
        assertFalse(rl.decide("c", 2L, "com.x.Engine") { "c 요약" }.scheduleDigest) // 같은 창 — 이미 예약됐다

        val digest = rl.drainHeld(3L)!!
        assertEquals(listOf("b 요약", "c 요약"), digest.alerts.map { it.summary })
        assertEquals(listOf("com.x.Engine", "com.x.Engine"), digest.alerts.map { it.logger })
        assertNull(rl.drainHeld(4L))
        assertTrue(rl.decide("d", 5L).scheduleDigest) // 요약 뒤 보류는 다시 예약을 요청한다

        rl.digestNotScheduled() // 예약이 거부되면 다음 보류가 다시 요청한다
        assertTrue(rl.decide("e", 6L).scheduleDigest)
    }

    @Test
    fun `보류 중인 fingerprint 는 창이 풀려도 개별로 나가지 않고 횟수만 는다`() {
        val rl = ErrorAlertRateLimiter(globalPerMinute = 1)
        rl.decide("a", 0L)
        rl.decide("b", 1L)
        assertFalse(rl.decide("b", 61_000L).allow) // 요약에 실릴 알림이다 — 개별로 보내면 두 번 나간다
        assertEquals(2, rl.drainHeld(62_000L)!!.alerts.single().occurrences)
    }

    @Test
    fun `요약으로 나간 fingerprint 는 쿨다운 동안 억제되고 요약에 실린 억제분은 다시 세지 않는다`() {
        val rl = ErrorAlertRateLimiter(dedupCooldownMs = 300_000, globalPerMinute = 1)
        rl.decide("x", 0L)       // 허용
        rl.decide("x", 100_000L) // 쿨다운 억제 1
        rl.decide("x", 200_000L) // 쿨다운 억제 2
        rl.decide("y", 400_000L) // 허용 — 창을 채운다
        rl.decide("x", 400_001L) // 쿨다운 밖·상한 초과 → 보류(앞의 억제 2 + 이번 1)
        assertEquals(3, rl.drainHeld(400_002L)!!.alerts.single().occurrences)

        assertFalse(rl.decide("x", 500_000L).allow) // 요약 시각부터 쿨다운
        val later = rl.decide("x", 700_003L)
        assertTrue(later.allow)
        assertEquals(1, later.suppressedSince) // 요약 뒤 억제 1회만 — 요약에 실린 2회는 다시 세지 않는다
    }

    @Test
    fun `쿨다운 안의 반복은 상한과 무관하게 보류하지 않는다`() {
        val rl = ErrorAlertRateLimiter(globalPerMinute = 1)
        rl.decide("x", 0L)
        assertFalse(rl.decide("x", 1L).scheduleDigest)
        assertNull(rl.drainHeld(2L))
    }

    @Test
    fun `보류 상한을 넘은 알림은 로거별 건수로만 세고 그 건수를 나중 개별 알림에서 다시 세지 않는다`() {
        val rl = ErrorAlertRateLimiter(dedupCooldownMs = 300_000, globalPerMinute = 1, maxHeld = 1)
        rl.decide("c", 0L, "com.x.Upbit")        // 허용
        rl.decide("c", 100_000L, "com.x.Upbit")  // 쿨다운 억제 1
        rl.decide("a", 400_000L)                 // 허용 — 창을 채운다
        rl.decide("b", 400_001L, "com.x.Engine") // 보류(상한 1)
        rl.decide("c", 400_002L, "com.x.Upbit")  // 보류 상한 초과 → overflow(앞의 억제 1 + 이번 1)
        val digest = rl.drainHeld(400_003L)!!
        assertEquals(listOf("b"), digest.alerts.map { it.summary })
        assertEquals(mapOf("com.x.Upbit" to 2), digest.overflowByLogger)

        val c = rl.decide("c", 461_000L, "com.x.Upbit") // 창이 풀리면 개별로
        assertTrue(c.allow)
        assertEquals(0, c.suppressedSince) // 요약에 실린 2회를 다시 세지 않는다
    }

    @Test
    fun `요약으로 나간 fingerprint 는 삽입순 맨 뒤로 가 곧바로 밀려나지 않는다`() {
        val rl = ErrorAlertRateLimiter(dedupCooldownMs = 300_000, globalPerMinute = 1, maxEntries = 2)
        rl.decide("x", 0L)       // [x]
        rl.decide("y", 400_000L) // [x, y] — 창을 채운다
        rl.decide("x", 400_001L) // 쿨다운 밖·상한 초과 → 보류
        rl.drainHeld(400_002L)   // x 를 맨 뒤로 → [y, x]
        rl.decide("z", 470_000L) // 가장 오래된 y 가 밀려난다 → [x, z]
        assertFalse(rl.decide("x", 531_000L).allow) // 창은 비었지만 x 는 요약 시각부터 쿨다운이다
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
