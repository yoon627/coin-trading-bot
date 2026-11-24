package com.trading.bot.events

import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent

/** 연결마다 hello, 그 뒤 0.5초마다 그 사용자의 바뀐 종류만. 느린 화면에 쌓이지 않고, 종료·수명에 끝나 자리를 돌려준다. */
@OptIn(ExperimentalCoroutinesApi::class)
class UserEventStreamTest {

    private val hub = UserEventHub()

    private fun TestScope.stream(maxPerUser: Int = 5, lifetime: Duration = Duration.ofHours(1)) = UserEventStream(
        hub, 10, maxPerUser, Duration.ofMillis(500), Duration.ofSeconds(15), lifetime, StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.collect(stream: UserEventStream, userId: Long): MutableList<ServerSentEvent<String>> {
        val events = mutableListOf<ServerSentEvent<String>>()
        backgroundScope.launch { stream.events(userId).collect { events += it } }
        runCurrent()
        return events
    }

    private fun List<ServerSentEvent<String>>.names() = mapNotNull { it.event() }

    @Test
    fun `every connection starts with hello, then only that user's changed kinds within half a second`() = runTest {
        hub.publish(1, UserEventHub.Kind.TRADE) // 연결 전 신호는 hello 가 대신한다
        val stream = stream()
        val mine = collect(stream, 1)
        val otherTab = collect(stream, 1)
        val someoneElse = collect(stream, 2)

        assertEquals(listOf("hello"), mine.names())
        assertEquals(UserEventStream.RETRY, mine.single().retry())

        hub.publish(1, UserEventHub.Kind.STATE)
        hub.publish(1, UserEventHub.Kind.STATE)
        hub.publish(1, UserEventHub.Kind.TRADE)
        advanceTimeBy(501)

        assertEquals(listOf("hello", "trade", "state"), mine.names(), "한 번에 바뀐 종류마다 하나")
        assertEquals(listOf("hello", "trade", "state"), otherTab.names())
        assertEquals(listOf("hello"), someoneElse.names())

        advanceTimeBy(2_000)
        assertEquals(3, mine.size, "바뀌지 않으면 보내지 않는다")
    }

    @Test
    fun `a reader that falls behind gets one signal per kind, not one per change`() = runTest {
        val stream = stream()
        val received = mutableListOf<ServerSentEvent<String>>()
        backgroundScope.launch {
            stream.events(1).collect {
                received += it
                delay(10_000)
            }
        }
        runCurrent()
        repeat(20) {
            hub.publish(1, UserEventHub.Kind.STATE)
            advanceTimeBy(500)
        }
        advanceTimeBy(30_000)

        assertTrue(received.count { it.event() == "state" } <= 4, received.names().toString())
        assertEquals("20", received.last { it.event() == "state" }.data(), "마지막 신호는 최신 번호")
    }

    @Test
    fun `a quiet stream sends a heartbeat comment every fifteen seconds`() = runTest {
        val events = collect(stream(), 1)

        advanceTimeBy(15_001)

        assertEquals("ping", events.last().comment())
    }

    @Test
    fun `stopping ends every open stream, gives its slot back and refuses new ones`() = runTest {
        val stream = stream()
        val done = backgroundScope.launch { stream.events(1).collect {} }
        runCurrent()
        assertEquals(1, stream.openConnections())

        stream.stop()
        advanceTimeBy(501)

        assertTrue(done.isCompleted)
        assertEquals(0, stream.openConnections())
        assertNull(stream.tryOpen(2))
    }

    // 다시 붙을 때 인증을 다시 거치게 — 로그아웃·만료된 토큰으로 계속 열려 있지 않는다.
    @Test
    fun `a stream ends after its lifetime and gives its slot back`() = runTest {
        val stream = stream(lifetime = Duration.ofMinutes(1))
        val done = backgroundScope.launch { stream.events(1).collect {} }
        runCurrent()

        advanceTimeBy(59_000)
        assertEquals(1, stream.openConnections())
        advanceTimeBy(1_001)

        assertTrue(done.isCompleted)
        assertEquals(0, stream.openConnections())
    }

    @Test
    fun `a stream that is not being read still ends at its lifetime`() = runTest {
        val stream = stream(lifetime = Duration.ofMinutes(1))
        backgroundScope.launch { stream.events(1).collect { delay(3_600_000) } }
        runCurrent()
        hub.publish(1, UserEventHub.Kind.STATE)
        advanceTimeBy(59_000)
        assertEquals(1, stream.openConnections())

        advanceTimeBy(1_001)

        assertEquals(0, stream.openConnections(), "보내기를 기다리는 중이어도 서버 쪽은 끝나 자리를 돌려준다")
    }

    @Test
    fun `the per-user cap of this stream is its own`() = runTest {
        val stream = stream(maxPerUser = 2)
        stream.tryOpen(1)!!
        stream.tryOpen(1)!!

        assertNull(stream.tryOpen(1))
        assertTrue(stream.hasRoom(2))
    }
}
