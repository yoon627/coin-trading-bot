package com.trading.bot.kis.market

import com.trading.bot.kis.client.KisApi
import com.trading.bot.kis.client.KisDailyCandle
import com.trading.bot.kis.client.KisLane
import com.trading.bot.kis.client.KisTransientException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KisDailyHistoryCacheTest {

    private val api = mockk<KisApi>()
    private val code = "005930"

    // KST 2026-10-21 09:30.
    private val clock = MutableClock(Instant.parse("2026-10-21T00:30:00Z"))
    private val today = LocalDate.of(2026, 10, 21)

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private fun rows(n: Int, newest: LocalDate = today.minusDays(1)) =
        List(n) { KisDailyCandle(newest.minusDays(it.toLong()), 1, 1, 1, 1, 1) }

    private fun stubAny(result: List<KisDailyCandle>) {
        coEvery { api.dailyCandles(code, any(), any(), any(), any()) } returns result
    }

    @Test
    fun `어제까지를 한 번 받아 그날 동안 다시 쓴다`() = runTest {
        stubAny(rows(60))
        val cache = KisDailyHistoryCache(clock)

        val first = cache.history(api, code, 60, KisLane.QUOTE)
        clock.now = Instant.parse("2026-10-21T14:59:59Z")
        val again = cache.history(api, code, 30, KisLane.QUOTE)

        assertThat(first).hasSize(60)
        assertThat(again).isEqualTo(first.take(30))
        coVerify(exactly = 1) { api.dailyCandles(code, any(), any(), any(), any()) }
        coVerify { api.dailyCandles(code, today.minusDays(130), today.minusDays(1), true, KisLane.QUOTE) }
    }

    @Test
    fun `키가 바뀌어 다른 KisApi 로 물어도 그날 받은 이력을 그대로 쓴다`() = runTest {
        stubAny(rows(60))
        val replaced = mockk<KisApi>()
        val cache = KisDailyHistoryCache(clock)

        val first = cache.history(api, code, 60, KisLane.QUOTE)
        val afterKeyChange = cache.history(replaced, code, 60, KisLane.QUOTE)

        assertThat(afterKeyChange).isEqualTo(first)
        coVerify(exactly = 0) { replaced.dailyCandles(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `KST 날짜가 바뀌면 다시 받는다`() = runTest {
        stubAny(rows(60))
        val cache = KisDailyHistoryCache(clock)

        cache.history(api, code, 60, KisLane.QUOTE)
        clock.now = Instant.parse("2026-10-21T15:00:00Z")
        cache.history(api, code, 60, KisLane.QUOTE)

        coVerify(exactly = 2) { api.dailyCandles(code, any(), any(), any(), any()) }
        coVerify { api.dailyCandles(code, today.plusDays(1).minusDays(130), today, true, KisLane.QUOTE) }
    }

    @Test
    fun `더 많은 count 면 다시 받고 받은 행이 요청보다 적었으면 다시 받지 않는다`() = runTest {
        stubAny(rows(30))
        val cache = KisDailyHistoryCache(clock)

        cache.history(api, code, 30, KisLane.QUOTE)
        cache.history(api, code, 60, KisLane.QUOTE)
        coVerify(exactly = 2) { api.dailyCandles(code, any(), any(), any(), any()) }

        // 상장이 짧아 60 을 요청해도 30 행만 왔다.
        cache.history(api, code, 60, KisLane.QUOTE)
        cache.history(api, code, 50, KisLane.QUOTE)
        coVerify(exactly = 2) { api.dailyCandles(code, any(), any(), any(), any()) }
    }

    @Test
    fun `요청보다 적게 온 이력은 짧은 시간 뒤 다시 받는다`() = runTest {
        coEvery { api.dailyCandles(code, any(), any(), any(), any()) } returns emptyList<KisDailyCandle>() andThen rows(60)
        val cache = KisDailyHistoryCache(clock)

        assertThat(cache.history(api, code, 60, KisLane.QUOTE)).isEmpty()
        clock.now = clock.now.plus(KisDailyHistoryCache.SHORT_RETRY).minusSeconds(1)
        assertThat(cache.history(api, code, 60, KisLane.QUOTE)).isEmpty()
        clock.now = clock.now.plusSeconds(1)

        assertThat(cache.history(api, code, 60, KisLane.QUOTE)).hasSize(60)
        coVerify(exactly = 2) { api.dailyCandles(code, any(), any(), any(), any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `같은 종목의 동시 요청은 한 번만 부른다`() = runTest(timeout = 5.seconds) {
        val gate = CompletableDeferred<List<KisDailyCandle>>()
        coEvery { api.dailyCandles(code, any(), any(), any(), any()) } coAnswers { gate.await() }
        val cache = KisDailyHistoryCache(clock)

        val callers = List(4) { async { cache.history(api, code, 60, KisLane.QUOTE) } }
        runCurrent()
        coVerify(exactly = 1) { api.dailyCandles(code, any(), any(), any(), any()) }
        gate.complete(rows(60))

        assertThat(callers.awaitAll()).allSatisfy { assertThat(it).hasSize(60) }
        coVerify(exactly = 1) { api.dailyCandles(code, any(), any(), any(), any()) }
    }

    @Test
    fun `실패는 그대로 던지고 캐시에 남기지 않는다`() = runTest {
        val failure = KisTransientException("daily-candles", throttled = false, reason = "retries exhausted")
        coEvery { api.dailyCandles(code, any(), any(), any(), any()) } throws failure andThen rows(60)
        val cache = KisDailyHistoryCache(clock)

        assertThat(runCatching { cache.history(api, code, 60, KisLane.QUOTE) }.exceptionOrNull()).isSameAs(failure)
        assertThat(cache.history(api, code, 60, KisLane.QUOTE)).hasSize(60)
        coVerify(exactly = 2) { api.dailyCandles(code, any(), any(), any(), any()) }
    }

    @Test
    fun `긴 기간은 호출당 100행 한도 안의 140일 구간으로 나눠 받고 최신순으로 잇는다`() = runTest {
        val yesterday = today.minusDays(1)
        val from = today.minusDays(200 * 2L + 10)
        val windows = listOf(
            yesterday.minusDays(139) to yesterday,
            yesterday.minusDays(279) to yesterday.minusDays(140),
            from to yesterday.minusDays(280),
        )
        windows.forEach { (start, end) ->
            coEvery { api.dailyCandles(code, start, end, true, KisLane.QUOTE) } returns rows(2, newest = end)
        }
        val cache = KisDailyHistoryCache(clock)

        val history = cache.history(api, code, 200, KisLane.QUOTE)

        assertThat(history.map { it.date }).containsExactly(
            yesterday, yesterday.minusDays(1),
            yesterday.minusDays(140), yesterday.minusDays(141),
            yesterday.minusDays(280), yesterday.minusDays(281),
        )
        coVerify(exactly = 3) { api.dailyCandles(code, any(), any(), any(), any()) }
    }
}
