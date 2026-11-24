package com.trading.bot.marketdata

import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedTicker
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent

/** 사용자마다 처음에 전체, 그 뒤 1초마다 바뀐 것만. 느린 사용자도 마켓·지운 마켓을 잃지 않는다. 종료 신호에 끝나고 자리를 돌려준다. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarketStreamTest {

    private val store = MarketBoardStore()

    private fun TestScope.stream(maxConnections: Int = 10, maxPerUser: Int = 3) =
        MarketStream(store, maxConnections, maxPerUser, Duration.ofSeconds(1), Duration.ofSeconds(15), StandardTestDispatcher(testScheduler))

    private fun ticker(market: String, price: Double) = NormalizedTicker(Exchange.UPBIT, market, price)

    private fun ServerSentEvent<MarketStream.Payload>.prices() = data()!!.rows.associate { it.m to it.p }

    @Test
    fun `it sends the whole board first, then only what changed each second`() = runTest {
        store.update(ticker("BTC/KRW", 1.0))
        store.update(ticker("ETH/KRW", 1.0))
        val stream = stream()
        val events = mutableListOf<ServerSentEvent<MarketStream.Payload>>()
        backgroundScope.launch { stream.events(1).collect { events += it } }
        runCurrent()

        assertEquals("snapshot", events.single().event())
        assertEquals(MarketStream.RETRY, events.single().retry())
        assertEquals(mapOf("KRW-BTC" to 1.0, "KRW-ETH" to 1.0), events.single().prices())

        store.update(ticker("BTC/KRW", 2.0))
        advanceTimeBy(1_001)

        assertEquals("delta", events[1].event())
        assertEquals(mapOf("KRW-BTC" to 2.0), events[1].prices())
    }

    // 느린 사용자가 여러 초 동안 받지 못해도 그동안 바뀐 마켓은 다음 delta 에 모두 들어 있다.
    @Test
    fun `a reader that falls behind still gets every market and every removal`() = runTest {
        store.update(ticker("BTC/KRW", 1.0))
        store.update(ticker("OLD/KRW", 1.0))
        val stream = stream()
        val flow = stream.events(1)

        val collected = mutableListOf<ServerSentEvent<MarketStream.Payload>>()
        backgroundScope.launch {
            flow.collect {
                collected += it
                kotlinx.coroutines.delay(10_000) // 10초에 하나씩만 읽는다
            }
        }
        runCurrent()
        repeat(5) { i ->
            store.update(ticker("BTC/KRW", 10.0 + i))
            store.update(ticker("ETH/KRW", 20.0 + i))
            advanceTimeBy(1_000)
        }
        store.retain(listOf("BTC/KRW", "ETH/KRW"))
        advanceTimeBy(60_000)

        val latest = mutableMapOf<String, Double>()
        val removed = mutableSetOf<String>()
        collected.filter { it.data() != null }.forEach { e ->
            latest.putAll(e.prices())
            removed.addAll(e.data()!!.removed)
        }
        // 쌓아 두지 않으므로 세 번째로 받는 것(두 번째 delta 가 읽힌 뒤 새로 묶은 것)이 이미 최신이다.
        assertEquals(mapOf("KRW-BTC" to 14.0, "KRW-ETH" to 24.0), collected[2].prices())
        assertEquals(14.0, latest["KRW-BTC"])
        assertEquals(24.0, latest["KRW-ETH"])
        assertEquals(setOf("KRW-OLD"), removed)
    }

    @Test
    fun `a quiet board sends a heartbeat comment every fifteen seconds`() = runTest {
        val stream = stream()
        val events = mutableListOf<ServerSentEvent<MarketStream.Payload>>()
        backgroundScope.launch { stream.events(1).collect { events += it } }
        runCurrent()

        advanceTimeBy(15_001)

        assertEquals("ping", events.last().comment())
    }

    @Test
    fun `stopping ends every open stream and gives its slot back`() = runTest {
        val stream = stream()
        val done = backgroundScope.launch { stream.events(1).collect {} }
        runCurrent()
        assertEquals(1, stream.openConnections())

        stream.stop()
        advanceTimeBy(1_001)

        assertEquals(true, done.isCompleted)
        assertEquals(0, stream.openConnections())
        assertNull(stream.tryOpen(2), "종료 중에는 새 스트림을 받지 않는다")
    }

    @Test
    fun `a reader that leaves after the first event gives its slot back`() = runTest {
        val stream = stream()
        stream.events(1).first()

        assertEquals(0, stream.openConnections())
    }

    @Test
    fun `the total and per-user caps refuse extra streams until one closes`() = runTest {
        val stream = stream(maxConnections = 3, maxPerUser = 2)
        val a = stream.tryOpen(1)!!
        assertNotNull(stream.tryOpen(1))
        assertNull(stream.tryOpen(1), "사용자당 2")
        assertNotNull(stream.tryOpen(2))
        assertNull(stream.tryOpen(3), "전체 3")

        a.release()
        a.release()
        assertEquals(2, stream.openConnections(), "두 번 돌려줘도 한 번")
        assertNotNull(stream.tryOpen(3))
    }

    @Test
    fun `an empty board still opens with an empty snapshot and fills through deltas`() = runTest {
        val stream = stream()
        val events = mutableListOf<ServerSentEvent<MarketStream.Payload>>()
        backgroundScope.launch { stream.events(1).collect { events += it } }
        runCurrent()
        store.update(ticker("BTC/KRW", 1.0))
        advanceTimeBy(1_001)

        assertEquals(emptyMap<String, Double>(), events[0].prices())
        assertEquals(mapOf("KRW-BTC" to 1.0), events[1].prices())
    }

    // 웹 서버가 진행 중 요청을 기다리기 전에 스트림이 끝나야 한다 — 거꾸로면 열린 화면 하나가 종료를 30초 붙잡는다(로컬 실측 32.6초 → 2.9초).
    @Test
    fun `streams stop before the web server waits for open requests`() {
        assertEquals(true, MarketStream(store).phase > org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE)
    }
}
