package com.trading.bot.marketdata

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.config.MarketDataWatchdogProperties
import com.trading.bot.config.WatchlistProperties
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.notification.DiscordErrorLogAppender
import com.trading.bot.stream.CandleAggregator
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Instant
import java.time.temporal.ChronoUnit
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.annotation.Order
import org.springframework.core.annotation.OrderUtils

@OptIn(ExperimentalCoroutinesApi::class)
class MarketDataIngestionServiceTest {

    private val feed = mockk<UpbitMarketFeed>(relaxed = true)
    private val store = mockk<MarketDataStore>(relaxed = true)
    private val aggregator = mockk<CandleAggregator>(relaxed = true)
    private val watchlist = mockk<WatchlistProperties>(relaxed = true)
    private val service = MarketDataIngestionService(feed, store, aggregator, watchlist, MarketDataWatchdogProperties())

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
        val today = Instant.now().truncatedTo(ChronoUnit.DAYS)
        val todayCandle = candle.copy(openTime = today, closeTime = today.plusSeconds(86_400))
        val yesterday = candle.copy(openTime = today.minusSeconds(86_400), closeTime = today)
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) } returns listOf(yesterday, todayCandle)

        service.seedDailyCandles(listOf("BTC/KRW"))

        verify(exactly = 1) { aggregator.prime(todayCandle, any()) }
        verify(exactly = 0) { aggregator.prime(yesterday, any()) }
    }

    @Test
    fun `seedDailyCandles skips priming when today's candle is absent and still seeds the store`() = runBlocking {
        val yesterday = candle.copy(openTime = Instant.now().truncatedTo(ChronoUnit.DAYS).minusSeconds(86_400))
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) } returns listOf(yesterday)

        service.seedDailyCandles(listOf("BTC/KRW"))

        verify(exactly = 1) { store.addCandle(yesterday) }
        verify(exactly = 0) { aggregator.prime(any(), any()) }
    }

    // 첫 라운드 꼬리가 어제 분봉을 실어 오면 상태 없는 어제 period 가 부분봉으로 store 의 seed 를 덮는다.
    // 그래서 바닥은 prime 이 아니라 seed 시점에 항상 걸고, fetch 가 실패해도 건다.
    @Test
    fun `seedDailyCandles raises the aggregation floor even without today's row and even when the fetch fails`() = runBlocking {
        val yesterday = candle.copy(openTime = Instant.now().truncatedTo(ChronoUnit.DAYS).minusSeconds(86_400))
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

    private fun serviceWatching(tickers: String) =
        MarketDataIngestionService(feed, store, aggregator, WatchlistProperties(tickers), MarketDataWatchdogProperties())

    // KRW_BTC 같은 항목이 섞이면 구독 메시지를 만들다 예외가 나 모든 마켓의 시세가 끊긴다.
    @Test
    fun `형식이 틀린 watchlist 항목은 빼고 나머지 마켓만 구독한다`() {
        every { feed.tickerFlow(any()) } returns emptyFlow()
        coEvery { feed.getCandles(any(), any(), any()) } returns emptyList()
        val service = serviceWatching("KRW-BTC,KRW_BTC,krw-t")
        try {
            service.start()
            verify(timeout = 5_000) { feed.tickerFlow(listOf("BTC/KRW", "T/KRW")) }
        } finally {
            service.stop()
        }
    }

    @Test
    fun `빠진 watchlist 항목은 기동 뒤 ERROR 한 줄로 알리고 모두 맞으면 알리지 않는다`() {
        val errors = errorsWhileReporting(serviceWatching("KRW-BTC,KRW_BTC,K-BTC"))
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("KRW_BTC") && errors.single().contains("K-BTC"), errors.single())
        assertTrue(errors.single().contains("빼고 시세를 수집합니다"), errors.single())
        assertTrue(errorsWhileReporting(serviceWatching("KRW-BTC,KRW-T")).isEmpty())
        // 남는 마켓이 없으면 수집이 꺼진다고 알린다. start() 의 WARN 은 Discord 에 가지 않는다.
        val allInvalid = errorsWhileReporting(serviceWatching("KRW-BTC KRW-ETH")).single()
        assertTrue(allInvalid.contains("시세 수집을 끕니다"), allInvalid)
        // ERROR 는 Discord 로 나가므로 값을 통째로 붙여 넣은 항목은 잘라서 싣는다.
        val pasted = errorsWhileReporting(serviceWatching("KRW-BTC," + "A".repeat(500))).single()
        assertTrue(pasted.contains("A".repeat(40)) && !pasted.contains("A".repeat(41)), pasted)
    }

    // @PostConstruct 는 Discord appender attach 전이라 ERROR 가 Discord 에 가지 않는다.
    @Test
    fun `watchlist 보고는 Discord appender 뒤·복원 앞에 도는 ApplicationReadyEvent 리스너다`() {
        val report = MarketDataIngestionService::class.java.getMethod("reportInvalidWatchlist")
        val listener = AnnotatedElementUtils.findMergedAnnotation(report, EventListener::class.java)
        assertEquals(listOf(ApplicationReadyEvent::class.java), listener?.classes?.map { it.java })
        assertTrue(!report.isAnnotationPresent(PostConstruct::class.java))
        val appenderOrder = OrderUtils.getOrder(DiscordErrorLogAppender::class.java)!!
        val reportOrder = AnnotatedElementUtils.findMergedAnnotation(report, Order::class.java)!!.value
        val restore = UserTradingManager::class.java.getMethod("restoreOnStartup")
        val restoreOrder = AnnotatedElementUtils.findMergedAnnotation(restore, Order::class.java)!!.value
        assertTrue(reportOrder in (appenderOrder + 1) until restoreOrder, "appender $appenderOrder < report $reportOrder < restore $restoreOrder")
    }

    private fun errorsWhileReporting(service: MarketDataIngestionService): List<String> {
        val logger = LoggerFactory.getLogger(MarketDataIngestionService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            service.reportInvalidWatchlist()
            appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
