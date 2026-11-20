package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Ticker
import com.trading.bot.domain.TradingState
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * 종목마다 작업자 하나가 그 종목을 처리한다. 한 종목의 느린 주문이 다른 종목을 막지 않고, 매수만 한 번에 하나씩 나간다.
 * 엔진을 시험 디스패처로 돌려 시계·작업자·진행 시각이 가상 시간을 따른다.
 */
class TradingEngineWorkersTest {

    private val upbitClient = mockk<UpbitClient>(relaxed = true)
    private val positionManager = mockk<PositionManager>(relaxed = true)
    private val dailyResetManager = mockk<DailyResetManager>(relaxed = true)
    private val strategy = mockk<TradingStrategy>(relaxed = true)
    private val marketDataStore = mockk<MarketDataStore>(relaxed = true)
    private val logger = LoggerFactory.getLogger(TradingEngine::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }
    private val t0 = Instant.parse("2026-11-20T03:00:00Z")

    @BeforeEach
    fun setup() {
        every { marketDataStore.getLatestTicker(any(), any()) } returns null
        every { strategy.name } returns "test_strategy"
        every { strategy.minCandles } returns 21
        every { dailyResetManager.getTradingDate() } returns LocalDate.of(2026, 11, 20)
        every { dailyResetManager.shouldSellForDailyReset(any()) } returns false
        coEvery { upbitClient.getTicker(any()) } coAnswers { firstArg<String>().split(",").map { Ticker(market = it, tradePrice = 100.0) } }
        logger.addAppender(logs)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(logs)
    }

    /** 시험 스케줄러의 가상 시각을 따르는 시계. */
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun instant(): Instant = t0.plusMillis(testScheduler.currentTime)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private fun TestScope.engine() = TradingEngine(
        upbitClient = upbitClient,
        positionManager = positionManager,
        dailyResetManager = dailyResetManager,
        strategy = strategy,
        tradingProperties = TradingProperties(intervalSeconds = 1),
        userId = 1L,
        marketDataStore = marketDataStore,
        clock = virtualClock(),
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun held(vararg tickers: String) = tickers.associateWith { TradingState(it, position = true, avgBuyPrice = 100.0, holdVolume = 1.0) }

    private fun errors() = logs.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }

    @Test
    fun `a sell that hangs on one ticker does not keep another ticker from selling`() = runTest {
        every { positionManager.checkStopLoss(any(), any()) } returns true
        val hung = CompletableDeferred<Unit>()
        val ethSold = CompletableDeferred<Unit>()
        coEvery { positionManager.sell("KRW-BTC", any(), any(), any()) } coAnswers { hung.await(); null }
        coEvery { positionManager.sell("KRW-ETH", any(), any(), any()) } coAnswers { ethSold.complete(Unit); null }
        val engine = engine()
        engine.start(listOf("KRW-BTC", "KRW-ETH"), held("KRW-BTC", "KRW-ETH"))
        try {
            withTimeout(5_000) { ethSold.await() }
        } finally {
            hung.complete(Unit)
            engine.stop()
        }
    }

    @Test
    fun `buys from two tickers go out one at a time`() = runTest {
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        var inside = 0
        var maxInside = 0
        val bought = mutableSetOf<String>()
        coEvery { positionManager.buy(any(), any(), any(), any()) } coAnswers {
            maxInside = maxOf(maxInside, ++inside)
            delay(200)
            inside--
            bought += firstArg<String>()
            null
        }
        val engine = engine()
        engine.start(listOf("KRW-BTC", "KRW-ETH"))
        try {
            withTimeout(5_000) { while (bought.size < 2) delay(20) }
            assertEquals(1, maxInside, "매수는 한 번에 하나")
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `the startup balance is read once and handed to every ticker's sync`() = runTest {
        val accounts = listOf(Account(currency = "BTC", balance = "1"), Account(currency = "ETH", balance = "2"))
        coEvery { upbitClient.getAccounts() } returns accounts
        val engine = engine()
        engine.start(listOf("KRW-BTC", "KRW-ETH", "KRW-XRP"))
        try {
            delay(100)
            coVerify(exactly = 1) { upbitClient.getAccounts() }
            listOf("KRW-BTC", "KRW-ETH", "KRW-XRP").forEach { coVerify(exactly = 1) { positionManager.syncPosition(it, any(), accounts) } }
        } finally {
            engine.stop()
        }
    }

    // 빈 목록을 넘기면 모든 보유가 0 으로 동기화된다. 못 받았으면 null 이라 작업자가 각자 다시 묻는다.
    @Test
    fun `a failed startup balance lookup lets each ticker look up its own`() = runTest {
        coEvery { upbitClient.getAccounts() } throws RuntimeException("503")
        val engine = engine()
        engine.start(listOf("KRW-BTC", "KRW-ETH"))
        try {
            delay(100)
            listOf("KRW-BTC", "KRW-ETH").forEach { coVerify(exactly = 1) { positionManager.syncPosition(it, any(), null) } }
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `a ticker released from management ends its worker quietly and the engine stays alive`() = runTest {
        // 사용자 목록 밖인데 진입 흔적(buyDate)만 남고 보유가 없다 — 동기화 뒤 정리된다.
        val restored = mapOf("KRW-OLD" to TradingState("KRW-OLD", buyDate = LocalDate.of(2026, 11, 1)))
        val engine = engine()
        engine.start(listOf("KRW-BTC"), restored)
        try {
            withTimeout(5_000) { while ("KRW-OLD" in engine.getActiveTickers()) delay(20) }
            delay(1_500) // 한 바퀴 더

            assertTrue(engine.isLoopAlive())
            assertTrue(errors().none { "ENGINE DEAD" in it }, errors().toString())
            coVerify { positionManager.persistState(match { it.ticker == "KRW-OLD" && it.buyDate == null }) }
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `a worker that dies while the engine runs is reported and the engine is no longer alive`() = runTest {
        every { positionManager.checkStopLoss(match { it.ticker == "KRW-BTC" }, any()) } throws StackOverflowError("boom")
        val engine = engine()
        engine.start(listOf("KRW-BTC", "KRW-ETH"), held("KRW-BTC", "KRW-ETH"))
        try {
            withTimeout(5_000) { while (engine.isLoopAlive()) delay(20) }

            assertTrue(errors().any { "ENGINE DEAD" in it && "KRW-BTC" in it }, errors().toString())
            assertTrue(engine.isRunning(), "정지하기 전까지 running 은 그대로다")
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `an engine that fails before starting its workers is reported`() = runTest {
        coEvery { upbitClient.getAccounts() } throws StackOverflowError("boom")
        val engine = engine()
        engine.start(listOf("KRW-BTC"))
        try {
            withTimeout(5_000) { while (engine.isLoopAlive()) delay(20) }

            assertTrue(errors().any { "[ENGINE DEAD] engine for user" in it }, errors().toString())
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `a worker queued for the buy window is not counted as stalled, the one holding it is`() = runTest {
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        val inBuy = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        coEvery { positionManager.buy(any(), any(), any(), any()) } coAnswers { inBuy.complete(firstArg()); release.await(); null }
        val engine = engine()
        engine.start(listOf("KRW-BTC", "KRW-ETH"))
        try {
            withTimeout(5_000) { inBuy.await() }
            val heldSince = virtualClock().instant()

            delay(Duration.ofMinutes(4).toMillis())
            assertFalse(engine.isStalled(virtualClock().instant()), "창구를 쥔 종목이 4분 — 아직 멈춤 아님")
            delay(Duration.ofMinutes(2).toMillis())
            assertTrue(engine.isStalled(virtualClock().instant()), "창구를 쥔 종목이 6분 진행 없음")
            assertEquals(heldSince, engine.lastProgressAt(), "기다리는 종목·시계가 아니라 창구를 쥔 종목이 기준이다")
        } finally {
            release.complete(Unit)
            engine.stop()
        }
    }

    @Test
    fun `a worker that just took the buy window after a long wait is not stalled`() = runTest {
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        val first = CompletableDeferred<String>()
        val releaseFirst = CompletableDeferred<Unit>()
        val second = CompletableDeferred<String>()
        coEvery { positionManager.buy(any(), any(), any(), any()) } coAnswers {
            if (first.complete(firstArg())) releaseFirst.await() else { second.complete(firstArg()); CompletableDeferred<Unit>().await() }
            null
        }
        val engine = engine()
        engine.start(listOf("KRW-BTC", "KRW-ETH"))
        try {
            withTimeout(5_000) { first.await() }
            delay(Duration.ofMinutes(4).toMillis())
            releaseFirst.complete(Unit)
            withTimeout(5_000) { second.await() }

            delay(Duration.ofMinutes(2).toMillis())
            assertFalse(engine.isStalled(virtualClock().instant()), "6분 기다렸지만 창구를 잡은 지는 2분 (${engine.lastProgressAt()})")
        } finally {
            engine.stop()
        }
    }
}
