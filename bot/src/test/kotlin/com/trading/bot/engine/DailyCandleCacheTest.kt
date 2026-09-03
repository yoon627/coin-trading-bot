package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.common.domain.Candle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DailyCandleCacheTest {

    private val client = mockk<UpbitClient>()
    private val start = Instant.parse("2026-09-03T00:00:00Z")

    /** 한 인스턴스 안에서 시각을 움직여야 TTL 이 실제로 검증된다 — 인스턴스를 둘 만들면 캐시가 비어 무엇이든 통과한다. */
    private class MutableClock(var nowMs: Long) : Clock() {
        override fun millis(): Long = nowMs
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    @Test
    fun `serves the cached response within the ttl`() = runTest {
        coEvery { client.getDayCandles("KRW-A", 60) } returns List(60) { Candle() }
        val cache = DailyCandleCache(client, Clock.fixed(start, ZoneOffset.UTC))

        cache.get("KRW-A", 60)
        cache.get("KRW-A", 60)

        coVerify(exactly = 1) { client.getDayCandles("KRW-A", 60) }
    }

    @Test
    fun `a short history is cached too — the exchange has no more candles to give`() = runTest {
        // 상장 60일 미만 종목: 60 을 요청해도 20 만 온다. 이를 miss 로 보면 매 tick REST 를 다시 친다.
        coEvery { client.getDayCandles("KRW-NEW", 60) } returns List(20) { Candle() }
        val cache = DailyCandleCache(client, Clock.fixed(start, ZoneOffset.UTC))

        assertEquals(20, cache.get("KRW-NEW", 60).size)
        assertEquals(20, cache.get("KRW-NEW", 60).size)

        coVerify(exactly = 1) { client.getDayCandles("KRW-NEW", 60) }
    }

    @Test
    fun `refetches only once the ttl has elapsed`() = runTest {
        coEvery { client.getDayCandles("KRW-A", 60) } returns List(60) { Candle() }
        val clock = MutableClock(start.toEpochMilli())
        val cache = DailyCandleCache(client, clock)

        cache.get("KRW-A", 60)
        clock.nowMs += DailyCandleCache.TTL_MS - 1
        cache.get("KRW-A", 60)
        coVerify(exactly = 1) { client.getDayCandles("KRW-A", 60) }

        clock.nowMs += 1
        cache.get("KRW-A", 60)
        coVerify(exactly = 2) { client.getDayCandles("KRW-A", 60) }
    }
}
