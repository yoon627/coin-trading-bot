package com.trading.bot.marketdata

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MarketCatalogTest {
    private var now = Instant.parse("2026-11-24T00:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private val btc = MarketListing("BTC/KRW", "비트코인", "Bitcoin")
    private val eth = MarketListing("ETH/KRW", "이더리움", "Ethereum")
    private val feed = mockk<UpbitMarketFeed>()
    private val catalog = MarketCatalog(feed, clock)

    private fun listings() = runBlocking { catalog.listings() }

    @Test
    fun `listings are fetched once and reused for a day`() {
        coEvery { feed.getKrwMarketListings() } returns listOf(btc) andThen listOf(btc, eth)

        assertEquals(listOf(btc), listings())
        now = now.plus(Duration.ofHours(23))
        assertEquals(listOf(btc), listings())
        now = now.plus(Duration.ofHours(2))
        assertEquals(listOf(btc, eth), listings())
        coVerify(exactly = 2) { feed.getKrwMarketListings() }
    }

    // 업비트 목록 장애 중에도 받아 둔 목록으로 검색·검증한다. 요청마다 다시 묻지 않고 1분 쉰다.
    @Test
    fun `a failed refresh keeps the old list and waits a minute before asking again`() {
        coEvery { feed.getKrwMarketListings() } returns listOf(btc) andThenThrows RuntimeException("down") andThen listOf(btc, eth)

        listings()
        now = now.plus(Duration.ofHours(25))
        assertEquals(listOf(btc), listings())
        now = now.plusSeconds(30)
        assertEquals(listOf(btc), listings())
        coVerify(exactly = 2) { feed.getKrwMarketListings() }
        now = now.plusSeconds(31)
        assertEquals(listOf(btc, eth), listings())
    }

    // 빈 목록은 받은 것으로 치지 않는다 — 모든 마켓을 '없는 마켓' 으로 거절하게 된다.
    @Test
    fun `with no list yet a failure or an empty answer gives null`() {
        coEvery { feed.getKrwMarketListings() } throws RuntimeException("down")
        assertNull(listings())

        now = now.plusSeconds(61)
        coEvery { feed.getKrwMarketListings() } returns emptyList()
        assertNull(listings())
    }

    @Test
    fun `unknown names the markets missing from the list, or null without a list`() {
        coEvery { feed.getKrwMarketListings() } throws RuntimeException("down")
        assertNull(runBlocking { catalog.unknown(listOf("KRW-BTC")) })

        now = now.plusSeconds(61)
        coEvery { feed.getKrwMarketListings() } returns listOf(btc, eth)
        assertEquals(listOf("KRW-NOPE"), runBlocking { catalog.unknown(listOf("KRW-BTC", "KRW-NOPE")) })
    }

    // 하루 캐시 사이에 새로 상장된 코인을 하루 동안 거절하지 않게, 받은 지 5분이 지난 목록은 없는 마켓이 오면 한 번 다시 받는다.
    @Test
    fun `an unknown market makes a list older than five minutes refresh once`() {
        coEvery { feed.getKrwMarketListings() } returns listOf(btc) andThen listOf(btc, eth)

        listings()
        now = now.plus(Duration.ofMinutes(4))
        assertEquals(listOf("KRW-ETH"), runBlocking { catalog.unknown(listOf("KRW-ETH")) })
        coVerify(exactly = 1) { feed.getKrwMarketListings() }

        now = now.plus(Duration.ofMinutes(2))
        assertEquals(emptyList<String>(), runBlocking { catalog.unknown(listOf("KRW-ETH")) })
        coVerify(exactly = 2) { feed.getKrwMarketListings() }
    }

    // 하루가 지나 다시 받는 동안(업비트가 느리면 최대 약 15초) 다른 요청은 기다리지 않고 받아 둔 목록을 쓴다.
    @Test
    fun `while a refresh is in flight other callers get the old list at once`() = runBlocking {
        val gate = CompletableDeferred<List<MarketListing>>()
        coEvery { feed.getKrwMarketListings() } returns listOf(btc) andThenAnswer { runBlocking { gate.await() } }
        listings()
        now = now.plus(Duration.ofHours(25))

        val refreshing = async(Dispatchers.Default) { catalog.listings() }
        withTimeout(5_000) { while (!catalog.refreshing()) yield() }
        assertEquals(listOf(btc), withTimeout(1_000) { catalog.listings() })

        gate.complete(listOf(btc, eth))
        assertEquals(listOf(btc, eth), refreshing.await())
    }
}
