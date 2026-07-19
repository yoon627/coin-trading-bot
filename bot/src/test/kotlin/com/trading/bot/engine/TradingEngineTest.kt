package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.client.UpbitClient
import com.trading.bot.client.UpbitWebSocketClient
import com.trading.bot.domain.*
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import com.trading.common.strategy.TradingStrategy
import com.trading.common.strategy.GoldenCross
import com.trading.common.strategy.MacdCross
import com.trading.common.strategy.VolatilityBreakout
import io.mockk.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class TradingEngineTest {

    private lateinit var upbitClient: UpbitClient
    private lateinit var positionManager: PositionManager
    private lateinit var dailyResetManager: DailyResetManager
    private lateinit var tradeExecutionService: TradeExecutionService
    private lateinit var strategy: TradingStrategy
    private lateinit var webSocketClient: UpbitWebSocketClient
    private lateinit var marketDataStore: MarketDataStore
    private val tradingProperties = TradingProperties(intervalSeconds = 1)

    @BeforeEach
    fun setup() {
        upbitClient = mockk(relaxed = true)
        positionManager = mockk(relaxed = true)
        dailyResetManager = mockk(relaxed = true)
        tradeExecutionService = mockk(relaxed = true)
        strategy = mockk()
        webSocketClient = mockk(relaxed = true)
        marketDataStore = mockk(relaxed = true)
        // store miss 기본값 — relaxed mock 이 non-null child mock 을 반환해 store-hit 으로 오작동하는 것 방지
        // (가격 경로는 ws/REST).
        every { marketDataStore.getLatestTicker(any(), any()) } returns null
        every { strategy.name } returns "test_strategy"
        every { dailyResetManager.checkAndReset(any()) } returns false
        every { dailyResetManager.shouldSellForDailyReset(any()) } returns false
    }

    private fun createEngine(
        strategies: List<TradingStrategy> = listOf(strategy),
        props: TradingProperties = tradingProperties,
    ): TradingEngine {
        return TradingEngine(
            upbitClient = upbitClient,
            positionManager = positionManager,
            dailyResetManager = dailyResetManager,
            tradeExecutionService = tradeExecutionService,
            strategies = strategies,
            tradingProperties = props,
            userId = 1L,
            username = "testuser",
            discordWebhookUrl = null,
            webSocketClient = webSocketClient,
            marketDataStore = marketDataStore,
        )
    }

    @Test
    fun `start sets engine to running`() = runBlocking {
        val engine = createEngine()
        assertFalse(engine.isRunning())
        engine.start(listOf("KRW-BTC"))
        assertTrue(engine.isRunning())
        engine.stop()
    }

    @Test
    fun `stop sets engine to not running`() = runBlocking {
        val engine = createEngine()
        engine.start(listOf("KRW-BTC"))
        assertTrue(engine.isRunning())
        engine.stop()
        assertFalse(engine.isRunning())
    }

    @Test
    fun `stop joins in-flight tick and does not log spurious cancellation errors`() = runBlocking {
        // stop 은 취소 후 join — 진행 중이던 tick 의 주문 후처리(NonCancellable)가 끝난 뒤 반환해야 reload 가
        // 구 루프와 경합하지 않는다. 또한 취소가 오탐 ERROR(Discord 스팸)로 둔갑하면 안 된다(CE rethrow).
        val postProcessingDone = AtomicBoolean(false)
        val buyEntered = CompletableDeferred<Unit>()
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        coEvery { upbitClient.getDayCandles(any(), any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        coEvery { positionManager.buy(any(), any(), any(), any()) } coAnswers {
            buyEntered.complete(Unit)
            withContext(NonCancellable) { delay(300); postProcessingDone.set(true) }
            null
        }
        val logger = LoggerFactory.getLogger(TradingEngine::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            val engine = createEngine()
            engine.start(listOf("KRW-BTC"))
            buyEntered.await() // tick 이 매수 후처리에 진입할 때까지 대기
            engine.stop()      // cancelAndJoin — 후처리 완주까지 대기해야
            assertTrue(postProcessingDone.get(), "stop 이 진행 중 후처리 완주를 기다리지 않음")
            val errors = appender.list.filter { it.level == Level.ERROR }
            assertTrue(
                errors.none {
                    it.formattedMessage.contains("Trading loop error") ||
                        it.formattedMessage.contains("Error processing")
                },
                "cancel 이 오탐 ERROR 로그를 남김: ${errors.map { it.formattedMessage }}",
            )
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `record persistence completes despite cancellation during shutdown`() = runBlocking {
        // 체결·상태 반영 후 취소가 오면 onTrade(DB 기록·Discord)가 스킵돼 감사 유실 → NonCancellable 완주 검증(M1).
        val recordSaved = AtomicBoolean(false)
        val saveEntered = CompletableDeferred<Unit>()
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        coEvery { upbitClient.getDayCandles(any(), any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        val record = TradeRecord(ticker = "KRW-BTC", side = TradeSide.BUY, price = 100.0, volume = 1.0, totalAmount = 100.0)
        coEvery { positionManager.buy(any(), any(), any(), any()) } returns record
        coEvery { tradeExecutionService.saveAndNotify(any(), any(), any(), any()) } coAnswers {
            saveEntered.complete(Unit)
            delay(300)
            recordSaved.set(true)
        }

        val engine = createEngine()
        engine.start(listOf("KRW-BTC"))
        saveEntered.await() // onTrade 의 기록 영속화 진입
        engine.stop()        // 취소 — NonCancellable 이면 기록이 완주
        assertTrue(recordSaved.get(), "onTrade 가 취소로 중단돼 기록이 유실됨")
    }

    @Test
    fun `concurrent stop calls are safe and halt the loop`() = runBlocking {
        // stopMutex 직렬화로 동시 stop(shutdownAll ↔ reload/stopBot)이 데드락·예외 없이 완료되고 loop 가 멈춘다(M4).
        val engine = createEngine()
        engine.start(listOf("KRW-BTC"))
        delay(50)
        val j1 = launch { engine.stop() }
        val j2 = launch { engine.stop() }
        j1.join()
        j2.join()
        assertFalse(engine.isRunning())
    }

    @Test
    fun `start is idempotent`() = runBlocking {
        val engine = createEngine()
        engine.start(listOf("KRW-BTC"))
        engine.start(listOf("KRW-ETH")) // second call should be no-op
        assertTrue(engine.isRunning())
        assertEquals(listOf("KRW-BTC"), engine.getActiveTickers())
        engine.stop()
    }

    @Test
    fun `start subscribes active tickers to WebSocket fallback`() = runBlocking {
        // watchlist 밖 티커로 startBot 해도 WS 폴백(latestPrice)이 커버하도록 engine.start 가 구독을 건다.
        val engine = createEngine()
        engine.start(listOf("KRW-DOGE", "KRW-ADA"))
        verify { webSocketClient.subscribe(listOf("KRW-DOGE", "KRW-ADA")) }
        engine.stop()
    }

    @Test
    fun `getActiveStrategyName returns strategy name`() {
        val engine = createEngine()
        assertEquals("test_strategy", engine.getActiveStrategyName())
    }

    @Test
    fun `setStrategy returns true for valid strategy`() {
        val engine = createEngine()
        assertTrue(engine.setStrategy("test_strategy"))
    }

    @Test
    fun `setStrategy returns false for unknown strategy`() {
        val engine = createEngine()
        assertFalse(engine.setStrategy("nonexistent"))
    }

    @Test
    fun `getStates returns empty map before start`() {
        val engine = createEngine()
        assertTrue(engine.getStates().isEmpty())
    }

    // --- decideSell 우선순위 (stopLoss > trailingStop > takeProfit > chartExit > dailyReset) ---

    private fun sellState() = TradingState("KRW-BTC", position = true)
    private val chartEnabledProps = TradingProperties(intervalSeconds = 1, chartExitEnabled = true)

    // chartExit off(기본) — chartExitTriggered 가 즉시 false 라 가격 안전망/일일리셋만 평가.
    @Test
    fun `decideSell prioritizes stopLoss over everything`() = runBlocking {
        val engine = createEngine()
        val state = sellState()
        every { positionManager.checkStopLoss(state, any()) } returns true
        every { positionManager.checkTrailingStop(state, any()) } returns true
        every { positionManager.checkTakeProfit(state, any()) } returns true
        assertEquals(SellReason.STOP_LOSS, engine.decideSell(state, 100.0, "KRW-BTC", strategy))
    }

    @Test
    fun `decideSell returns TRAILING_STOP when stopLoss is false`() = runBlocking {
        val engine = createEngine()
        val state = sellState()
        every { positionManager.checkStopLoss(state, any()) } returns false
        every { positionManager.checkTrailingStop(state, any()) } returns true
        assertEquals(SellReason.TRAILING_STOP, engine.decideSell(state, 100.0, "KRW-BTC", strategy))
    }

    @Test
    fun `decideSell prefers TAKE_PROFIT over chartExit and skips chart evaluation`() = runBlocking {
        val engine = createEngine(props = chartEnabledProps)
        val state = sellState()
        every { positionManager.checkStopLoss(state, any()) } returns false
        every { positionManager.checkTrailingStop(state, any()) } returns false
        every { positionManager.checkTakeProfit(state, any()) } returns true
        // 익절이 차트청산보다 우선 — 가격 안전망이 트리거되면 차트 캔들 조회조차 하지 않음(short-circuit).
        assertEquals(SellReason.TAKE_PROFIT, engine.decideSell(state, 100.0, "KRW-BTC", VolatilityBreakout()))
        coVerify(exactly = 0) { marketDataStore.getCandles(any(), any(), any(), any()) }
    }

    @Test
    fun `decideSell returns CHART_EXIT when only chart signal triggers`() = runBlocking {
        val engine = createEngine(props = chartEnabledProps)
        val state = sellState()
        every { positionManager.checkStopLoss(state, any()) } returns false
        every { positionManager.checkTrailingStop(state, any()) } returns false
        every { positionManager.checkTakeProfit(state, any()) } returns false
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns deadCrossNormalized()
        assertEquals(SellReason.CHART_EXIT, engine.decideSell(state, 50.0, "KRW-BTC", VolatilityBreakout()))
    }

    @Test
    fun `decideSell falls to DAILY_RESET when chart disabled`() = runBlocking {
        val engine = createEngine()
        val state = sellState()
        every { positionManager.checkStopLoss(state, any()) } returns false
        every { positionManager.checkTrailingStop(state, any()) } returns false
        every { positionManager.checkTakeProfit(state, any()) } returns false
        every { dailyResetManager.shouldSellForDailyReset(state) } returns true
        assertEquals(SellReason.DAILY_RESET, engine.decideSell(state, 100.0, "KRW-BTC", strategy))
    }

    @Test
    fun `decideSell returns null when nothing triggers`() = runBlocking {
        val engine = createEngine()
        val state = sellState()
        every { positionManager.checkStopLoss(state, any()) } returns false
        every { positionManager.checkTrailingStop(state, any()) } returns false
        every { positionManager.checkTakeProfit(state, any()) } returns false
        assertNull(engine.decideSell(state, 100.0, "KRW-BTC", strategy))
    }

    // --- processTicker 오케스트레이션 (H8 게이트 순서·skip/return 불변식) ---
    // 회귀 게이트가 실제로 물리도록 mock 이 TradingState 를 프로덕션 PositionManager 처럼 변이시킨다
    // (sell→markSold, reconcile→markBought). 그러지 않으면 문제의 return 을 지워도 downstream 게이트가
    // 대신 막아 mutation 이 관측되지 않는다(plan-review Major-1). 각 시나리오의 게이트/return 을 제거하면
    // 실제로 FAIL 함을 수동 mutation 으로 1회 확인함(Progress 기록).

    private fun tradeRec(side: TradeSide) =
        TradeRecord(ticker = "KRW-BTC", side = side, price = 100.0, volume = 1.0, totalAmount = 100.0)

    @Test
    fun `processTicker buys using REST price when store and WebSocket miss`() = runTest {
        val engine = createEngine()
        val state = TradingState("KRW-BTC")
        every { webSocketClient.latestPrice(any()) } returns null // getRealtimePrice → null → REST 폴백
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 51_000_000.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true

        engine.processTicker("KRW-BTC", state, strategy)

        // REST 가격이 buy 로 그대로 전달 — 삭제한 delay(3000) 테스트의 REST 폴백 커버리지 대체.
        coVerify { positionManager.buy("KRW-BTC", state, 51_000_000.0, "test_strategy") }
    }

    @Test
    fun `processTicker sells then returns without evaluating buy in same tick`() = runTest {
        val engine = createEngine()
        // boughtToday=false 로 둬 sell 후 return 만을 격리 검증(프로덕션은 boughtToday 게이트로 이중 방어).
        val state = TradingState("KRW-BTC", position = true)
        every { webSocketClient.latestPrice(any()) } returns null
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true // return 제거 시 buy 도달 보장
        every { positionManager.checkStopLoss(any(), any()) } returns true
        coEvery { positionManager.sell("KRW-BTC", state, any(), SellReason.STOP_LOSS) } answers {
            state.markSold()
            tradeRec(TradeSide.SELL)
        }

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify { positionManager.sell("KRW-BTC", state, 100.0, SellReason.STOP_LOSS) }
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `processTicker after pending buy reconcile does not sell in same tick`() = runTest {
        val engine = createEngine()
        val state = TradingState("KRW-BTC", pendingBuyUuid = "uuid-buy")
        every { webSocketClient.latestPrice(any()) } returns null
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        every { positionManager.checkStopLoss(any(), any()) } returns true // 재평가되면 sell 트리거
        coEvery { positionManager.reconcilePendingBuy("KRW-BTC", state, any()) } answers {
            state.markBought(100.0, 1.0, "test_strategy") // position=true, pendingBuyUuid=null
            tradeRec(TradeSide.BUY)
        }

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify { positionManager.reconcilePendingBuy("KRW-BTC", state, 100.0) }
        coVerify(exactly = 0) { positionManager.sell(any(), any(), any(), any()) }
    }

    @Test
    fun `processTicker skips buy while pending buy is unresolved`() = runTest {
        val engine = createEngine()
        val state = TradingState("KRW-BTC", pendingBuyUuid = "uuid-buy")
        every { webSocketClient.latestPrice(any()) } returns null
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        coEvery { positionManager.reconcilePendingBuy("KRW-BTC", state, any()) } returns null // 미해소, uuid 유지

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `processTicker skips sell while pending sell is unresolved`() = runTest {
        val engine = createEngine()
        val state = TradingState("KRW-BTC", position = true, pendingSellUuid = "uuid-sell")
        every { webSocketClient.latestPrice(any()) } returns null
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        every { positionManager.checkStopLoss(any(), any()) } returns true // 재평가되면 sell 트리거
        coEvery { positionManager.reconcilePendingSell("KRW-BTC", state, any()) } returns null // 미해소, uuid 유지

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 0) { positionManager.sell(any(), any(), any(), any()) }
    }

    @Test
    fun `processTicker skips buy when already bought today`() = runTest {
        val engine = createEngine()
        val state = TradingState("KRW-BTC", position = false, boughtToday = true)
        every { webSocketClient.latestPrice(any()) } returns null
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `processTicker after pending sell reconcile does not buy in same tick`() = runTest {
        val engine = createEngine()
        // 전날 보유분 청산(position=true, boughtToday=false) — reconciled 후 return 이 없으면
        // markSold 로 position=false 가 돼 같은 tick 에 신규 매수까지 흘러간다(pendingBuy S3 의 매도판 대칭).
        val state = TradingState("KRW-BTC", position = true, pendingSellUuid = "uuid-sell")
        every { webSocketClient.latestPrice(any()) } returns null
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true // return 제거 시 buy 도달 보장
        coEvery { positionManager.reconcilePendingSell("KRW-BTC", state, any()) } answers {
            state.markSold() // position=false, boughtToday 미변경(false 유지)
            tradeRec(TradeSide.SELL)
        }

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify { positionManager.reconcilePendingSell("KRW-BTC", state, 100.0) }
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `processTicker retries syncPosition when state is unsynced`() = runTest {
        val engine = createEngine()
        val state = TradingState("KRW-BTC", unsynced = true)
        every { webSocketClient.latestPrice(any()) } returns null
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns false

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify { positionManager.syncPosition("KRW-BTC", state) }
    }

    // chartExit 평가의 데이터 조회 실패가 가격 안전망/매수까지 막지 않도록 격리되는지 (REST 예외 전파 방지).
    @Test
    fun `decideSell isolates chartExit evaluation exception`() = runBlocking {
        val engine = createEngine(props = chartEnabledProps)
        val state = sellState()
        every { positionManager.checkStopLoss(state, any()) } returns false
        every { positionManager.checkTrailingStop(state, any()) } returns false
        every { positionManager.checkTakeProfit(state, any()) } returns false
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns emptyList()
        coEvery { upbitClient.getDayCandles(any(), any()) } throws RuntimeException("rate limit")
        every { dailyResetManager.shouldSellForDailyReset(state) } returns true
        // 예외가 전파되지 않고 dailyReset 안전망까지 평가됨
        assertEquals(SellReason.DAILY_RESET, engine.decideSell(state, 100.0, "KRW-BTC", VolatilityBreakout()))
    }

    // --- evaluateChartExit: store D1 distinct + REST 폴백 (D1 은 CandleAggregator 가 같은 날 반복 ingest) ---

    private fun deadCrossLegacy(): List<Candle> =
        listOf(Candle(tradePrice = 50.0)) + (1..20).map { i -> Candle(tradePrice = 200.0 - i * 2.0) }

    // openTime 을 하루씩 다르게 — distinctBy{openTime} 후에도 21개 유지([0]=최신).
    private fun deadCrossNormalized(): List<NormalizedCandle> =
        (listOf(50.0) + (1..20).map { 200.0 - it * 2.0 }).mapIndexed { idx, p ->
            NormalizedCandle(
                Exchange.UPBIT, "KRW-BTC", p, p, p, p, 1.0,
                openTime = Instant.ofEpochSecond(1_700_000_000L - idx * 86_400L),
            )
        }

    @Test
    fun `evaluateChartExit uses store candles without REST when distinct sufficient`() = runBlocking {
        val engine = createEngine()
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns deadCrossNormalized()
        assertTrue(engine.evaluateChartExit("KRW-BTC", 50.0, VolatilityBreakout()))
        coVerify(exactly = 0) { upbitClient.getDayCandles(any(), any()) }
    }

    @Test
    fun `evaluateChartExit falls back to REST when store candles polluted`() = runBlocking {
        // 같은 openTime 30개(같은 날 누적) → distinct 후 1개 < 21 → REST 폴백.
        val engine = createEngine()
        val polluted = (1..30).map {
            NormalizedCandle(Exchange.UPBIT, "KRW-BTC", 100.0, 100.0, 100.0, 100.0, 1.0, openTime = Instant.EPOCH)
        }
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns polluted
        coEvery { upbitClient.getDayCandles("KRW-BTC", 60) } returns deadCrossLegacy()
        assertTrue(engine.evaluateChartExit("KRW-BTC", 50.0, VolatilityBreakout()))
        coVerify { upbitClient.getDayCandles("KRW-BTC", 60) }
    }

    @Test
    fun `evaluateChartExit returns false when candles insufficient`() = runBlocking {
        val engine = createEngine()
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns emptyList()
        coEvery { upbitClient.getDayCandles("KRW-BTC", 60) } returns listOf(Candle(tradePrice = 100.0))
        assertFalse(engine.evaluateChartExit("KRW-BTC", 50.0, VolatilityBreakout()))
    }

    // --- loadStoreDailyCandles: 매수·청산 공통 D1 게이트 (distinct + size>=MIN_DAILY_CANDLES, 부족 시 null) ---

    @Test
    fun `loadStoreDailyCandles returns store candles when distinct sufficient`() {
        val engine = createEngine()
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns deadCrossNormalized()
        assertEquals(21, engine.loadStoreDailyCandles("KRW-BTC")?.size)
    }

    @Test
    fun `loadStoreDailyCandles returns null when polluted below threshold`() {
        // 같은 openTime 30개 → distinct 후 1개 < 21 → null(호출측 REST 폴백).
        val engine = createEngine()
        val polluted = (1..30).map {
            NormalizedCandle(Exchange.UPBIT, "KRW-BTC", 100.0, 100.0, 100.0, 100.0, 1.0, openTime = Instant.EPOCH)
        }
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns polluted
        assertNull(engine.loadStoreDailyCandles("KRW-BTC"))
    }

    @Test
    fun `loadStoreDailyCandles returns null when store has too few candles`() {
        val engine = createEngine()
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns deadCrossNormalized().take(10)
        assertNull(engine.loadStoreDailyCandles("KRW-BTC"))
    }

    @Test
    fun `loadStoreDailyCandles returns null when store absent`() {
        val engine = TradingEngine(
            upbitClient, positionManager, dailyResetManager, tradeExecutionService,
            listOf(strategy), tradingProperties,
        )
        assertNull(engine.loadStoreDailyCandles("KRW-BTC"))
    }

    // --- resolveExitStrategy: 청산을 진입 전략으로 (entryStrategy 복원 + 폴백) ---

    @Test
    fun `resolveExitStrategy uses entryStrategy when present`() {
        val macd = MacdCross()
        val golden = GoldenCross()
        val engine = createEngine(strategies = listOf(macd, golden))
        val state = TradingState("KRW-BTC").apply { markBought(100.0, 1.0, "macd_cross") }
        assertEquals("macd_cross", engine.resolveExitStrategy(state, golden).name)
    }

    @Test
    fun `resolveExitStrategy falls back to active when entryStrategy null`() {
        val golden = GoldenCross()
        val engine = createEngine(strategies = listOf(golden))
        val state = TradingState("KRW-BTC") // entryStrategy null (재시작 syncPosition 복원 시뮬)
        assertEquals(golden.name, engine.resolveExitStrategy(state, golden).name)
    }

    @Test
    fun `resolveExitStrategy falls back when entryStrategy not in list`() {
        val golden = GoldenCross()
        val engine = createEngine(strategies = listOf(golden))
        val state = TradingState("KRW-BTC").apply { markBought(100.0, 1.0, "removed_strategy") }
        assertEquals(golden.name, engine.resolveExitStrategy(state, golden).name)
    }

    // --- getRealtimePrice: store staleness 가드 (이슈 #27 — 얼어붙은 store 가격으로 매매 판단 방지) ---

    private fun storeTicker(price: Double, ageSeconds: Long) = NormalizedTicker(
        exchange = Exchange.UPBIT,
        market = "BTC/KRW",
        price = price,
        timestamp = Instant.now().minusSeconds(ageSeconds),
    )

    private fun wsPrice(price: Double, ageMs: Long = 0) = RealtimePrice(
        market = "KRW-BTC",
        tradePrice = price,
        signedChangeRate = 0.0,
        accTradePrice24h = 0.0,
        timestamp = System.currentTimeMillis() - ageMs,
    )

    @Test
    fun `getRealtimePrice uses fresh store ticker`() {
        val engine = createEngine()
        every { marketDataStore.getLatestTicker(any(), any()) } returns storeTicker(70000000.0, ageSeconds = 1)

        assertEquals(70000000.0, engine.getRealtimePrice("KRW-BTC"))
    }

    @Test
    fun `getRealtimePrice falls back to WS when store ticker stale`() {
        val engine = createEngine()
        every { marketDataStore.getLatestTicker(any(), any()) } returns storeTicker(69000000.0, ageSeconds = 60)
        every { webSocketClient.latestPrice("KRW-BTC") } returns wsPrice(70500000.0)

        assertEquals(70500000.0, engine.getRealtimePrice("KRW-BTC"))
    }

    @Test
    fun `getRealtimePrice returns null when store and WS both stale`() {
        val engine = createEngine()
        every { marketDataStore.getLatestTicker(any(), any()) } returns storeTicker(69000000.0, ageSeconds = 60)
        every { webSocketClient.latestPrice("KRW-BTC") } returns wsPrice(70500000.0, ageMs = 60_000)

        assertNull(engine.getRealtimePrice("KRW-BTC")) // null → processTicker 의 REST 폴백 경로
    }

    @Test
    fun `getRealtimePrice staleness boundary around 30s`() {
        val engine = createEngine()
        // 25s — threshold(30s) 안쪽 (5s 는 느린 CI 대비 테스트 실행 마진)
        every { marketDataStore.getLatestTicker(any(), any()) } returns storeTicker(70000000.0, ageSeconds = 25)
        assertEquals(70000000.0, engine.getRealtimePrice("KRW-BTC"))

        // 32s — threshold 바깥, WS 도 없음 → null
        every { marketDataStore.getLatestTicker(any(), any()) } returns storeTicker(70000000.0, ageSeconds = 32)
        every { webSocketClient.latestPrice("KRW-BTC") } returns null
        assertNull(engine.getRealtimePrice("KRW-BTC"))
    }
}
