package com.trading.bot.marketdata

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.config.MarketDataWatchdogProperties
import com.trading.bot.config.WatchlistProperties
import com.trading.bot.stream.CandleAggregator
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * 분봉으로 만든 전일 D1 을 업비트 일봉과 대조하는 경로를 실제 [MarketDataStore]·[CandleAggregator] 로 본다.
 * 교체 뒤 늦게 온 전일 분봉이 집계값으로 store 를 되덮지 않는지는 실제 집계기로만 보인다.
 */
class DailyCandleReconcileTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val day = Instant.parse("2026-11-12T00:00:00Z")
    private val yesterday = day.minus(1, ChronoUnit.DAYS)
    private val clock = MutableClock(yesterday.plusSeconds(86_340))
    private val feed = mockk<UpbitMarketFeed>(relaxed = true)
    private val store = MarketDataStore()
    private val service = MarketDataIngestionService(
        feed, store, CandleAggregator(store), mockk<WatchlistProperties>(relaxed = true), MarketDataWatchdogProperties(), clock,
    )

    private val logger = LoggerFactory.getLogger(MarketDataIngestionService::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun attach() {
        logger.addAppender(logs)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(logs)
    }

    private fun logged(level: Level) = logs.list.filter { it.level == level }.map { it.formattedMessage }

    private fun minute(openTime: Instant, high: Double, low: Double, close: Double) = NormalizedCandle(
        exchange = Exchange.UPBIT, market = MARKET, openPrice = 100.0, highPrice = high, lowPrice = low, closePrice = close,
        volume = 1.0, interval = CandleInterval.M1, openTime = openTime, closeTime = openTime.plusSeconds(60),
    )

    private fun upbitDaily(high: Double, low: Double, close: Double, volume: Double = 2.0) = NormalizedCandle(
        exchange = Exchange.UPBIT, market = MARKET, openPrice = 100.0, highPrice = high, lowPrice = low, closePrice = close,
        volume = volume, interval = CandleInterval.D1, openTime = yesterday, closeTime = day,
    )

    // 23:58·23:59 분봉으로 어제 D1 이 (100, 120, 90, 110, 2.0) 으로 집계된 상태.
    private val lateMinute = minute(yesterday.plusSeconds(86_340), 120.0, 95.0, 110.0)

    private fun aggregateYesterday() = runBlocking {
        coEvery { feed.getCandles(MARKET, CandleInterval.M1, any()) } returns listOf(lateMinute, minute(yesterday.plusSeconds(86_280), 105.0, 90.0, 100.0))
        service.collectCandlesRound(listOf(MARKET))
    }

    private fun upbitSays(vararg candles: NormalizedCandle) {
        coEvery { feed.getCandles(MARKET, CandleInterval.D1, any()) } returns candles.toList()
    }

    private fun reconcileAt(at: Instant) = runBlocking {
        clock.now = at
        service.reconcilePreviousDay(listOf(MARKET))
    }

    // 늦게 온 23:59 분봉 재전송은 집계기가 같은 period 를 다시 publish 하는 경로다.
    private fun resendLateMinute() = runBlocking {
        coEvery { feed.getCandles(MARKET, CandleInterval.M1, any()) } returns listOf(minute(day.plusSeconds(180), 101.0, 100.0, 101.0), lateMinute)
        service.collectCandlesRound(listOf(MARKET))
    }

    private fun storedYesterday(): NormalizedCandle? =
        store.getCandles(Exchange.UPBIT, MARKET, CandleInterval.D1, 10).firstOrNull { it.openTime == yesterday }

    private fun prices(c: NormalizedCandle?) = c?.let { listOf(it.openPrice, it.highPrice, it.lowPrice, it.closePrice) }

    @Test
    fun `nothing is checked until two minutes past the day boundary, then a matching day is done without alerts`() {
        aggregateYesterday()
        upbitSays(upbitDaily(120.0, 90.0, 110.0))

        reconcileAt(day.plusSeconds(119))
        coVerify(exactly = 0) { feed.getCandles(MARKET, CandleInterval.D1, any()) }

        reconcileAt(day.plusSeconds(120))
        reconcileAt(day.plusSeconds(180))
        coVerify(exactly = 1) { feed.getCandles(MARKET, CandleInterval.D1, any()) }
        assertEquals(emptyList<String>(), logged(Level.ERROR) + logged(Level.WARN))
    }

    // 미확정 봉 한 번으로 맞는 집계값을 덮고 그날 고정하지 않는다. 두 번 같은 값일 때만 바꾸고, 늦은 분봉이 되덮지 않는다.
    @Test
    fun `a price mismatch is replaced only when a later read agrees, and late minutes cannot overwrite it`() {
        aggregateYesterday()
        val upbit = upbitDaily(125.0, 90.0, 112.0)
        upbitSays(upbit)

        reconcileAt(day.plusSeconds(150))
        assertEquals(listOf(100.0, 120.0, 90.0, 110.0), prices(storedYesterday()), "첫 읽기로는 바꾸지 않는다")

        reconcileAt(day.plusSeconds(210))
        assertEquals(prices(upbit), prices(storedYesterday()))
        assertEquals(1, logged(Level.ERROR).size, logged(Level.ERROR).toString())
        assertEquals(1, logged(Level.WARN).count { "KRW-BTC" in it || MARKET in it }, logged(Level.WARN).toString())

        resendLateMinute()
        assertEquals(prices(upbit), prices(storedYesterday()), "늦은 23:59 분봉이 집계값으로 되덮었다")
    }

    @Test
    fun `a mismatch that goes away on the next read was an unfinished upbit candle and changes nothing`() {
        aggregateYesterday()
        upbitSays(upbitDaily(120.0, 90.0, 108.0))
        reconcileAt(day.plusSeconds(150))

        upbitSays(upbitDaily(120.0, 90.0, 110.0))
        reconcileAt(day.plusSeconds(210))
        reconcileAt(day.plusSeconds(270))

        assertEquals(listOf(100.0, 120.0, 90.0, 110.0), prices(storedYesterday()))
        assertEquals(emptyList<String>(), logged(Level.ERROR))
        coVerify(exactly = 2) { feed.getCandles(MARKET, CandleInterval.D1, any()) }
    }

    // Double 누적 오차는 같다고 본다.
    @Test
    fun `volume within float noise counts as equal`() {
        aggregateYesterday()
        upbitSays(upbitDaily(120.0, 90.0, 110.0, volume = 2.0 + 1e-9))

        reconcileAt(day.plusSeconds(150))

        assertEquals(2.0, storedYesterday()!!.volume)
        assertEquals(emptyList<String>(), logged(Level.ERROR) + logged(Level.WARN))
    }

    // 기동 prime 의 1분 겹침처럼 거래량만 다른 경우는 두 번 확인한 뒤 알리지 않고 바꾼다.
    @Test
    fun `a volume-only difference is replaced after a second read without an alert`() {
        aggregateYesterday()
        upbitSays(upbitDaily(120.0, 90.0, 110.0, volume = 3.5))

        reconcileAt(day.plusSeconds(150))
        assertEquals(2.0, storedYesterday()!!.volume)
        reconcileAt(day.plusSeconds(210))

        assertEquals(3.5, storedYesterday()!!.volume)
        assertEquals(emptyList<String>(), logged(Level.ERROR) + logged(Level.WARN))
    }

    // 업비트가 아직 값을 고치는 중이면 처음 값이 아니라 마지막으로 두 번 같은 값을 쓴다.
    @Test
    fun `a mismatch that keeps changing is replaced with the value read twice in a row`() {
        aggregateYesterday()
        upbitSays(upbitDaily(125.0, 90.0, 112.0))
        reconcileAt(day.plusSeconds(150))
        val settled = upbitDaily(126.0, 90.0, 113.0)
        upbitSays(settled)
        reconcileAt(day.plusSeconds(210))
        assertEquals(listOf(100.0, 120.0, 90.0, 110.0), prices(storedYesterday()))

        reconcileAt(day.plusSeconds(270))

        assertEquals(prices(settled), prices(storedYesterday()))
        assertEquals(1, logged(Level.ERROR).size, logged(Level.ERROR).toString())
    }

    @Test
    fun `a missing stored day is filled after two agreeing reads and a failed fetch is retried`() {
        coEvery { feed.getCandles(MARKET, CandleInterval.D1, any()) } throws RuntimeException("timeout") andThen listOf(upbitDaily(120.0, 90.0, 110.0))

        reconcileAt(day.plusSeconds(150))
        reconcileAt(day.plusSeconds(210))
        assertNull(storedYesterday())
        reconcileAt(day.plusSeconds(270))

        assertEquals(listOf(100.0, 120.0, 90.0, 110.0), prices(storedYesterday()))
        assertEquals(1, logged(Level.WARN).size, logged(Level.WARN).toString())
        assertEquals(emptyList<String>(), logged(Level.ERROR))
    }

    @Test
    fun `a day upbit has no candle for is done, and an unfinished day is dropped with one warning when the date turns`() {
        upbitSays()
        reconcileAt(day.plusSeconds(150))
        reconcileAt(day.plusSeconds(210))
        coVerify(exactly = 1) { feed.getCandles(MARKET, CandleInterval.D1, any()) }

        coEvery { feed.getCandles(MARKET, CandleInterval.D1, any()) } throws RuntimeException("timeout")
        reconcileAt(day.plus(1, ChronoUnit.DAYS).plusSeconds(150))
        reconcileAt(day.plus(2, ChronoUnit.DAYS).plusSeconds(150))

        assertEquals(1, logged(Level.WARN).count { "2026-11-12" in it }, logged(Level.WARN).toString())
    }

    // 조회가 하루 내내 실패하면 다음 날 WARN 만으로는 그날을 지키지 못한다. 기준은 경계가 아니라 처음 시도한 때라
    // 한낮에 다시 떠 첫 조회가 한 번 실패한 것으로는 알리지 않는다.
    @Test
    fun `a market still unfinished thirty minutes after its first attempt raises one error, even after a late start`() {
        coEvery { feed.getCandles(MARKET, CandleInterval.D1, any()) } throws RuntimeException("400 bad request")
        val start = day.plusSeconds(7_200)

        reconcileAt(start)
        reconcileAt(start.plusSeconds(1_799))
        assertEquals(emptyList<String>(), logged(Level.ERROR))
        reconcileAt(start.plusSeconds(1_800))
        reconcileAt(start.plusSeconds(1_860))

        assertEquals(1, logged(Level.ERROR).count { MARKET in it }, logged(Level.ERROR).toString())
    }

    private companion object {
        const val MARKET = "KRW-BTC"
    }
}
