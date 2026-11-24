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
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.common.config.PriceEventsProperties
import com.trading.common.config.TradingProperties
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import com.trading.common.domain.NormalizedTrade
import com.trading.common.strategy.TradingStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
    private val trades = MutableSharedFlow<NormalizedTrade>(extraBufferCapacity = 10_000)
    private val workerLogger = LoggerFactory.getLogger(TickerWorker::class.java) as Logger

    @BeforeEach
    fun setup() {
        every { marketDataStore.getLatestTicker(any(), any()) } returns null
        every { marketDataStore.trades } returns trades
        every { strategy.name } returns "test_strategy"
        every { strategy.minCandles } returns 21
        every { dailyResetManager.getTradingDate() } returns LocalDate.of(2026, 11, 20)
        every { dailyResetManager.shouldSellForDailyReset(any()) } returns false
        coEvery { upbitClient.getTicker(any()) } coAnswers { firstArg<String>().split(",").map { Ticker(market = it, tradePrice = 100.0) } }
        logger.addAppender(logs)
        workerLogger.addAppender(logs)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(logs)
        workerLogger.detachAppender(logs)
    }

    /** 시험 스케줄러의 가상 시각을 따르는 시계. */
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun instant(): Instant = t0.plusMillis(testScheduler.currentTime)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private fun TestScope.engine(
        priceEvents: PriceEventsProperties = PriceEventsProperties(),
        intervalSeconds: Long = 1,
    ) = TradingEngine(
        upbitClient = upbitClient,
        positionManager = positionManager,
        dailyResetManager = dailyResetManager,
        strategy = strategy,
        tradingProperties = TradingProperties(intervalSeconds = intervalSeconds),
        userId = 1L,
        marketDataStore = marketDataStore,
        clock = virtualClock(),
        priceEvents = priceEvents,
        statusChanged = { statusSignals++ },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private var statusSignals = 0

    private fun TestScope.trade(market: String, price: Double, exchange: Exchange = Exchange.UPBIT, at: Instant = virtualClock().instant()) =
        NormalizedTrade(exchange, market, price, volume = 1.0, tradeTime = at, sequentialId = seq++)

    private var seq = 1L

    private suspend fun awaitCollector() = withTimeout(5_000) { while (trades.subscriptionCount.value == 0) delay(10) }

    private fun heldForReal(vararg tickers: String) =
        tickers.associateWith { TradingState(it, position = true, avgBuyPrice = 100.0, holdVolume = 1_000.0, peakPrice = 100.0) }

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
            assertEquals(0, statusSignals)
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
            assertEquals(1, statusSignals, "죽은 엔진을 화면이 곧바로 보게 한 번 알린다")
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

    // --- 체결 이벤트 ---

    private fun todayCandles() = (0 until 30).map { i ->
        NormalizedCandle(
            Exchange.UPBIT, "BTC/KRW", openPrice = 100.0, highPrice = 101.0, lowPrice = 99.0, closePrice = 100.0, volume = 1.0,
            openTime = LocalDate.of(2026, 11, 20).minusDays(i.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant(),
        )
    }

    @Test
    fun `while trades are fresh a round does not exit on the store price, and takes over once they stop`() = runTest {
        var storePrice = 100.0
        every { marketDataStore.getLatestTicker(any(), any()) } answers { NormalizedTicker(Exchange.UPBIT, "BTC/KRW", storePrice, timestamp = virtualClock().instant()) }
        every { positionManager.checkStopLoss(any(), any()) } answers { secondArg<Double>() <= 95.0 }
        val engine = engine()
        engine.start(listOf("KRW-BTC"), heldForReal("KRW-BTC"))
        try {
            awaitCollector()
            delay(100) // 시작 바퀴는 저장소 가격(100)으로 판단한다
            trades.emit(trade("BTC/KRW", 100.0)); delay(10)
            storePrice = 50.0
            repeat(20) { delay(500); trades.emit(trade("BTC/KRW", 100.0)) }
            coVerify(exactly = 0) { positionManager.sell(any(), any(), any(), any()) }

            delay(31_000)
            coVerify(atLeast = 1) { positionManager.sell("KRW-BTC", any(), 50.0, SellReason.STOP_LOSS) }
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `while trades are fresh a round still reconciles, retries persists and makes the hold-days exit at the trade price`() = runTest {
        val engine = engine()
        engine.start(listOf("KRW-BTC"), heldForReal("KRW-BTC"))
        try {
            awaitCollector()
            delay(100)
            trades.emit(trade("BTC/KRW", 101.0)); delay(10)
            every { dailyResetManager.shouldSellForDailyReset(any()) } returns true
            delay(1_000)

            coVerify(exactly = 1) { positionManager.sell("KRW-BTC", any(), 101.0, SellReason.DAILY_RESET) }
            coVerify(atLeast = 2) { positionManager.retryPendingPersistIfNeeded(any()) }
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `an overflowing trade mailbox drops the oldest trades and warns at most once a minute with the count, rounds are kept`() = runTest {
        every { positionManager.checkStopLoss(any(), any()) } answers { secondArg<Double>() <= 95.0 }
        val gates = List(2) { CompletableDeferred<Unit>() }
        var sells = 0
        coEvery { positionManager.sell(any(), any(), any(), any()) } coAnswers { gates.getOrNull(sells++)?.await(); mockk<TradeRecord>(relaxed = true) }
        val seen = mutableListOf<Double>()
        every { positionManager.checkTakeProfit(any(), any()) } answers { seen += secondArg<Double>(); false }
        var retries = 0
        coEvery { positionManager.retryPendingPersistIfNeeded(any()) } answers { retries++ }
        val overflow = TickerWorker.TRADE_MAILBOX_CAPACITY + 100
        val engine = engine()
        engine.start(listOf("KRW-BTC"), heldForReal("KRW-BTC"))
        fun drops() = logs.list.filter { it.level == Level.WARN && "dropped" in it.formattedMessage }.map { it.formattedMessage }
        suspend fun burstDuringSell(gate: CompletableDeferred<Unit>) {
            trades.emit(trade("BTC/KRW", 90.0)); delay(100) // 매도 중에 멈춘다
            val before = retries
            seen.clear()
            repeat(overflow) { trades.emit(trade("BTC/KRW", 200.0 + it)) }
            delay(3_000) // 바퀴 몇 개가 합쳐진다
            gate.complete(Unit); delay(100)
            assertEquals((100 until overflow).map { 200.0 + it }, seen.filter { it >= 200.0 }, "오래된 체결부터 버린다")
            assertTrue(retries > before, "바퀴는 버리지 않는다")
        }
        try {
            awaitCollector()
            delay(100)

            burstDuringSell(gates[0])
            assertEquals(1, drops().size, drops().toString())
            assertTrue("100" in drops().single(), drops().toString())

            burstDuringSell(gates[1])
            assertEquals(1, drops().size, "1분 안의 두 번째 버림은 알리지 않는다: ${drops()}")

            delay(61_000)
            trades.emit(trade("BTC/KRW", 150.0)); delay(100)
            assertEquals(2, drops().size, drops().toString())
            assertTrue("100" in drops().last(), "알리지 않은 버림은 다음 경고가 센다: ${drops()}")
        } finally {
            gates.forEach { it.complete(Unit) }
            engine.stop()
        }
    }

    @Test
    fun `the collector hands each worker only its own exchange's trades and starts after the workers exist`() = runTest {
        every { positionManager.checkStopLoss(any(), any()) } answers { secondArg<Double>() <= 95.0 }
        val engine = engine(intervalSeconds = 60)
        engine.start(listOf("KRW-BTC"), heldForReal("KRW-BTC"))
        try {
            awaitCollector()
            delay(100)
            trades.emit(trade("BTC/KRW", 90.0, exchange = Exchange.KIS)); delay(100)
            trades.emit(trade("ETH/KRW", 90.0)); delay(100)
            coVerify(exactly = 0) { positionManager.sell(any(), any(), any(), any()) }

            trades.emit(trade("BTC/KRW", 90.0)); delay(100)
            coVerify(exactly = 1) { positionManager.sell("KRW-BTC", any(), 90.0, SellReason.STOP_LOSS) }
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `trades for a released ticker are dropped and the engine stays alive`() = runTest {
        val restored = mapOf("KRW-OLD" to TradingState("KRW-OLD", buyDate = LocalDate.of(2026, 11, 1)))
        val engine = engine()
        engine.start(listOf("KRW-BTC"), restored)
        try {
            withTimeout(5_000) { while ("KRW-OLD" in engine.getActiveTickers()) delay(20) }
            awaitCollector()
            repeat(3) { trades.emit(trade("OLD/KRW", 1.0)) }
            delay(1_500)

            assertTrue(engine.isLoopAlive())
            assertTrue(errors().isEmpty(), errors().toString())
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `a trade collector that ends while the engine runs is reported as engine dead`() = runTest {
        val ended = mockk<SharedFlow<NormalizedTrade>>()
        coEvery { ended.collect(any()) } throws IllegalStateException("feed gone")
        every { marketDataStore.trades } returns ended
        val engine = engine()
        engine.start(listOf("KRW-BTC"))
        try {
            withTimeout(5_000) { while (engine.isLoopAlive()) delay(20) }

            assertTrue(errors().any { "[ENGINE DEAD] trade collector" in it }, errors().toString())
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `with price events switched off the engine does not take trades and only rounds decide`() = runTest {
        val engine = engine(priceEvents = PriceEventsProperties(enabled = false), intervalSeconds = 60)
        engine.start(listOf("KRW-BTC"), heldForReal("KRW-BTC"))
        try {
            delay(100)
            trades.emit(trade("BTC/KRW", 90.0))
            delay(1_000)

            assertEquals(0, trades.subscriptionCount.value)
            verify(exactly = 0) { marketDataStore.trades }
            coVerify(exactly = 0) { positionManager.sell(any(), any(), any(), any()) }
            assertTrue(engine.isLoopAlive())
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `after stop no order is started from trades left in a mailbox`() = runTest {
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns todayCandles()
        coEvery { strategy.shouldBuyNormalized(any(), 123.0, any()) } returns true
        val inBuy = CompletableDeferred<String>()
        coEvery { positionManager.buy(any(), any(), any(), any()) } coAnswers { inBuy.complete(firstArg()); CompletableDeferred<Unit>().await(); null }
        val engine = engine()
        engine.start(listOf("KRW-BTC", "KRW-ETH"))
        try {
            awaitCollector()
            delay(100)
            trades.emit(trade("BTC/KRW", 123.0))
            assertEquals("KRW-BTC", withTimeout(5_000) { inBuy.await() })
            repeat(3) { trades.emit(trade("ETH/KRW", 123.0)) } // ETH 는 매수 창구 앞에서 기다린다
            delay(100)
        } finally {
            engine.stop()
        }
        repeat(3) { trades.emit(trade("ETH/KRW", 123.0)) }
        delay(1_000)

        coVerify(exactly = 0) { positionManager.buy("KRW-ETH", any(), any(), any()) }
    }
}
