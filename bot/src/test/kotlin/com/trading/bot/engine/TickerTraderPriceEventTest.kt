package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.TradingCalendar
import com.trading.bot.exchange.UpbitMarketRules
import com.trading.bot.exchange.UpbitTradingCalendar
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.strategy.TradingStrategy
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verifyOrder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * 체결 하나가 오면 작업자가 그 가격으로 바로 판단한다. 체결 경로는 주문 말고는 거래소·DB 를 부르지 않는다.
 * 작업자와 트레이더를 시험 스케줄러로 돌려 체결 시각·낡음이 가상 시간을 따른다.
 */
class TickerTraderPriceEventTest {

    private val upbitClient = mockk<UpbitClient>(relaxed = true)
    private val positionManager = mockk<PositionManager>(relaxed = true)
    private val dailyResetManager = mockk<DailyResetManager>(relaxed = true)
    private val strategy = mockk<TradingStrategy>(relaxed = true)
    private val store = mockk<MarketDataStore>(relaxed = true)
    private val t0 = Instant.parse("2026-11-20T03:00:00Z")
    private val today = LocalDate.of(2026, 11, 20)
    private val states = mutableMapOf<String, TradingState>()
    private val storeCandles = (0 until 30).map { i ->
        NormalizedCandle(
            Exchange.UPBIT, "BTC/KRW", openPrice = 100.0, highPrice = 101.0, lowPrice = 99.0, closePrice = 100.0, volume = 1.0,
            openTime = today.minusDays(i.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant(),
        )
    }
    private val record = mockk<TradeRecord>(relaxed = true)
    private var exitOnly = false
    private val traderLogger = LoggerFactory.getLogger(TickerTrader::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setup() {
        every { store.getLatestTicker(any(), any()) } returns null
        every { store.getCandles(any(), any(), CandleInterval.D1, any()) } returns storeCandles
        every { strategy.name } returns "test_strategy"
        every { strategy.minCandles } returns 21
        every { dailyResetManager.getTradingDate() } returns today
        every { dailyResetManager.shouldSellForDailyReset(any()) } returns false
        every { positionManager.checkStopLoss(any(), any()) } answers { secondArg<Double>() <= 95.0 }
        every { positionManager.checkTrailingStop(any(), any()) } returns false
        every { positionManager.checkTakeProfit(any(), any()) } returns false
        every { positionManager.entriesPausedNow() } returns false
        coEvery { positionManager.sell(any(), any(), any(), any()) } returns record
        coEvery { positionManager.buy(any(), any(), any(), any()) } returns record
        traderLogger.addAppender(logs)
    }

    @AfterEach
    fun teardown() {
        traderLogger.detachAppender(logs)
    }

    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun instant(): Instant = t0.plusMillis(testScheduler.currentTime)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private fun TestScope.now(): Instant = virtualClock().instant()

    private fun TestScope.trader(
        shadow: ShadowExitObserver? = null,
        calendar: TradingCalendar = UpbitTradingCalendar,
        window: BuyWindow = BuyWindow {},
    ) = TickerTrader(
        upbitClient = upbitClient,
        positionManager = positionManager,
        dailyResetManager = dailyResetManager,
        strategy = strategy,
        tradingProperties = TradingProperties(intervalSeconds = 60),
        roster = object : TickerRoster {
            override fun state(ticker: String): TradingState? = states[ticker]
            override fun isExitOnly(ticker: String): Boolean = exitOnly
            override fun release(ticker: String) {
                states.remove(ticker)
            }
        },
        buyWindow = window,
        userId = 1L,
        marketDataStore = store,
        exchange = Exchange.UPBIT,
        dailyCandleCache = null,
        shadowExitObserver = shadow,
        clock = virtualClock(),
        calendar = calendar,
        rules = UpbitMarketRules,
    )

    private fun held() = TradingState("KRW-BTC", position = true, avgBuyPrice = 100.0, holdVolume = 1_000.0, peakPrice = 100.0)
        .also { states[it.ticker] = it }

    private fun flat() = TradingState("KRW-BTC").also { states[it.ticker] = it }

    private fun round() = Round(today, RestRound(emptyMap()))

    /** 작업자를 띄워 첫 바퀴(시작 때 REST 가격)를 지나게 한다. 체결은 그 뒤에 처리된다. */
    private fun TestScope.started(state: TradingState, trader: TickerTrader = trader()): Pair<TickerWorker, Job> {
        val worker = TickerWorker(state.ticker, state, trader, virtualClock())
        val job = launch { worker.run(null) }
        worker.offer(round())
        testScheduler.advanceUntilIdle()
        return worker to job
    }

    @Test
    fun `a held ticker sells on the trade that crosses the stop, without waiting for a round`() = runTest {
        val state = held()
        val (worker, job) = started(state)

        worker.offerTrade(99.0, now()); testScheduler.advanceUntilIdle()
        coVerify(exactly = 0) { positionManager.sell(any(), any(), any(), any()) }
        delay(1_000)
        worker.offerTrade(94.0, now()); testScheduler.advanceUntilIdle()
        job.cancel()

        coVerify(exactly = 1) { positionManager.sell("KRW-BTC", state, 94.0, SellReason.STOP_LOSS) }
    }

    @Test
    fun `a flat ticker buys on a trade, with today's daily close replaced by the trade price`() = runTest {
        val candles = slot<List<NormalizedCandle>>()
        coEvery { strategy.shouldBuyNormalized(capture(candles), 123.0, any()) } returns true
        val state = flat()
        val (worker, job) = started(state)

        worker.offerTrade(123.0, now()); testScheduler.advanceUntilIdle()
        job.cancel()

        coVerify(exactly = 1) { positionManager.buy("KRW-BTC", state, 123.0, "test_strategy") }
        assertEquals(123.0, candles.captured.first().closePrice)
        assertEquals(storeCandles.size, candles.captured.size)
        assertEquals(storeCandles.drop(1), candles.captured.drop(1))
        assertEquals(100.0, storeCandles.first().closePrice, "저장소의 일봉은 바꾸지 않는다")
    }

    @Test
    fun `a trade does not buy a ticker sold today, with a pending order, while entries are paused, or while unsynced`() = runTest {
        coEvery { strategy.shouldBuyNormalized(any(), any(), any()) } returns true
        val trader = trader()
        val blocked = listOf<(TradingState) -> Unit>(
            { it.boughtToday = true },
            { it.pendingBuyUuid = "b-1" },
            { it.pendingSellUuid = "s-1" },
            { it.unsynced = true },
        )
        blocked.forEach { block ->
            val state = flat().also(block)
            trader.onTrade("KRW-BTC", state, PriceSample(123.0, now()), TradeOrders.ALL)
        }
        every { positionManager.entriesPausedNow() } returns true
        trader.onTrade("KRW-BTC", flat(), PriceSample(123.0, now()), TradeOrders.ALL)

        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `queued trades are compared in order - a brief dip below the stop sells and an intermediate high raises the peak`() = runTest {
        val state = held()
        var peakAtSell = 0.0
        coEvery { positionManager.sell(any(), any(), any(), any()) } answers { peakAtSell = secondArg<TradingState>().peakPrice; record }
        val (worker, job) = started(state)

        listOf(100.0, 110.0, 94.0, 100.0).forEach { worker.offerTrade(it, now()) }
        testScheduler.advanceUntilIdle()
        job.cancel()

        coVerify(exactly = 1) { positionManager.sell("KRW-BTC", state, 94.0, SellReason.STOP_LOSS) }
        assertEquals(110.0, peakAtSell)
    }

    @Test
    fun `the trade path calls nothing but orders - no reconcile, persist, balance sync or hold-days exit`() = runTest {
        val trader = trader()
        val state = held()
        (1..50).forEach { i -> trader.onTrade("KRW-BTC", state, PriceSample(100.0 + i, now()), TradeOrders.ALL) }
        trader.onTrade("KRW-BTC", flat(), PriceSample(100.0, now()), TradeOrders.ALL)

        assertEquals(150.0, state.peakPrice)
        assertTrue(state.peakPersistFailed, "새 고점은 바퀴가 저장한다")
        coVerify(exactly = 0) { positionManager.persistPeak(any()) }
        coVerify(exactly = 0) { positionManager.persistState(any()) }
        coVerify(exactly = 0) { positionManager.retryPendingPersistIfNeeded(any()) }
        coVerify(exactly = 0) { positionManager.syncPosition(any(), any(), any()) }
        coVerify(exactly = 0) { positionManager.reconcilePendingBuy(any(), any(), any()) }
        coVerify(exactly = 0) { positionManager.reconcilePendingSell(any(), any(), any()) }
        coVerify(exactly = 0) { upbitClient.getAccounts() }
        coVerify(exactly = 0) { upbitClient.getOrder(any()) }
        coVerify(exactly = 0) { upbitClient.getTicker(any()) }
        coVerify(exactly = 0) { dailyResetManager.shouldSellForDailyReset(any()) }
    }

    @Test
    fun `a trade ends at once while unsynced, with a pending order, or outside the session`() = runTest {
        val closed = object : TradingCalendar by UpbitTradingCalendar {
            override fun isSessionOpen(now: Instant): Boolean = false
        }
        trader().onTrade("KRW-BTC", held().apply { unsynced = true }, PriceSample(90.0, now()), TradeOrders.ALL)
        trader().onTrade("KRW-BTC", held().apply { pendingSellUuid = "s-1" }, PriceSample(90.0, now()), TradeOrders.ALL)
        trader().onTrade("KRW-BTC", held().apply { pendingBuyIdentifier = "b-1" }, PriceSample(90.0, now()), TradeOrders.ALL)
        trader(calendar = closed).onTrade("KRW-BTC", held(), PriceSample(90.0, now()), TradeOrders.ALL)

        coVerify(exactly = 0) { positionManager.checkStopLoss(any(), any()) }
        coVerify(exactly = 0) { positionManager.sell(any(), any(), any(), any()) }
    }

    @Test
    fun `trades that arrive during an order are handled after it - they raise the peak but start no order, stale ones are ignored`() = runTest {
        val state = held()
        val gate = CompletableDeferred<TradeRecord>()
        var sells = 0
        coEvery { positionManager.sell(any(), any(), any(), any()) } coAnswers { if (sells++ == 0) gate.await() else record }
        val (worker, job) = started(state)

        worker.offerTrade(94.0, now()); testScheduler.advanceUntilIdle() // 매도 중에 멈춘다
        delay(1_000)
        worker.offerTrade(120.0, now())
        worker.offerTrade(94.0, now())
        testScheduler.advanceUntilIdle()
        gate.complete(record); testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { positionManager.sell(any(), any(), any(), any()) }
        assertEquals(120.0, state.peakPrice, "주문 중에 온 체결도 고점은 올린다")

        delay(1_000)
        worker.offerTrade(90.0, now().minus(Duration.ofSeconds(31))); testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { positionManager.sell(any(), any(), any(), any()) }

        worker.offerTrade(94.0, now()); testScheduler.advanceUntilIdle()
        job.cancel()
        coVerify(exactly = 2) { positionManager.sell(any(), any(), any(), any()) }
    }

    @Test
    fun `an order attempt without a result is not retried from trades until the next round`() = runTest {
        coEvery { positionManager.sell(any(), any(), any(), any()) } returns null
        val state = held()
        val (worker, job) = started(state)

        repeat(5) { worker.offerTrade(94.0, now()); testScheduler.advanceUntilIdle(); delay(100) }
        coVerify(exactly = 1) { positionManager.sell(any(), any(), any(), any()) }

        worker.offer(round()); testScheduler.advanceUntilIdle()
        repeat(5) { delay(100); worker.offerTrade(94.0, now()); testScheduler.advanceUntilIdle() }
        job.cancel()
        coVerify(exactly = 2) { positionManager.sell(any(), any(), any(), any()) }
    }

    @Test
    fun `a buy attempt without a result is not retried from trades until the next round`() = runTest {
        coEvery { strategy.shouldBuyNormalized(any(), any(), any()) } returns true
        coEvery { positionManager.buy(any(), any(), any(), any()) } returns null
        val (worker, job) = started(flat())

        repeat(5) { delay(100); worker.offerTrade(123.0, now()); testScheduler.advanceUntilIdle() }
        job.cancel()

        coVerify(exactly = 1) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `the shadow observer sees every trade after the peak and the live decision`() = runTest {
        val shadow = mockk<ShadowExitObserver>(relaxed = true)
        val peaksSeen = mutableListOf<Pair<Double, Double>>()
        every { shadow.onTick(any(), any(), any()) } answers { peaksSeen += thirdArg<Double>() to secondArg<TradingState>().peakPrice }
        val state = held()
        val (worker, job) = started(state, trader(shadow))

        worker.offerTrade(110.0, now())
        worker.offerTrade(105.0, now())
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(110.0 to 110.0, 105.0 to 110.0), peaksSeen)
        verifyOrder {
            positionManager.checkStopLoss(state, 110.0)
            positionManager.checkTrailingStop(state, 110.0)
            positionManager.checkTakeProfit(state, 110.0)
            shadow.onTick("KRW-BTC", state, 110.0)
            positionManager.checkStopLoss(state, 105.0)
            shadow.onTick("KRW-BTC", state, 105.0)
        }
    }

    // --- 리뷰 반영 ---

    @Test
    fun `a dust holding whose exit does not sell still goes on to the buy check in the same trade`() = runTest {
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 12_000_000.0, holdVolume = 0.0001).also { states[it.ticker] = it }
        every { positionManager.checkStopLoss(any(), any()) } returns true
        coEvery { positionManager.sell(any(), any(), any(), any()) } returns null // 가드가 주문하지 않았다
        coEvery { strategy.shouldBuyNormalized(any(), any(), any()) } returns true

        val outcome = trader().onTrade("KRW-BTC", state, PriceSample(10_000_000.0, now()), TradeOrders.ALL)

        coVerify { positionManager.sell("KRW-BTC", state, 10_000_000.0, SellReason.STOP_LOSS) }
        coVerify { positionManager.buy("KRW-BTC", state, 10_000_000.0, "test_strategy") }
        assertEquals(TradeOutcome.ORDERED, outcome)
    }

    @Test
    fun `with fresh trades a round still buys through the REST daily candles when the store has none, at the trade price`() = runTest {
        every { store.getCandles(any(), any(), CandleInterval.D1, any()) } returns emptyList()
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), 123.0, any()) } returns true
        val state = flat()

        trader().processTicker("KRW-BTC", state, strategy, tradePrice = 123.0)

        coVerify(exactly = 1) { positionManager.buy("KRW-BTC", state, 123.0, "test_strategy") }
    }

    @Test
    fun `with fresh trades and usable store candles a round leaves buying to the trades`() = runTest {
        coEvery { strategy.shouldBuyNormalized(any(), any(), any()) } returns true
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true

        trader().processTicker("KRW-BTC", flat(), strategy, tradePrice = 123.0)

        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
        coVerify(exactly = 0) { upbitClient.getDayCandles(any(), any()) }
    }

    @Test
    fun `a trade that cannot evaluate a buy for lack of store candles warns once a minute`() = runTest {
        every { store.getCandles(any(), any(), CandleInterval.D1, any()) } returns storeCandles.take(5)
        val trader = trader()
        repeat(3) { trader.onTrade("KRW-BTC", flat(), PriceSample(123.0, now()), TradeOrders.ALL) }

        val warns = logs.list.filter { it.level == ch.qos.logback.classic.Level.WARN }.map { it.formattedMessage }
        assertEquals(1, warns.size, warns.toString())
        assertTrue("trades do not evaluate buys" in warns.single(), warns.toString())
        coVerify(exactly = 0) { upbitClient.getDayCandles(any(), any()) }
    }

    @Test
    fun `a buy that waited for the window re-checks freshness, the list, the roster and paused entries before ordering`() = runTest {
        coEvery { strategy.shouldBuyNormalized(any(), any(), any()) } returns true
        val changes = listOf<() -> Unit>(
            { testScheduler.advanceTimeBy(31_000) },
            { exitOnly = true },
            { states["KRW-BTC"] = TradingState("KRW-BTC") },
            { every { positionManager.entriesPausedNow() } returns true },
        )
        changes.forEach { change ->
            exitOnly = false
            every { positionManager.entriesPausedNow() } returns false
            val window = BuyWindow {}
            val hold = CompletableDeferred<Unit>()
            launch { window.withWindow("KRW-ETH") { hold.await() } }
            testScheduler.advanceUntilIdle()
            val outcome = async { trader(window = window).onTrade("KRW-BTC", flat(), PriceSample(123.0, now()), TradeOrders.ALL) }
            testScheduler.advanceUntilIdle()

            change()
            hold.complete(Unit); testScheduler.advanceUntilIdle()

            assertEquals(TradeOutcome.NO_ORDER, outcome.await())
        }
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `a trade received before the first round never orders`() = runTest {
        val state = held()
        val worker = TickerWorker(state.ticker, state, trader(), virtualClock())
        val job = launch { worker.run(null) }
        worker.offerTrade(94.0, now()); testScheduler.advanceUntilIdle()
        worker.offer(round()); testScheduler.advanceUntilIdle()
        job.cancel()

        coVerify(exactly = 0) { positionManager.sell(any(), any(), any(), any()) }
    }

    @Test
    fun `a stop-crossing trade queued behind an uneventful round still sells`() = runTest {
        val state = held()
        val (worker, job) = started(state)

        worker.offer(round())
        worker.offerTrade(94.0, now())
        testScheduler.advanceUntilIdle()
        job.cancel()

        coVerify(exactly = 1) { positionManager.sell("KRW-BTC", state, 94.0, SellReason.STOP_LOSS) }
    }

    @Test
    fun `until a new trading day's reset is saved trades do not buy, and buy once it is`() = runTest {
        coEvery { strategy.shouldBuyNormalized(any(), any(), any()) } returns true
        coEvery { positionManager.buy(any(), any(), any(), any()) } returns null
        val state = flat()
        val (worker, job) = started(state)
        val nextDay = Round(today.plusDays(1), RestRound(emptyMap()))

        coEvery { positionManager.persistState(any()) } throws RuntimeException("db down")
        worker.offer(nextDay); testScheduler.advanceUntilIdle()
        worker.offerTrade(123.0, now()); testScheduler.advanceUntilIdle()
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }

        coEvery { positionManager.persistState(any()) } returns Unit
        worker.offer(nextDay); testScheduler.advanceUntilIdle()
        delay(100)
        worker.offerTrade(123.0, now()); testScheduler.advanceUntilIdle()
        job.cancel()
        coVerify(exactly = 1) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `exits still go out from trades while the day's reset keeps failing, even the first one`() = runTest {
        coEvery { positionManager.persistState(any()) } throws RuntimeException("db down")
        val state = held()
        val worker = TickerWorker(state.ticker, state, trader(), virtualClock())
        val job = launch { worker.run(null) }
        worker.offer(round()); testScheduler.advanceUntilIdle()
        delay(100)
        worker.offerTrade(94.0, now()); testScheduler.advanceUntilIdle()
        job.cancel()

        coVerify(exactly = 1) { positionManager.sell("KRW-BTC", state, 94.0, SellReason.STOP_LOSS) }
    }
}
