package com.trading.bot.marketdata

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.config.MarketDataWatchdogProperties
import com.trading.bot.stream.CandleAggregator
import com.trading.common.config.PriceEventsProperties
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import com.trading.common.domain.NormalizedTrade
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

@OptIn(ExperimentalCoroutinesApi::class)
class MarketDataIngestionServiceTest {

    private val feed = mockk<UpbitMarketFeed>(relaxed = true)
    private val store = mockk<MarketDataStore>(relaxed = true)
    private val aggregator = mockk<CandleAggregator>(relaxed = true)
    private val subscription = MarketSubscription().also { every { store.subscription } returns it }
    private val clock = MutableClock(Instant.parse("2026-11-12T03:00:00Z"))
    private val service = MarketDataIngestionService(feed, store, aggregator, MarketDataWatchdogProperties(), PriceEventsProperties(), clock)
    private val bot = Any()

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val ticker = NormalizedTicker(exchange = Exchange.UPBIT, market = "BTC/KRW", price = 50_000_000.0)
    private val candle = NormalizedCandle(
        exchange = Exchange.UPBIT, market = "BTC/KRW",
        openPrice = 1.0, highPrice = 2.0, lowPrice = 0.5, closePrice = 1.5, volume = 10.0,
    )

    // store·집계기 한쪽의 실패가 다른 쪽 갱신이나 수집 코루틴(Flow collect)을 죽이면 안 된다.
    @Test
    fun `ingestTicker keeps the ticker flow alive when the store throws`() {
        every { store.updateTicker(any()) } throws RuntimeException("oom")

        assertDoesNotThrow { service.ingestTicker(ticker) }
    }

    @Test
    fun `ingestCandle updates store even when aggregation throws`() {
        every { aggregator.onMinuteCandle(any()) } throws RuntimeException("bad period")

        assertDoesNotThrow { service.ingestCandle(candle) }

        verify { store.addCandle(candle) }
    }

    @Test
    fun `ingestCandle still aggregates even when store throws`() {
        every { store.addCandle(any()) } throws RuntimeException("oom")

        assertDoesNotThrow { service.ingestCandle(candle) }

        verify { aggregator.onMinuteCandle(candle) }
    }

    // 부팅 때 store D1 버퍼를 과거 일봉으로 채워 warm-up 중 REST 폴백을 피한다.
    @Test
    fun `seedDailyCandles loads D1 candles into store`() = runBlocking {
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) } returns listOf(candle, candle)

        service.seedDailyCandles(listOf("BTC/KRW"))

        verify(exactly = 2) { store.addCandle(candle) }
    }

    // seed 의 오늘(UTC) 봉은 M1 이 이어서 집계할 period 라 prime 해야 첫 M1 이 seed 를 대체하지 않는다.
    // 응답 순서가 아니라 openTime 으로 고르는지 보려고 mock 은 오래된 봉을 앞에 둔다.
    @Test
    fun `seedDailyCandles primes the aggregator with today's seeded candle only`() = runBlocking {
        val today = clock.now.truncatedTo(ChronoUnit.DAYS)
        val todayCandle = candle.copy(openTime = today, closeTime = today.plusSeconds(86_400))
        val yesterday = candle.copy(openTime = today.minusSeconds(86_400), closeTime = today)
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) } returns listOf(yesterday, todayCandle)

        service.seedDailyCandles(listOf("BTC/KRW"))

        verify(exactly = 1) { aggregator.prime(todayCandle, any()) }
        verify(exactly = 0) { aggregator.prime(yesterday, any()) }
    }

    @Test
    fun `seedDailyCandles skips priming when today's candle is absent and still seeds the store`() = runBlocking {
        val yesterday = candle.copy(openTime = clock.now.truncatedTo(ChronoUnit.DAYS).minusSeconds(86_400))
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) } returns listOf(yesterday)

        service.seedDailyCandles(listOf("BTC/KRW"))

        verify(exactly = 1) { store.addCandle(yesterday) }
        verify(exactly = 0) { aggregator.prime(any(), any()) }
    }

    // 첫 라운드 꼬리가 어제 분봉을 실어 오면 상태 없는 어제 period 가 부분봉으로 store 의 seed 를 덮는다.
    // 그래서 바닥은 prime 이 아니라 seed 시점에 항상 걸고, fetch 가 실패해도 건다.
    @Test
    fun `seedDailyCandles raises the aggregation floor even without today's row and even when the fetch fails`() = runBlocking {
        val yesterday = candle.copy(openTime = clock.now.truncatedTo(ChronoUnit.DAYS).minusSeconds(86_400))
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) } returns listOf(yesterday)
        coEvery { feed.getCandles("ETH/KRW", CandleInterval.D1, any()) } throws RuntimeException("rate limit")

        service.seedDailyCandles(listOf("BTC/KRW", "ETH/KRW"))

        verify(exactly = 1) { aggregator.startFrom(Exchange.UPBIT, "BTC/KRW", any()) }
        verify(exactly = 1) { aggregator.startFrom(Exchange.UPBIT, "ETH/KRW", any()) }
    }

    @Test
    fun `seedDailyCandles isolates fetch failure across markets`() = runBlocking {
        coEvery { feed.getCandles("BTC/KRW", any(), any()) } throws RuntimeException("rate limit")
        val eth = candle.copy(market = "ETH/KRW")
        coEvery { feed.getCandles("ETH/KRW", CandleInterval.D1, any()) } returns listOf(eth)

        // 첫 market 실패가 둘째 market seed 를 막지 않는다.
        service.seedDailyCandles(listOf("BTC/KRW", "ETH/KRW"))

        verify { store.addCandle(eth) }
    }

    // 기동 seed 가 실패한 마켓은 그대로 두면 재시작 전까지 D1 이 하루 하나씩만 쌓여 전략이 21개를 못 채운다.
    @Test
    fun `a failed seed is tried again after its backoff and stops once it succeeds`() = runBlocking {
        val eth = candle.copy(market = "ETH/KRW")
        coEvery { feed.getCandles("ETH/KRW", CandleInterval.D1, any()) } throws RuntimeException("timeout") andThen listOf(eth)
        service.seedDailyCandles(listOf("ETH/KRW"))

        clock.now = clock.now.plusSeconds(30)
        service.retryFailedSeeds()
        coVerify(exactly = 1) { feed.getCandles("ETH/KRW", CandleInterval.D1, any()) }

        clock.now = clock.now.plusSeconds(30)
        service.retryFailedSeeds()
        verify { store.addCandle(eth) }

        clock.now = clock.now.plusSeconds(3_600)
        service.retryFailedSeeds()
        coVerify(exactly = 2) { feed.getCandles("ETH/KRW", CandleInterval.D1, any()) }
    }

    // 상장 폐지처럼 계속 실패하는 마켓이 매분 200개 요청과 WARN 을 보내지 않게 한다.
    @Test
    fun `a seed that keeps failing backs off up to thirty minutes and warns only once`() = runBlocking {
        coEvery { feed.getCandles("ETH/KRW", CandleInterval.D1, any()) } throws RuntimeException("404")
        val warns = warnsDuring {
            runBlocking {
                service.seedDailyCandles(listOf("ETH/KRW"))
                // 1·2·4·8·16·30·30 분 간격
                for (minutes in listOf(1L, 2, 4, 8, 16, 30, 30)) {
                    clock.now = clock.now.plusSeconds(minutes * 60 - 1)
                    service.retryFailedSeeds()
                    clock.now = clock.now.plusSeconds(1)
                    service.retryFailedSeeds()
                }
            }
        }

        coVerify(exactly = 8) { feed.getCandles("ETH/KRW", CandleInterval.D1, any()) }
        assertEquals(1, warns.count { "ETH/KRW" in it }, "$warns")
    }

    private inline fun warnsDuring(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(MarketDataIngestionService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    // Upbit candles 그룹은 초당 10회 제한이라 마켓을 지연 없이 연속 호출하면 429 가 난다.
    // 요청 간격은 virtual time 으로 본다. 벽시계로 재면 느리고 flaky 하다.

    @Test
    fun `collectCandlesRound spaces requests across markets`() = runTest {
        val markets = listOf("BTC/KRW", "ETH/KRW", "XRP/KRW", "SOL/KRW")
        coEvery { feed.getCandles(any(), CandleInterval.M1, any()) } returns listOf(candle)

        val start = testScheduler.currentTime
        service.collectCandlesRound(markets)
        val elapsed = testScheduler.currentTime - start

        // N개 마켓이면 최소 (N-1) 번의 간격이 있어야 한다.
        val minExpected = (markets.size - 1) * MarketDataIngestionService.CANDLE_REQUEST_SPACING_MS
        assertTrue(
            elapsed >= minExpected,
            "요청 간 spacing 없음: elapsed=${elapsed}ms, 기대 최소=${minExpected}ms",
        )
    }

    @Test
    fun `seedDailyCandles spaces requests across markets`() = runTest {
        val markets = listOf("BTC/KRW", "ETH/KRW", "XRP/KRW")
        coEvery { feed.getCandles(any(), CandleInterval.D1, any()) } returns listOf(candle)

        val start = testScheduler.currentTime
        service.seedDailyCandles(markets)
        val elapsed = testScheduler.currentTime - start

        val minExpected = (markets.size - 1) * MarketDataIngestionService.CANDLE_REQUEST_SPACING_MS
        assertTrue(
            elapsed >= minExpected,
            "seed 요청 간 spacing 없음: elapsed=${elapsed}ms, 기대 최소=${minExpected}ms",
        )
    }

    // spacing 을 둬도 다른 경로와 겹치거나 업비트가 순간 제한을 좁히면 429 가 날 수 있다.
    // 그때 그 마켓의 1분봉을 그냥 버리지 않고 한 번은 되찾아야 한다.
    @Test
    fun `collectCandlesRound retries once after 429`() = runTest {
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.M1, any()) }
            .throws(RuntimeException("429 Too Many Requests from GET https://api.upbit.com/v1/candles/minutes/1"))
            .andThen(listOf(candle))

        service.collectCandlesRound(listOf("BTC/KRW"))

        // 재시도가 성공해 store 에 반영된다.
        verify { store.addCandle(candle) }
    }

    // 봇이 뜰 때 엔진들의 REST 일봉 조회와 같은 IP 한도를 나눠 시드가 429 를 받는다. 1분 재시도까지 그 마켓은 체결 기반 매수 판단이 꺼진다.
    @Test
    fun `seedDailyCandles retries once after 429 instead of waiting for the backoff`() = runTest {
        val d1 = candle.copy(interval = CandleInterval.D1)
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) }
            .throws(RuntimeException("429 Too Many Requests from GET https://api.upbit.com/v1/candles/days"))
            .andThen(listOf(d1))

        service.seedDailyCandles(listOf("BTC/KRW"))

        verify { store.addCandle(d1) }
        clock.now = clock.now.plus(1, ChronoUnit.HOURS)
        service.retryFailedSeeds()
        coVerify(exactly = 2) { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) }
    }

    // Upbit 은 최신순으로 돌려주지만 집계기는 더 새 분봉이 오면 이전 분봉을 확정하므로 오름차순으로 넣어야 한다.
    // 내림차순이면 부분 봉이 확정되고 완결본은 오래된 봉으로 무시된다.
    @Test
    fun `collectCandlesRound ingests polled M1 candles oldest first and asks for the fetch count on both paths`() = runTest {
        val t = Instant.parse("2024-01-01T00:13:00Z")
        val newest = candle.copy(openTime = t, closeTime = t.plusSeconds(60))
        val middle = candle.copy(openTime = t.minusSeconds(60), closeTime = t)
        val oldest = candle.copy(openTime = t.minusSeconds(120), closeTime = t.minusSeconds(60))
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.M1, MarketDataIngestionService.M1_FETCH_COUNT) }
            .throws(RuntimeException("429 Too Many Requests"))
            .andThen(listOf(newest, middle, oldest))

        service.collectCandlesRound(listOf("BTC/KRW"))

        verifyOrder {
            store.addCandle(oldest)
            store.addCandle(middle)
            store.addCandle(newest)
        }
        coVerify(exactly = 2) { feed.getCandles("BTC/KRW", CandleInterval.M1, MarketDataIngestionService.M1_FETCH_COUNT) }
    }

    // 1 이면 진행 중 분봉만 와서 각 분의 완결본을 받지 못한다.
    @Test
    fun `M1 fetch count exceeds one so completed minutes arrive in the tail`() {
        assertTrue(MarketDataIngestionService.M1_FETCH_COUNT > 1)
    }

    // 429 가 아닌 오류는 원인이 사라지지 않으므로 재시도하지 않는다.
    @Test
    fun `collectCandlesRound does not retry non-rate-limit errors`() = runTest {
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.M1, any()) } throws RuntimeException("connection reset")

        service.collectCandlesRound(listOf("BTC/KRW"))

        coVerify(exactly = 1) { feed.getCandles("BTC/KRW", CandleInterval.M1, any()) }
    }

    /** 구독 집합을 바로 따르는 서비스(디바운스·재연결 최소 간격 없음). */
    private fun following(
        priceEvents: PriceEventsProperties = PriceEventsProperties(),
        watchdog: MarketDataWatchdogProperties = MarketDataWatchdogProperties(),
    ) = MarketDataIngestionService(feed, store, aggregator, watchdog, priceEvents, clock, subscriptionDebounceMs = 1, resubscribeMinIntervalMs = 0)

    @Test
    fun `스위치를 끄면 trade 를 구독하지 않는다`() {
        every { feed.marketFlow(any(), any(), any(), any()) } returns emptyFlow()
        coEvery { feed.getCandles(any(), any(), any()) } returns emptyList()
        val service = following(PriceEventsProperties(enabled = false))
        try {
            service.start()
            subscription.update(bot, listOf("KRW-BTC"))
            verify(timeout = 5_000) { feed.marketFlow(listOf("BTC/KRW"), false, any(), any()) }
        } finally {
            service.stop()
        }
    }

    // 지금 가격의 출처는 ticker 하나라 체결은 흐름에만 싣는다.
    @Test
    fun `체결은 저장소 체결 흐름으로만, 시세는 최신가로만 들어간다`() {
        val trade = NormalizedTrade(Exchange.UPBIT, "BTC/KRW", 51_000_000.0, 0.1, Instant.parse("2026-11-12T03:00:00Z"), 7)
        every { feed.marketFlow(any(), any(), any(), any()) } answers {
            thirdArg<(NormalizedTicker) -> Unit>().invoke(ticker)
            flowOf(trade)
        }
        coEvery { feed.getCandles(any(), any(), any()) } returns emptyList()
        val service = following()
        try {
            service.start()
            subscription.update(bot, listOf("KRW-BTC"))
            verify(timeout = 5_000) { store.publishTrade(trade) }
            verify(atLeast = 1) { store.updateTicker(ticker) }
        } finally {
            service.stop()
        }
    }

    @Test
    fun `체결 수신 요약은 마켓마다 받은 수·중복·피드 버림을 INFO 로 남긴다`() {
        every { store.drainTradeIntake() } returns mapOf("BTC/KRW" to TradeIntake(received = 1200, duplicates = 3))
        every { feed.drainDropped() } returns mapOf("BTC/KRW" to 2L)
        every { feed.drainParseFailures() } returns mapOf("ETH/KRW" to 4L)
        val logger = LoggerFactory.getLogger(MarketDataIngestionService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            service.logTradeIntake(listOf("BTC/KRW", "ETH/KRW"))
            val info = appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }
            assertEquals(1, info.size, info.toString())
            assertTrue(info.single().contains("BTC/KRW received=1200 duplicates=3 feedDropped=2 parseFailed=0"), info.single())
            // 한 건도 못 받은 마켓도 드러나야 수신이 끊긴 것을 안다.
            assertTrue(info.single().contains("ETH/KRW received=0 duplicates=0 feedDropped=0 parseFailed=4"), info.single())
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    // 워치독 재시작이 무수신 기준을 되돌리므로 장애가 길어져도 WARN 만 쌓이고 Discord(ERROR)에는 가지 않았다.
    @Test
    fun `시세가 5분 넘게 안 오면 장애 한 번에 ERROR 한 줄, 다시 받으면 INFO 한 줄을 남긴다`() {
        every { feed.marketFlow(any(), any(), any(), any()) } returns emptyFlow()
        coEvery { feed.getCandles(any(), any(), any()) } returns emptyList()
        val service = following()
        val logger = LoggerFactory.getLogger(MarketDataIngestionService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        fun errors() = appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }
        try {
            service.start()
            subscription.update(bot, listOf("KRW-BTC"))
            verify(timeout = 5_000) { feed.marketFlow(listOf("BTC/KRW"), true, any(), any()) }
            val startedAt = System.currentTimeMillis()

            service.checkTickerHealth(startedAt + 4 * 60_000)
            assertTrue(errors().isEmpty(), errors().toString())

            service.checkTickerHealth(startedAt + 6 * 60_000)
            service.checkTickerHealth(startedAt + 7 * 60_000)
            assertEquals(1, errors().size, errors().toString())
            assertTrue(errors().single().contains("[FEED DOWN]"), errors().single())

            service.ingestTicker(ticker)
            assertEquals(1, appender.list.count { it.level == Level.INFO && it.formattedMessage.contains("[FEED RECOVERED]") })
            service.ingestTicker(ticker)
            assertEquals(1, appender.list.count { it.level == Level.INFO && it.formattedMessage.contains("[FEED RECOVERED]") })

            service.checkTickerHealth(System.currentTimeMillis() + 6 * 60_000)
            assertEquals(2, errors().size, errors().toString())
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            service.stop()
        }
    }

    @Test
    fun `워치독을 꺼도 시세 장애 알림은 낸다`() {
        every { feed.marketFlow(any(), any(), any(), any()) } returns emptyFlow()
        coEvery { feed.getCandles(any(), any(), any()) } returns emptyList()
        val service = following(watchdog = MarketDataWatchdogProperties(enabled = false))
        val logger = LoggerFactory.getLogger(MarketDataIngestionService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            service.start()
            subscription.update(bot, listOf("KRW-BTC"))
            verify(timeout = 5_000) { feed.marketFlow(listOf("BTC/KRW"), true, any(), any()) }
            service.checkTickerHealth(System.currentTimeMillis() + 6 * 60_000)
            assertEquals(1, appender.list.count { it.level == Level.ERROR && it.formattedMessage.contains("[FEED DOWN]") })
            assertTrue(appender.list.none { it.formattedMessage.contains("restarting collection") })
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            service.stop()
        }
    }

    // --- 구독 집합을 따른다 ---

    private fun errorLog(block: (() -> List<String>) -> Unit) {
        val logger = LoggerFactory.getLogger(MarketDataIngestionService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block { appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage } }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `봇이 없으면 연결하지 않고 끊김 알림도 내지 않는다`() = errorLog { errors ->
        val service = following()
        try {
            service.start()
            Thread.sleep(200)
            service.checkTickerHealth(System.currentTimeMillis() + 60 * 60_000)

            verify(exactly = 0) { feed.marketFlow(any(), any(), any(), any()) }
            assertTrue(errors().isEmpty(), errors().toString())
        } finally {
            service.stop()
        }
    }

    @Test
    fun `구독 집합이 바뀌면 그 집합으로 다시 연결하고 같은 집합이면 다시 연결하지 않는다`() {
        every { feed.marketFlow(any(), any(), any(), any()) } returns flow { awaitCancellation() }
        coEvery { feed.getCandles(any(), any(), any()) } returns emptyList()
        val service = following()
        try {
            service.start()
            subscription.update(bot, listOf("KRW-BTC"))
            verify(timeout = 5_000) { feed.marketFlow(listOf("BTC/KRW"), true, any(), any()) }

            subscription.update(bot, listOf("KRW-ETH", "KRW-BTC"))
            verify(timeout = 5_000) { feed.marketFlow(listOf("BTC/KRW", "ETH/KRW"), true, any(), any()) }

            runBlocking { service.applySubscription() }
            verify(exactly = 2) { feed.marketFlow(any(), any(), any(), any()) }
        } finally {
            service.stop()
        }
    }

    // 누구의 시작·정지든 모든 사용자의 피드를 다시 연결하므로 몰린 변경은 한 번으로 모으고, 재연결 사이에 간격을 둔다.
    @Test
    fun `몰린 변경은 한 번의 재연결로 모이고 재연결 사이에 최소 간격을 둔다`() {
        val connectedAt = java.util.Collections.synchronizedList(mutableListOf<Long>())
        every { feed.marketFlow(any(), any(), any(), any()) } answers {
            connectedAt += System.currentTimeMillis()
            flow { awaitCancellation() }
        }
        coEvery { feed.getCandles(any(), any(), any()) } returns emptyList()
        val service = MarketDataIngestionService(
            feed, store, aggregator, MarketDataWatchdogProperties(), PriceEventsProperties(), clock,
            subscriptionDebounceMs = 200, resubscribeMinIntervalMs = 1_000,
        )
        try {
            service.start()
            subscription.update(bot, listOf("KRW-BTC"))
            subscription.update(Any(), listOf("KRW-ETH"))
            verify(timeout = 5_000) { feed.marketFlow(listOf("BTC/KRW", "ETH/KRW"), true, any(), any()) }
            verify(exactly = 1) { feed.marketFlow(any(), any(), any(), any()) }

            subscription.update(bot, listOf("KRW-BTC", "KRW-XRP"))
            verify(timeout = 5_000) { feed.marketFlow(listOf("BTC/KRW", "ETH/KRW", "XRP/KRW"), true, any(), any()) }
            // mockk 는 호출을 먼저 기록하고 답을 돌려 시각이 조금 늦게 들어온다.
            val deadline = System.currentTimeMillis() + 5_000
            while (connectedAt.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue(connectedAt.size >= 2, "두 번째 연결 시각이 없다: $connectedAt")
            // 연결 시각 사이로 잰다 — 시험 스레드가 늦게 깨어나도 흔들리지 않는다.
            assertTrue(connectedAt[1] - connectedAt[0] >= 1_000, "최소 간격 전에 다시 연결했다: $connectedAt")
        } finally {
            service.stop()
        }
    }

    // 워치독 재시작이 집합 변경 재시작과 겹쳐도 옛 집합으로 되돌아가거나 연결이 둘이 되면 안 된다.
    @Test
    fun `워치독 재시작과 집합 변경이 겹쳐도 새 집합의 흐름 하나만 남는다`() {
        val live = java.util.concurrent.atomic.AtomicInteger()
        val subscribed = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        every { feed.marketFlow(any(), any(), any(), any()) } answers {
            subscribed += firstArg<List<String>>()
            flow<NormalizedTrade> {
                live.incrementAndGet()
                try { awaitCancellation() } finally { live.decrementAndGet() }
            }
        }
        coEvery { feed.getCandles(any(), any(), any()) } returns emptyList()
        val service = following(watchdog = MarketDataWatchdogProperties(staleMs = 1))
        try {
            service.start()
            subscription.update(bot, listOf("KRW-BTC"))
            verify(timeout = 5_000) { feed.marketFlow(listOf("BTC/KRW"), true, any(), any()) }

            Thread.sleep(20)
            service.checkTickerHealth(System.currentTimeMillis())
            subscription.update(bot, listOf("KRW-ETH"))
            service.checkTickerHealth(System.currentTimeMillis())

            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && (subscribed.last() != listOf("ETH/KRW") || live.get() != 1)) Thread.sleep(20)
            Thread.sleep(200)
            assertEquals(listOf("ETH/KRW"), subscribed.last())
            assertEquals(1, live.get())
        } finally {
            service.stop()
        }
    }

    // 빈 집합 동안 시각이 멈춰 있어 종목이 다시 생기자마자 [FEED DOWN] 이 나거나, 앞선 장애의 알림 상태가 남아 새 장애를 못 알리면 안 된다.
    @Test
    fun `봇이 없다가 다시 생기면 끊김 판단을 처음부터 한다`() = errorLog { errors ->
        every { feed.marketFlow(any(), any(), any(), any()) } returns flow { awaitCancellation() }
        coEvery { feed.getCandles(any(), any(), any()) } returns emptyList()
        val service = following(watchdog = MarketDataWatchdogProperties(enabled = false))
        try {
            service.start()
            subscription.update(bot, listOf("KRW-BTC"))
            verify(timeout = 5_000) { feed.marketFlow(listOf("BTC/KRW"), true, any(), any()) }
            service.checkTickerHealth(System.currentTimeMillis() + 6 * 60_000)
            assertEquals(1, errors().size, errors().toString())

            subscription.remove(bot)
            Thread.sleep(200)
            subscription.update(bot, listOf("KRW-ETH"))
            verify(timeout = 5_000) { feed.marketFlow(listOf("ETH/KRW"), true, any(), any()) }

            service.checkTickerHealth(System.currentTimeMillis() + 4 * 60_000)
            assertEquals(1, errors().size, errors().toString())
            service.checkTickerHealth(System.currentTimeMillis() + 6 * 60_000)
            assertEquals(2, errors().size, errors().toString())
        } finally {
            service.stop()
        }
    }

    // PING 에 대한 UP 응답도 연결이 살아 있다는 뜻이다 — 거래가 뜸한 마켓만 구독해도 끊김으로 보지 않는다.
    @Test
    fun `UP 응답을 받으면 시세처럼 살아 있는 것으로 본다`() = errorLog { errors ->
        val alive = java.util.concurrent.atomic.AtomicReference<() -> Unit>()
        every { feed.marketFlow(any(), any(), any(), any()) } answers {
            alive.set(arg(3))
            flow { awaitCancellation() }
        }
        coEvery { feed.getCandles(any(), any(), any()) } returns emptyList()
        val service = following(watchdog = MarketDataWatchdogProperties(enabled = false))
        try {
            service.start()
            subscription.update(bot, listOf("KRW-BTC"))
            verify(timeout = 5_000) { feed.marketFlow(listOf("BTC/KRW"), true, any(), any()) }
            service.checkTickerHealth(System.currentTimeMillis() + 6 * 60_000)
            assertEquals(1, errors().size, errors().toString())

            alive.get().invoke()
            service.checkTickerHealth(System.currentTimeMillis() + 4 * 60_000)
            assertEquals(1, errors().size, errors().toString())
        } finally {
            service.stop()
        }
    }

    // 새 봇의 마켓 일봉이 늦으면 엔진이 그동안 REST 로 받는다. 분봉 주기(60초)를 기다리지 않고 바로 시드하고, 빠진 마켓은 바로 지운다.
    @Test
    fun `분봉 수집은 구독이 바뀌면 주기를 기다리지 않고 새 마켓을 시드하고 빠진 마켓을 지운다`() {
        every { feed.marketFlow(any(), any(), any(), any()) } returns flow { awaitCancellation() }
        coEvery { feed.getCandles(any(), any(), any()) } returns emptyList()
        val service = following()
        try {
            service.start()
            subscription.update(bot, listOf("KRW-BTC"))
            coVerify(timeout = 5_000) { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) }

            subscription.update(bot, listOf("KRW-ETH"))
            coVerify(timeout = 5_000) { feed.getCandles("ETH/KRW", CandleInterval.D1, any()) }
            verify(timeout = 5_000) { store.forget(Exchange.UPBIT, "BTC/KRW") }
        } finally {
            service.stop()
        }
    }

    // 빠졌다 다시 들어온 마켓이 멈춘 오늘 일봉을 '오늘' 로 남기면 매수 판단이 그 값을 쓴다. 빠질 때 지우고 들어오면 다시 시드한다.
    @Test
    fun `빠진 마켓은 저장소·집계기·재시도에서 지우고 다시 들어오면 다시 시드한다`() = runBlocking {
        coEvery { feed.getCandles("ETH/KRW", CandleInterval.D1, any()) } throws RuntimeException("down")
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) } returns emptyList()

        var collected = service.syncCandleMarkets(emptyList(), listOf("BTC/KRW", "ETH/KRW"))
        collected = service.syncCandleMarkets(collected, listOf("BTC/KRW"))
        verify { store.forget(Exchange.UPBIT, "ETH/KRW") }
        verify { aggregator.forget(Exchange.UPBIT, "ETH/KRW") }
        clock.now = clock.now.plus(1, ChronoUnit.HOURS)
        service.retryFailedSeeds()
        coVerify(exactly = 1) { feed.getCandles("ETH/KRW", CandleInterval.D1, any()) }

        service.syncCandleMarkets(collected, listOf("BTC/KRW", "ETH/KRW"))
        coVerify(exactly = 2) { feed.getCandles("ETH/KRW", CandleInterval.D1, any()) }
        coVerify(exactly = 1) { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) }
    }
}
