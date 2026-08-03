package com.trading.bot.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.common.domain.Exchange
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class UpbitMarketFeedParsingTest {

    private val feed = UpbitMarketFeed(mockk<WebClient>(relaxed = true), ObjectMapper())

    @Test
    fun `parses a valid ticker frame`() {
        val json = """
            {"type":"ticker","code":"KRW-BTC","trade_price":50000000.0,
             "signed_change_rate":0.012,"acc_trade_price_24h":1.0e12,"acc_trade_volume_24h":1234.5,
             "high_price":51000000.0,"low_price":49000000.0,"timestamp":1700000000000}
        """.trimIndent()
        val t = feed.parseTickerMessage(json)
        assertNotNull(t); t!!
        assertEquals(Exchange.UPBIT, t.exchange)
        assertEquals("BTC/KRW", t.market) // MarketPair.normalize: Upbit "KRW-BTC" → 내부 "BTC/KRW"
        assertEquals(50000000.0, t.price)
        assertEquals(0.012, t.changeRate24h)
        assertEquals(1234.5, t.volume24h)
        assertEquals(51000000.0, t.highPrice24h)
        assertEquals(1700000000000, t.timestamp.toEpochMilli())
    }

    @Test
    fun `defaults optional fields when absent`() {
        val json = """{"type":"ticker","code":"KRW-ETH","trade_price":3000000.0}"""
        val t = feed.parseTickerMessage(json)
        assertNotNull(t); t!!
        assertEquals("ETH/KRW", t.market)
        assertEquals(3000000.0, t.price)
        assertEquals(0.0, t.changeRate24h)
        assertEquals(0.0, t.volume24h)
        assertEquals(0.0, t.bidPrice)
    }

    @Test
    fun `returns null for non-ticker frame`() {
        assertNull(feed.parseTickerMessage("""{"type":"trade","code":"KRW-BTC","trade_price":1.0}"""))
    }

    @Test
    fun `returns null for malformed json`() {
        assertNull(feed.parseTickerMessage("""{"type":"ticker" oops not json"""))
    }

    @Test
    fun `interprets timestamp as epoch millis`() {
        val json = """{"type":"ticker","code":"KRW-BTC","trade_price":1.0,"timestamp":1609459200000}"""
        val t = feed.parseTickerMessage(json)
        assertEquals(1609459200000, t!!.timestamp.toEpochMilli()) // 2021-01-01T00:00:00Z
    }
}
