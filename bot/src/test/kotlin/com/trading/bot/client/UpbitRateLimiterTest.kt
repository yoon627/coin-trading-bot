package com.trading.bot.client

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.currentTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 업비트 private API 는 계좌마다 초당 주문 12·그 밖 30 을 넘으면 429, 반복되면 418 이다. 어떤 1초 창에서도 여유 있게 그 아래로 보낸다. */
class UpbitRateLimiterTest {

    private fun maxInAnySecond(sentAtMs: List<Long>): Int =
        sentAtMs.indices.maxOf { i -> sentAtMs.count { it >= sentAtMs[i] && it < sentAtMs[i] + 1_000 } }

    private fun kotlinx.coroutines.test.TestScope.limiter() =
        UpbitRateLimiter(nowMs = { currentTime })

    @Test
    fun `requests other than order creation never exceed 25 in any one-second window`() = runTest {
        val limiter = limiter()
        val sent = mutableListOf<Long>()

        repeat(100) { launch { limiter.acquire(UpbitRateLimiter.Group.DEFAULT); sent += currentTime } }
        testScheduler.advanceUntilIdle()

        assertEquals(100, sent.size, "거절하지 않고 기다려서 모두 보낸다")
        assertTrue(maxInAnySecond(sent.sorted()) <= 25, "1초 창 최대 ${maxInAnySecond(sent.sorted())}")
    }

    @Test
    fun `order creation never exceeds 10 in any one-second window and does not use the other budget`() = runTest {
        val limiter = limiter()
        val orders = mutableListOf<Long>()
        val others = mutableListOf<Long>()

        repeat(30) { launch { limiter.acquire(UpbitRateLimiter.Group.ORDER); orders += currentTime } }
        repeat(5) { launch { limiter.acquire(UpbitRateLimiter.Group.DEFAULT); others += currentTime } }
        testScheduler.advanceUntilIdle()

        assertTrue(maxInAnySecond(orders.sorted()) <= 10, "1초 창 최대 ${maxInAnySecond(orders.sorted())}")
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), others, "주문이 밀려도 다른 요청은 기다리지 않는다")
    }

    @Test
    fun `a quiet limiter lets a short burst through at once`() = runTest {
        val limiter = limiter()
        val sent = mutableListOf<Long>()

        repeat(5) { launch { limiter.acquire(UpbitRateLimiter.Group.DEFAULT); sent += currentTime } }
        testScheduler.advanceUntilIdle()

        assertEquals(List(5) { 0L }, sent)
    }

    // 급락 때 8종목이 한꺼번에 손절한다: 종목마다 잔고 조회 → 매도 주문 → 체결 확인 폴링 5번. 업비트 공식 한도(주문 12·그 밖 30)를
    // 어떤 1초 창에서도 넘으면 429 를 주는 가짜 거래소에서도 모두 끝난다.
    @Test
    fun `eight simultaneous stop-loss sells stay under the official limits and all finish`() = runTest {
        val limiter = limiter()
        val orders = mutableListOf<Long>()
        val others = mutableListOf<Long>()
        fun call(group: UpbitRateLimiter.Group) {
            val sent = if (group == UpbitRateLimiter.Group.ORDER) orders else others
            sent += currentTime
            val limit = if (group == UpbitRateLimiter.Group.ORDER) 12 else 30
            check(maxInAnySecond(sent.sorted()) <= limit) { "429 — $group ${maxInAnySecond(sent.sorted())}/s" }
        }
        var sold = 0

        repeat(8) {
            launch {
                limiter.acquire(UpbitRateLimiter.Group.DEFAULT); call(UpbitRateLimiter.Group.DEFAULT)
                limiter.acquire(UpbitRateLimiter.Group.ORDER); call(UpbitRateLimiter.Group.ORDER)
                repeat(5) { limiter.acquire(UpbitRateLimiter.Group.DEFAULT); call(UpbitRateLimiter.Group.DEFAULT); kotlinx.coroutines.delay(300) }
                sold++
            }
        }
        testScheduler.advanceUntilIdle()

        assertEquals(8, sold)
    }

    @Test
    fun `clients of the same account share one limiter and other accounts get their own`() {
        val limiters = UpbitRateLimiters()

        assertSame(limiters.forAccount("key-a"), limiters.forAccount("key-a"))
        assertNotSame(limiters.forAccount("key-a"), limiters.forAccount("key-b"))
    }
}
