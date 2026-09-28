package com.trading.bot.client

import com.trading.bot.domain.Order
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AwaitFillTest {

    private val client = mockk<UpbitClient>()

    @Test
    fun `an order that stays open is polled FILL_POLL_ATTEMPTS times and its last response returned`() = runTest {
        // wait 는 terminal 이 아니다 — executed>0 이어도 잔여 체결분이 더 올 수 있어 확정하지 않고 끝까지 본다.
        // 체결량이 조회마다 늘어난다 — 엔진은 반환값의 executedVolume 을 쓰므로 첫 응답이 아니라 마지막 응답이어야 한다.
        coEvery { client.getOrder("o-wait") } returnsMany (1..FILL_POLL_ATTEMPTS).map {
            Order(uuid = "o-wait", state = "wait", executedVolume = "0.$it")
        }

        val last = client.awaitFill("o-wait")

        assertEquals("0.$FILL_POLL_ATTEMPTS", last?.executedVolume)
        coVerify(exactly = FILL_POLL_ATTEMPTS) { client.getOrder("o-wait") }
    }

    @Test
    fun `polling stops as soon as the order becomes terminal`() = runTest {
        coEvery { client.getOrder("o-late") } returnsMany listOf(
            Order(uuid = "o-late", state = "wait", executedVolume = "0.1"),
            Order(uuid = "o-late", state = "done", executedVolume = "0.3"),
        )

        val filled = client.awaitFill("o-late")

        assertEquals("done", filled?.state)
        coVerify(exactly = 2) { client.getOrder("o-late") }
    }
}
