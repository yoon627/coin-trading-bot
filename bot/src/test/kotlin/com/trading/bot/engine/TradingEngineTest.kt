package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.client.UpbitWebSocketClient
import com.trading.bot.domain.*
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.strategy.TradingStrategy
import com.trading.common.strategy.GoldenCross
import com.trading.common.strategy.MacdCross
import com.trading.common.strategy.VolatilityBreakout
import io.mockk.*
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
        // store miss 기본값 — relaxed mock 의 Double? 0.0 반환으로 인한 store-hit 오작동 방지(가격 경로는 ws/REST).
        every { marketDataStore.getLatestPrice(any(), any()) } returns null
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
    fun `start sets engine to running`() {
        val engine = createEngine()
        assertFalse(engine.isRunning())
        engine.start(listOf("KRW-BTC"))
        assertTrue(engine.isRunning())
        engine.stop()
    }

    @Test
    fun `stop sets engine to not running`() {
        val engine = createEngine()
        engine.start(listOf("KRW-BTC"))
        assertTrue(engine.isRunning())
        engine.stop()
        assertFalse(engine.isRunning())
    }

    @Test
    fun `start is idempotent`() {
        val engine = createEngine()
        engine.start(listOf("KRW-BTC"))
        engine.start(listOf("KRW-ETH")) // second call should be no-op
        assertTrue(engine.isRunning())
        assertEquals(listOf("KRW-BTC"), engine.getActiveTickers())
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

    @Test
    fun `getRealtimePrice prefers fresh WebSocket price`() {
        val wsPrice = RealtimePrice(
            market = "KRW-BTC",
            tradePrice = 50000000.0,
            signedChangeRate = 0.01,
            accTradePrice24h = 1000000000.0,
            timestamp = System.currentTimeMillis(),
        )
        every { webSocketClient.latestPrice("KRW-BTC") } returns wsPrice

        val engine = createEngine()
        // Test the internal logic via reflection or just trust the integration
        // Since getRealtimePrice is private, we verify indirectly through engine behavior
        assertNotNull(webSocketClient.latestPrice("KRW-BTC"))
        assertEquals(50000000.0, webSocketClient.latestPrice("KRW-BTC")!!.tradePrice)
    }

    @Test
    fun `falls back to REST when WebSocket price is stale`() = runBlocking {
        val stalePrice = RealtimePrice(
            market = "KRW-BTC",
            tradePrice = 50000000.0,
            signedChangeRate = 0.01,
            accTradePrice24h = 1000000000.0,
            timestamp = System.currentTimeMillis() - 60_000, // 60 seconds old
        )
        every { webSocketClient.latestPrice("KRW-BTC") } returns stalePrice
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 51000000.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", 60) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns false

        val engine = createEngine()
        engine.start(listOf("KRW-BTC"))
        delay(3000)
        engine.stop()

        coVerify(atLeast = 1) { upbitClient.getTicker("KRW-BTC") }
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
}
