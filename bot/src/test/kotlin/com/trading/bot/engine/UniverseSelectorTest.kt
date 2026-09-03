package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.MarketEvent
import com.trading.bot.domain.MarketInfo
import com.trading.bot.domain.Ticker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UniverseSelectorTest {

    private val markets = listOf(
        MarketInfo(market = "KRW-A", marketEvent = MarketEvent(warning = true)),
        MarketInfo(market = "KRW-B"),
        MarketInfo(market = "KRW-USDT"),
        MarketInfo(market = "BTC-C"),
        MarketInfo(market = "KRW-D"),
        MarketInfo(market = "KRW-E"), // 시세 응답에 없음
    )
    private val tickers = listOf(
        Ticker(market = "KRW-A", accTradePrice24h = 100.0),
        Ticker(market = "KRW-B", accTradePrice24h = 50.0),
        Ticker(market = "KRW-USDT", accTradePrice24h = 500.0),
        Ticker(market = "BTC-C", accTradePrice24h = 900.0),
        Ticker(market = "KRW-D", accTradePrice24h = 70.0),
    )

    @Test
    fun `rank keeps KRW markets without warning or peg, by 24h trade value`() {
        assertEquals(listOf("KRW-D", "KRW-B"), UniverseSelector.rank(markets, tickers))
    }

    private fun selector(client: UpbitClient, clock: Clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC)) =
        UniverseSelector(client, clock)

    @Test
    fun `select excludes the given tickers and caps the count`() = runTest {
        val client = mockk<UpbitClient>()
        coEvery { client.getMarkets() } returns markets
        coEvery { client.getTicker(any()) } returns tickers

        assertEquals(listOf("KRW-B"), selector(client).select(exclude = setOf("KRW-D"), count = 5))
        assertEquals(listOf("KRW-D"), selector(client).select(exclude = emptySet(), count = 1))
    }

    @Test
    fun `select reuses the snapshot within the ttl`() = runTest {
        val client = mockk<UpbitClient>()
        coEvery { client.getMarkets() } returns markets
        coEvery { client.getTicker(any()) } returns tickers
        val selector = selector(client)

        selector.select(emptySet(), 2)
        selector.select(emptySet(), 2)

        coVerify(exactly = 1) { client.getMarkets() }
    }

    @Test
    fun `select returns null instead of a partial ranking when the exchange call fails`() = runTest {
        val client = mockk<UpbitClient>()
        coEvery { client.getMarkets() } throws RuntimeException("upbit down")

        assertNull(selector(client).select(emptySet(), 2))
    }
}
