package com.trading.bot.marketdata

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.common.domain.Exchange
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.reactive.function.client.WebClient

class UpbitMarketFeedParsingTest {

    private val feed = UpbitMarketFeed(mockk<WebClient>(relaxed = true), ObjectMapper())

    private fun ticker(json: String) = (feed.parseMessage(json) as? MarketEvent.Ticker)?.ticker

    private fun trade(json: String) = (feed.parseMessage(json) as? MarketEvent.Trade)?.trade

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
        val t = ticker(json)
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
        val t = ticker(json)
        assertNotNull(t); t!!
        assertEquals("ETH/KRW", t.market)
        assertEquals(3000000.0, t.price)
        assertEquals(0.0, t.changeRate24h)
        assertEquals(0.0, t.volume24h)
        assertEquals(0.0, t.bidPrice)
    }

    @Test
    fun `returns null for an unsubscribed frame type`() {
        assertNull(feed.parseMessage("""{"type":"orderbook","code":"KRW-BTC"}"""))
    }

    @Test
    fun `returns null for malformed json`() {
        assertNull(ticker("""{"type":"ticker" oops not json"""))
    }

    @Test
    fun `interprets timestamp as epoch millis`() {
        val json = """{"type":"ticker","code":"KRW-BTC","trade_price":1.0,"timestamp":1609459200000}"""
        val t = ticker(json)
        assertEquals(1609459200000, t!!.timestamp.toEpochMilli())
    }

    @Test
    fun `parses a trade frame into a normalized trade`() {
        val json = """
            {"type":"trade","code":"KRW-BTC","trade_price":50000000.0,"trade_volume":0.0123,
             "trade_timestamp":1700000000123,"sequential_id":17000000001230000,"ask_bid":"BID",
             "stream_type":"REALTIME","timestamp":1700000000200}
        """.trimIndent()
        val t = trade(json)
        assertNotNull(t); t!!
        assertEquals(Exchange.UPBIT, t.exchange)
        assertEquals("BTC/KRW", t.market)
        assertEquals(50000000.0, t.price)
        assertEquals(0.0123, t.volume)
        assertEquals(1700000000123, t.tradeTime.toEpochMilli())
        assertEquals(17000000001230000L, t.sequentialId)
    }

    // 체결 번호가 없으면 중복을 가릴 수 없고, 체결 시각이 없으면 낡은 체결을 가릴 수 없다.
    @Test
    fun `drops a trade frame missing its sequential id or trade time`() {
        assertNull(feed.parseMessage("""{"type":"trade","code":"KRW-BTC","trade_price":1.0,"trade_volume":1.0,"trade_timestamp":1}"""))
        assertNull(feed.parseMessage("""{"type":"trade","code":"KRW-BTC","trade_price":1.0,"trade_volume":1.0,"sequential_id":1}"""))
    }

    // 형식이 바뀌면 체결마다(마켓당 초당 수십 건) WARN 이 쌓이므로 세기만 하고 1분에 한 번 알린다.
    @Test
    fun `counts malformed frames per market and warns at most once a minute`() {
        val bad = """{"type":"trade","code":"KRW-BTC","trade_price":1.0,"trade_volume":1.0,"trade_timestamp":1}"""
        val logger = LoggerFactory.getLogger(UpbitMarketFeed::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        fun warns() = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        try {
            repeat(5) { assertNull(feed.parseMessage(bad, nowMs = 1_000L + it)) }
            assertEquals(1, warns().size, warns().toString())
            assertNull(feed.parseMessage(bad, nowMs = 61_000))
            assertEquals(2, warns().size, warns().toString())
            assertTrue(warns()[1].contains("5 malformed"), warns()[1])
            assertEquals(mapOf("BTC/KRW" to 6L), feed.drainParseFailures())
            assertEquals(emptyMap<String, Long>(), feed.drainParseFailures())
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `subscribes to trades on the same request only when asked`() {
        fun types(includeTrades: Boolean): List<Pair<String, List<String>>> {
            val request = ObjectMapper().readTree(feed.buildSubscriptionMessage(listOf("BTC/KRW", "ETH/KRW"), includeTrades))
            return request.filter { it.has("type") }.map { node -> node["type"].asText() to node["codes"].map { it.asText() } }
        }
        val codes = listOf("KRW-BTC", "KRW-ETH")
        assertEquals(listOf("ticker" to codes, "trade" to codes), types(includeTrades = true))
        assertEquals(listOf("ticker" to codes), types(includeTrades = false))
        // ticker 는 구독 때 스냅샷을 받아 거래가 뜸한 마켓도 이 연결이 시세를 받는지 보이고, 체결은 실시간만 받는다.
        val request = ObjectMapper().readTree(feed.buildSubscriptionMessage(listOf("BTC/KRW"), true)).filter { it.has("type") }
        assertEquals(listOf(false, true), request.map { it["isOnlyRealtime"]?.asBoolean() ?: false })
    }

    @Test
    fun `an UP frame is a sign of life and an error frame is counted, not dropped silently`() {
        assertEquals(MarketEvent.Alive, feed.parseMessage("""{"status":"UP"}"""))
        val logger = LoggerFactory.getLogger(UpbitMarketFeed::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            assertNull(feed.parseMessage("""{"error":{"name":"WRONG_FORMAT","message":"bad"}}""", nowMs = 0))
            val warn = appender.list.single { it.level == Level.WARN }.formattedMessage
            assertTrue(warn.contains("WRONG_FORMAT"), warn)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `counts events the collector could not take and warns at most once a minute`() {
        val logger = LoggerFactory.getLogger(UpbitMarketFeed::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        fun warns() = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        try {
            feed.recordDropped("BTC/KRW", nowMs = 0)
            repeat(4) { feed.recordDropped("BTC/KRW", nowMs = 30_000) }
            feed.recordDropped("ETH/KRW", nowMs = 59_999)
            assertEquals(1, warns().size, warns().toString())
            feed.recordDropped("BTC/KRW", nowMs = 60_000)
            assertEquals(2, warns().size, warns().toString())
            assertTrue(warns()[0].contains("dropped 1 "), warns()[0])
            assertTrue(warns()[1].contains("dropped 6 "), warns()[1])
            assertEquals(mapOf("BTC/KRW" to 6L, "ETH/KRW" to 1L), feed.drainDropped())
            assertEquals(emptyMap<String, Long>(), feed.drainDropped())
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
