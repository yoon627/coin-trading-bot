package com.trading.bot.kis.client

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 가상 시간으로 허가 시각과 순서를 본다. */
@OptIn(ExperimentalCoroutinesApi::class)
class KisRateLimiterTest {

    private fun TestScope.limiter(intervalMs: Long, shortWaitMs: Long = 2_000, longWaitMs: Long = 30_000) =
        KisRateLimiter(intervalMs, shortWaitMs, longWaitMs) { testScheduler.currentTime }

    /** 허가된 순서대로 (이름, 허가 시각) 을 남긴다. */
    private class Grants {
        val log = mutableListOf<Pair<String, Long>>()
    }

    private fun TestScope.request(limiter: KisRateLimiter, lane: KisLane, name: String, grants: Grants): Job = launch {
        limiter.acquire(lane)
        grants.log += name to currentTime
    }

    @Test
    fun `허가 사이 간격은 최소 간격 이상이다`() = runTest {
        val limiter = limiter(intervalMs = 667)
        val grants = Grants()

        (1..4).map { request(limiter, KisLane.RECONCILE, "r$it", grants) }.forEach { it.join() }

        assertThat(grants.log.map { it.second }).containsExactly(0L, 667L, 1_334L, 2_001L)
    }

    @Test
    fun `슬롯이 열리면 가장 높은 레인이 받고 같은 레인은 먼저 온 순서다`() = runTest {
        val limiter = limiter(intervalMs = 100)
        limiter.acquire(KisLane.QUERY)
        val grants = Grants()

        listOf(
            KisLane.QUERY to "query", KisLane.QUOTE to "quote-1", KisLane.RECONCILE to "reconcile", KisLane.QUOTE to "quote-2",
            KisLane.ORDER to "order", KisLane.HELD_QUOTE to "held",
        ).map { (lane, name) -> request(limiter, lane, name, grants) }.forEach { it.join() }

        assertThat(grants.log.map { it.first }).containsExactly("held", "order", "reconcile", "quote-1", "quote-2", "query")
        assertThat(grants.log.map { it.second }).containsExactly(100L, 200L, 300L, 400L, 500L, 600L)
    }

    @Test
    fun `늦게 온 주문이 먼저 기다리던 QUOTE 를 앞선다`() = runTest {
        val limiter = limiter(intervalMs = 500)
        limiter.acquire(KisLane.QUOTE)
        val grants = Grants()

        val quote = request(limiter, KisLane.QUOTE, "quote", grants)
        advanceTimeBy(100)
        val order = request(limiter, KisLane.ORDER, "order", grants)
        listOf(quote, order).forEach { it.join() }

        assertThat(grants.log).containsExactly("order" to 500L, "quote" to 1_000L)
    }

    @Test
    fun `QUOTE 가 짧은 상한을 넘게 기다리면 throttled Transient 로 건너뛴다`() = runTest {
        val limiter = limiter(intervalMs = 1_000, shortWaitMs = 2_000)
        limiter.acquire(KisLane.ORDER)
        val orders = (1..3).map { async { limiter.acquire(KisLane.ORDER) } }

        val error = assertThrows<KisTransientException> { limiter.acquire(KisLane.QUOTE) }

        assertThat(error.throttled).isTrue()
        assertThat(currentTime).isEqualTo(2_000L)
        orders.awaitAll()
        assertThat(currentTime).isEqualTo(3_000L)
    }

    @Test
    fun `긴 상한 레인도 상한을 넘으면 Transient 다`() = runTest {
        val limiter = limiter(intervalMs = 1_000, longWaitMs = 1_500)
        limiter.acquire(KisLane.HELD_QUOTE)
        val held = async { limiter.acquire(KisLane.HELD_QUOTE) }

        assertThrows<KisTransientException> { limiter.acquire(KisLane.ORDER) }

        assertThat(currentTime).isEqualTo(1_500L)
        held.await()
    }

    @Test
    fun `close 하면 대기자와 이후 요청이 바로 실패한다`() = runTest {
        val limiter = limiter(intervalMs = 10_000)
        limiter.acquire(KisLane.ORDER)
        val waiting = async { runCatching { limiter.acquire(KisLane.ORDER) } }
        runCurrent()

        limiter.close()
        runCurrent()

        assertThat(waiting.isCompleted).isTrue()
        assertThat(waiting.await().exceptionOrNull()).isInstanceOf(KisTransientException::class.java)
        assertThat(currentTime).isZero()
        assertThrows<KisTransientException> { limiter.acquire(KisLane.HELD_QUOTE) }
    }

    @Test
    fun `취소된 대기자는 큐에서 빠지고 슬롯을 쓰지 않는다`() = runTest {
        val limiter = limiter(intervalMs = 1_000)
        limiter.acquire(KisLane.ORDER)
        val grants = Grants()

        val cancelled = request(limiter, KisLane.ORDER, "cancelled", grants)
        val next = request(limiter, KisLane.QUERY, "next", grants)
        advanceTimeBy(500)
        cancelled.cancel()
        next.join()

        assertThat(grants.log).containsExactly("next" to 1_000L)
    }
}
