package com.trading.bot.kis.marketdata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FallbackCacheTest {

    /** 시간 경과를 제어하는 가짜 단조시계. */
    private class FakeClock {
        var nanos: Long = 0
        fun advanceMs(ms: Long) {
            nanos += ms * 1_000_000
        }
    }

    private fun cache(ttlMs: Long, clock: FakeClock) = FallbackCache<String>(ttlMs) { clock.nanos }

    @Test
    fun `value is reused within ttl and dropped after`() {
        val clock = FakeClock()
        val c = cache(ttlMs = 5_000, clock)
        c.put("005930", "v1")

        clock.advanceMs(4_999)
        assertEquals("v1", c.get("005930"))

        clock.advanceMs(2)
        assertNull(c.get("005930"), "TTL 경과 후에는 stale 로 보고 재조회해야 한다")
    }

    @Test
    fun `unknown key is a miss`() {
        assertNull(cache(ttlMs = 1_000, FakeClock()).get("000660"))
    }

    @Test
    fun `failures back off exponentially and success clears them`() {
        val clock = FakeClock()
        val c = cache(ttlMs = 5_000, clock)

        assertTrue(c.shouldAttempt("005930"), "실패 이력이 없으면 즉시 시도한다")

        c.recordFailure("005930")
        assertFalse(c.shouldAttempt("005930"))
        clock.advanceMs(1_000) // 1회차 backoff = 1s
        assertTrue(c.shouldAttempt("005930"))

        c.recordFailure("005930")
        c.recordFailure("005930") // 누적 3회 → 4s
        clock.advanceMs(2_000)
        assertFalse(c.shouldAttempt("005930"), "누적 실패는 대기시간이 지수적으로 늘어야 한다")
        clock.advanceMs(2_000)
        assertTrue(c.shouldAttempt("005930"))

        c.recordSuccess("005930")
        c.recordFailure("005930")
        clock.advanceMs(1_000)
        assertTrue(c.shouldAttempt("005930"), "성공 후에는 실패 누적이 초기화돼 1회차 backoff 로 돌아간다")
    }

    @Test
    fun `backoff is capped`() {
        val clock = FakeClock()
        val c = cache(ttlMs = 5_000, clock)
        repeat(20) { c.recordFailure("005930") }

        clock.advanceMs(60_000)
        assertTrue(c.shouldAttempt("005930"), "상한(60s)을 넘겨 무한정 늘어나면 안 된다")
    }

    @Test
    fun `separate caches do not share failure state`() {
        // 엔진은 price/candle 을 별도 인스턴스로 둔다 — 캔들 실패가 가격 조회를 막으면 안 된다.
        val clock = FakeClock()
        val priceCache = cache(ttlMs = 5_000, clock)
        val candleCache = cache(ttlMs = 300_000, clock)

        candleCache.recordFailure("005930")

        assertFalse(candleCache.shouldAttempt("005930"))
        assertTrue(priceCache.shouldAttempt("005930"))
    }
}
