package com.trading.bot.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.common.domain.Exchange
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.reactive.function.client.WebClient

class UpbitMarketFeedParsingTest {

    private val feed = UpbitMarketFeed(mockk<WebClient>(relaxed = true), ObjectMapper())

    // 생성자가 둘이라 이름이 틀리거나 @Autowired 가 빠지면 배포에서야 드러난다. WebClient 빈이 둘이다.
    @Test
    fun `the bean is created with the Upbit client among two WebClients`() {
        val upbitClient = WebClient.create()
        ApplicationContextRunner()
            .withBean("upbitWebClient", WebClient::class.java, { upbitClient })
            .withBean("discordWebClient", WebClient::class.java, { WebClient.create() })
            .withBean(ObjectMapper::class.java)
            .withBean(UpbitMarketFeed::class.java)
            .run { context ->
                assertNull(context.startupFailure)
                val created = context.getBean(UpbitMarketFeed::class.java)
                val field = UpbitMarketFeed::class.java.getDeclaredField("upbitWebClient").apply { isAccessible = true }
                assertSame(upbitClient, field.get(created))
            }
    }

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
        assertEquals("BTC/KRW", t.market)
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
        assertEquals(1609459200000, t!!.timestamp.toEpochMilli())
    }
}
