package com.trading.bot.marketdata

import com.trading.bot.config.MarketBoardProperties
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedTicker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 시세판 전용 연결: KRW 목록 전체의 ticker 만 받고, 목록은 하루 한 번(KST 04:00) 다시 받는다. 봇 연결과는 따로다. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarketBoardTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val feed = mockk<UpbitMarketFeed>()
    private val store = MarketBoardStore()
    private val clock = MutableClock(Instant.parse("2026-11-24T10:00:00Z"))
    private val onTicker = mutableListOf<(NormalizedTicker) -> Unit>()
    private val onAlive = mutableListOf<() -> Unit>()

    private fun TestScope.board(enabled: Boolean = true) =
        MarketBoard(feed, store, MarketBoardProperties(enabled = enabled), clock, backgroundScope)

    private fun ticker(market: String, price: Double = 100.0) = NormalizedTicker(Exchange.UPBIT, market, price)

    init {
        every { feed.marketFlow(any(), false, any(), any()) } answers {
            onTicker += thirdArg<(NormalizedTicker) -> Unit>()
            onAlive += arg<() -> Unit>(3)
            flow { awaitCancellation() }
        }
    }

    @Test
    fun `it subscribes the whole catalog to tickers only and keeps the latest price per market`() = runTest {
        coEvery { feed.getKrwMarkets() } returns listOf("BTC/KRW", "ETH/KRW")
        board().start()
        runCurrent()

        verify(exactly = 1) { feed.marketFlow(listOf("BTC/KRW", "ETH/KRW"), false, any(), any()) }
        onTicker.single()(ticker("BTC/KRW", 1.0))
        onTicker.single()(ticker("BTC/KRW", 2.0))
        assertEquals(2.0, store.get("BTC/KRW")?.price)
    }

    @Test
    fun `a failed catalog fetch is retried from one minute, doubling up to thirty`() = runTest {
        var calls = 0
        coEvery { feed.getKrwMarkets() } answers { if (++calls < 4) throw RuntimeException("429") else listOf("BTC/KRW") }
        board().start()
        runCurrent()
        assertEquals(1, calls)

        advanceTimeBy(Duration.ofMinutes(1).toMillis() + 1)
        assertEquals(2, calls)
        advanceTimeBy(Duration.ofMinutes(2).toMillis())
        assertEquals(3, calls)
        advanceTimeBy(Duration.ofMinutes(4).toMillis())
        assertEquals(4, calls)
        verify(exactly = 1) { feed.marketFlow(listOf("BTC/KRW"), false, any(), any()) }
    }

    @Test
    fun `an empty catalog counts as a failure and never replaces a good one`() = runTest {
        coEvery { feed.getKrwMarkets() } returns emptyList() andThen listOf("BTC/KRW") andThen emptyList()
        val board = board()
        board.start()
        runCurrent()
        verify(exactly = 0) { feed.marketFlow(any(), any(), any(), any()) }

        advanceTimeBy(Duration.ofMinutes(1).toMillis() + 1)
        verify(exactly = 1) { feed.marketFlow(listOf("BTC/KRW"), false, any(), any()) }

        board.refresh()
        runCurrent()
        verify(exactly = 1) { feed.marketFlow(any(), any(), any(), any()) }
    }

    @Test
    fun `a changed catalog reconnects once and forgets the markets that left`() = runTest {
        coEvery { feed.getKrwMarkets() } returns listOf("BTC/KRW", "OLD/KRW") andThen listOf("BTC/KRW", "NEW/KRW")
        val board = board()
        board.start()
        runCurrent()
        onTicker.single()(ticker("OLD/KRW"))
        onTicker.single()(ticker("BTC/KRW"))

        board.refresh()
        runCurrent()

        verify(exactly = 1) { feed.marketFlow(listOf("BTC/KRW", "NEW/KRW"), false, any(), any()) }
        assertNull(store.get("OLD/KRW"))
        assertEquals(100.0, store.get("BTC/KRW")?.price)
    }

    @Test
    fun `an unchanged catalog does not reconnect`() = runTest {
        coEvery { feed.getKrwMarkets() } returns listOf("BTC/KRW")
        val board = board()
        board.start()
        runCurrent()

        board.refresh()
        runCurrent()

        verify(exactly = 1) { feed.marketFlow(any(), any(), any(), any()) }
    }

    @Test
    fun `the catalog is fetched again at the next 04 00 KST`() = runTest {
        // 10:00 UTC = 19:00 KST → 다음 04:00 KST = 다음 날 19:00 UTC. 9시간 뒤.
        assertEquals(Duration.ofHours(9), board().untilNextRefresh(clock.now))
        assertEquals(Duration.ofDays(1), board().untilNextRefresh(Instant.parse("2026-11-24T19:00:00Z")))
    }

    @Test
    fun `a board that receives nothing for two minutes reconnects`() = runTest {
        coEvery { feed.getKrwMarkets() } returns listOf("BTC/KRW")
        board().start()
        runCurrent()
        onTicker.single()(ticker("BTC/KRW"))

        clock.now = clock.now.plusSeconds(121)
        advanceTimeBy(MarketBoard.STALE_CHECK_INTERVAL.toMillis() + 1)

        verify(exactly = 2) { feed.marketFlow(listOf("BTC/KRW"), false, any(), any()) }
    }

    @Test
    fun `a disabled board never connects`() = runTest {
        board(enabled = false).start()
        runCurrent()

        coVerify(exactly = 0) { feed.getKrwMarkets() }
        verify(exactly = 0) { feed.marketFlow(any(), any(), any(), any()) }
    }

    @Test
    fun `the catalog retry wait stops growing at thirty minutes`() = runTest {
        var calls = 0
        coEvery { feed.getKrwMarkets() } answers { calls++; throw RuntimeException("down") }
        board().start()
        runCurrent()
        // 1 + 2 + 4 + 8 + 16 = 31분 뒤 여섯 번째, 그다음은 32분이 아니라 30분 뒤.
        advanceTimeBy(Duration.ofMinutes(31).toMillis() + 1)
        assertEquals(6, calls)

        advanceTimeBy(Duration.ofMinutes(30).toMillis())
        assertEquals(7, calls)
    }

    @Test
    fun `UP answers keep a board without trades alive and fresh data never reconnects`() = runTest {
        coEvery { feed.getKrwMarkets() } returns listOf("BTC/KRW")
        board().start()
        runCurrent()

        // UP 뒤 100초 — 120초 기준 안쪽이라 다시 열지 않는다.
        repeat(5) {
            onAlive.single()()
            clock.now = clock.now.plusSeconds(100)
            advanceTimeBy(MarketBoard.STALE_CHECK_INTERVAL.toMillis())
        }

        verify(exactly = 1) { feed.marketFlow(any(), any(), any(), any()) }
    }

    @Test
    fun `a feed flow that ends or throws is opened again`() = runTest {
        coEvery { feed.getKrwMarkets() } returns listOf("BTC/KRW")
        var opened = 0
        every { feed.marketFlow(any(), false, any(), any()) } answers {
            opened++
            if (opened == 1) flow<Nothing> { throw RuntimeException("boom") } else flow { awaitCancellation() }
        }
        board().start()
        runCurrent()

        advanceTimeBy(1_001)

        assertEquals(2, opened)
    }

    // 생성자가 둘이고 WebClient 빈이 둘이다. 이름이 틀리면 배포에서야 드러난다.
    @Test
    fun `the bean is created with the Upbit client among two WebClients`() {
        org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withBean("upbitWebClient", org.springframework.web.reactive.function.client.WebClient::class.java, { org.springframework.web.reactive.function.client.WebClient.create() })
            .withBean("discordWebClient", org.springframework.web.reactive.function.client.WebClient::class.java, { org.springframework.web.reactive.function.client.WebClient.create() })
            .withBean(com.fasterxml.jackson.databind.ObjectMapper::class.java)
            .withBean(MarketBoardStore::class.java)
            .withBean(MarketBoardProperties::class.java, { MarketBoardProperties(enabled = false) })
            .withBean(MarketBoard::class.java)
            .run { ctx -> org.assertj.core.api.Assertions.assertThat(ctx).hasNotFailed().hasSingleBean(MarketBoard::class.java) }
    }
}
