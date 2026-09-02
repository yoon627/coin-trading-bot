package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.common.domain.Candle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DailyCandleCacheTest {

    private val client = mockk<UpbitClient>()
    private val start = Instant.parse("2026-09-03T00:00:00Z")

    private fun cache(at: Instant = start) = DailyCandleCache(client, Clock.fixed(at, ZoneOffset.UTC))

    @Test
    fun `serves the cached response within the ttl`() = runTest {
        coEvery { client.getDayCandles("KRW-A", 60) } returns List(60) { Candle() }
        val cache = cache()

        cache.get("KRW-A", 60)
        cache.get("KRW-A", 60)

        coVerify(exactly = 1) { client.getDayCandles("KRW-A", 60) }
    }

    @Test
    fun `a short history is cached too — the exchange has no more candles to give`() = runTest {
        // 상장 60일 미만 종목: 60 을 요청해도 20 만 온다. 이를 miss 로 보면 매 tick REST 를 다시 친다.
        coEvery { client.getDayCandles("KRW-NEW", 60) } returns List(20) { Candle() }
        val cache = cache()

        assertEquals(20, cache.get("KRW-NEW", 60).size)
        assertEquals(20, cache.get("KRW-NEW", 60).size)

        coVerify(exactly = 1) { client.getDayCandles("KRW-NEW", 60) }
    }

    @Test
    fun `refetches after the ttl`() = runTest {
        coEvery { client.getDayCandles("KRW-A", 60) } returns List(60) { Candle() }
        val before = DailyCandleCache(client, Clock.fixed(start, ZoneOffset.UTC))
        before.get("KRW-A", 60)

        val later = DailyCandleCache(client, Clock.fixed(start.plusMillis(DailyCandleCache.TTL_MS), ZoneOffset.UTC))
        later.get("KRW-A", 60)

        coVerify(exactly = 2) { client.getDayCandles("KRW-A", 60) }
    }
}
