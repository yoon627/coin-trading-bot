package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.Ticker
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.ShadowExitObservationRepository
import com.trading.bot.persistence.entity.ShadowExitObservationEntity
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import com.trading.common.strategy.TradingStrategy
import com.trading.common.strategy.CombinedStrategy
import io.mockk.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
    private lateinit var strategy: TradingStrategy
    private lateinit var marketDataStore: MarketDataStore
    private val tradingProperties = TradingProperties(intervalSeconds = 1)

    @BeforeEach
    fun setup() {
        upbitClient = mockk(relaxed = true)
        positionManager = mockk(relaxed = true)
        dailyResetManager = mockk(relaxed = true)
        strategy = mockk()
        marketDataStore = mockk(relaxed = true)
        // store miss 기본값 — relaxed mock 이 non-null child mock 을 반환해 store-hit 으로 오작동하는 것 방지
        // (가격 경로는 store→REST 2단).
        every { marketDataStore.getLatestTicker(any(), any()) } returns null
        every { strategy.name } returns "test_strategy"
        every { strategy.minCandles } returns 21
        every { dailyResetManager.checkAndReset(any()) } returns false
        every { dailyResetManager.shouldSellForDailyReset(any()) } returns false
    }

    private fun namedStrategy(strategyName: String): TradingStrategy = mockk {
        every { name } returns strategyName
        every { minCandles } returns 21
    }

    private fun createEngine(
        strategies: List<TradingStrategy> = listOf(strategy),
        props: TradingProperties = tradingProperties,
        clock: Clock = Clock.systemUTC(),
    ): TradingEngine {
        return TradingEngine(
            upbitClient = upbitClient,
            positionManager = positionManager,
            dailyResetManager = dailyResetManager,
            strategies = strategies,
            tradingProperties = props,
            userId = 1L,
            username = "testuser",
            discordWebhookUrl = null,
            marketDataStore = marketDataStore,
            clock = clock,
        )
    }

    @Test
    fun `start sets engine to running`(): Unit = runBlocking {
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
    fun `flush after stop rewrites pending the loop could not record and reports the sells still unwritten`() = runBlocking {
        // 재기록은 다음 tick 몫이라 멈춘 엔진에서는 일어나지 않는다 — 그 뒤 DB 만 읽는 reload·재시작이 그 매도를 모른다(#244).
        // 루프가 도는 동안은 DB 가 죽어 있고 stop 뒤에만 살아나게 해, 플래그가 내려갔다면 flush 가 내린 것이다.
        val dbUp = AtomicBoolean(false)
        val rewritten = mutableSetOf<String>()
        coEvery { positionManager.retryPendingPersistIfNeeded(any()) } coAnswers {
            val state = firstArg<TradingState>()
            if (dbUp.get()) {
                rewritten += state.ticker
                if (state.ticker == "KRW-BTC") state.pendingPersistFailed = false
            }
        }
        val engine = createEngine()
        engine.start(
            listOf("KRW-BTC", "KRW-SOL", "KRW-ETH"),
            mapOf(
                "KRW-BTC" to TradingState("KRW-BTC", position = true, pendingSellIdentifier = "ctb-btc", pendingPersistFailed = true),
                "KRW-SOL" to TradingState("KRW-SOL", position = true, pendingSellUuid = "u-sol", pendingPersistFailed = true),
                // 매수는 선기록이 성공해야 보내므로 DB 에 identifier 가 있다 — 기록 실패로 남아도 새 엔진이 확정한다.
                "KRW-ETH" to TradingState("KRW-ETH", pendingBuyUuid = "u-eth", pendingPersistFailed = true),
            ),
        )
        engine.stop()
        dbUp.set(true)

        engine.flushUnpersisted()

        assertEquals(setOf("KRW-BTC", "KRW-SOL", "KRW-ETH"), rewritten)
        assertEquals(listOf("KRW-SOL"), engine.unpersistedSells().map { it.ticker })
    }

    @Test
    fun `flush after stop rewrites every pending before peaks and writes each state at most once`() = runBlocking {
        // 고점 재기록도 다음 tick 몫이다 — 멈춘 엔진의 states 를 DB 값으로 덮으면 트레일링 기준선이 뒤로 물러난다(#54).
        // 고점은 pending 을 모두 쓴 뒤에 쓴다: 고점 쓰기가 호출자의 시간 상한을 먹어 매도 기록이 밀리면 안 된다.
        // 맵 순회 순서는 SOL·BTC·XRP·ETH 다 — 고점만 남은 BTC 가 pending 인 XRP 보다 앞서, state 마다 번갈아 쓰면 순서 단언이 잡는다.
        val dbUp = AtomicBoolean(false)
        val writes = mutableListOf<String>()
        coEvery { positionManager.retryPendingPersistIfNeeded(any()) } coAnswers {
            val state = firstArg<TradingState>()
            if (dbUp.get() && state.pendingPersistFailed) {
                writes += "pending:${state.ticker}"
                // 스냅샷 쓰기라 성공하면 고점 플래그도 내려간다(upsertState). XRP 행만 계속 실패한다.
                if (state.ticker != "KRW-XRP") {
                    state.pendingPersistFailed = false
                    state.peakPersistFailed = false
                }
            }
        }
        coEvery { positionManager.persistPeak(any()) } coAnswers {
            val state = firstArg<TradingState>()
            if (dbUp.get()) {
                writes += "peak:${state.ticker}"
                state.peakPersistFailed = false
            }
        }
        val engine = createEngine()
        engine.start(
            listOf("KRW-BTC", "KRW-ETH", "KRW-SOL", "KRW-XRP"),
            mapOf(
                "KRW-BTC" to TradingState("KRW-BTC", position = true, peakPersistFailed = true),
                "KRW-ETH" to TradingState("KRW-ETH", peakPersistFailed = true),
                "KRW-SOL" to TradingState(
                    "KRW-SOL", position = true, pendingSellIdentifier = "ctb-sol", pendingPersistFailed = true, peakPersistFailed = true,
                ),
                "KRW-XRP" to TradingState(
                    "KRW-XRP", position = true, pendingSellIdentifier = "ctb-xrp", pendingPersistFailed = true, peakPersistFailed = true,
                ),
            ),
        )
        engine.stop()
        dbUp.set(true)

        engine.flushUnpersisted()

        assertEquals(listOf("pending:KRW-SOL", "pending:KRW-XRP"), writes.filter { it.startsWith("pending:") }.sorted())
        // SOL 은 재기록이 고점까지 썼다. XRP 는 같은 스냅샷을 한 flush 에서 두 번 시도하지 않는다.
        assertEquals(listOf("peak:KRW-BTC", "peak:KRW-ETH"), writes.filter { it.startsWith("peak:") }.sorted())
        assertTrue(
            writes.indexOfLast { it.startsWith("pending:") } < writes.indexOfFirst { it.startsWith("peak:") },
            "pending 을 모두 쓴 뒤에 고점을 써야 한다: $writes",
        )
    }

    @Test
    fun `start is idempotent`(): Unit = runBlocking {
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
    fun `a new engine starts with the first registered strategy`() {
        // 이름이 아니라 등록 순서가 기본 전략을 정한다 — 상태 API 가 엔진이 없을 때 보고하는 전략과 같은 규칙이고,
        // 둘이 갈리면 정지 뒤 시작이 다른 전략으로 돈다.
        val combined = namedStrategy("combined")
        assertEquals("test_strategy", createEngine(strategies = listOf(strategy, combined)).getActiveStrategyName())
        assertEquals("combined", createEngine(strategies = listOf(combined, strategy)).getActiveStrategyName())
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
        assertEquals(SellReason.TAKE_PROFIT, engine.decideSell(state, 100.0, "KRW-BTC", CombinedStrategy()))
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
        assertEquals(SellReason.CHART_EXIT, engine.decideSell(state, 50.0, "KRW-BTC", CombinedStrategy()))
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
        TradeRecord(
            ticker = "KRW-BTC", side = side, price = 100.0, volume = 1.0, totalAmount = 100.0,
            pnlPercent = null, pnlAmount = null, strategy = "combined",
            fee = FeeBasis.Estimate,
            orderAmount = null,
        )

    @Test
    fun `processTicker persists a new peak so trailing stop survives restart`() = runTest {
        // peakPrice 를 안 남기면 재시작 후 peak 이 현재가에서 다시 쌓여, 이미 발동했어야 할 트레일링 스톱이 안 걸린다.
        val engine = createEngine()
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.001, peakPrice = 51_000_000.0)
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 52_000_000.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()

        engine.processTicker("KRW-BTC", state, strategy)

        assertEquals(52_000_000.0, state.peakPrice)
        coVerify(exactly = 1) { positionManager.persistPeak(state) }
    }

    @Test
    fun `a dirty tick that also sets a new high persists only once`() = runTest {
        // 재시도와 갱신 flush 를 따로 두면 같은 tick 에 upsert 가 두 번 난다.
        val engine = createEngine()
        val state = TradingState(
            "KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.001,
            peakPrice = 51_000_000.0, peakPersistFailed = true,
        )
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 52_000_000.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()

        engine.processTicker("KRW-BTC", state, strategy)

        assertEquals(52_000_000.0, state.peakPrice)
        coVerify(exactly = 1) { positionManager.persistPeak(state) }
    }

    @Test
    fun `processTicker does not persist when the peak is unchanged`() = runTest {
        // 매 tick upsert 는 write 증폭 — 갱신된 tick 에만 flush 한다.
        val engine = createEngine()
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.001, peakPrice = 53_000_000.0)
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 52_000_000.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 0) { positionManager.persistPeak(any()) }
    }

    @Test
    fun `processTicker retries a failed peak flush even without a new high`() = runTest {
        // 갱신 tick 에만 flush 하므로, 실패를 흘리면 하락 전환 후에는 재기록 기회가 없다.
        // 그대로 두면 재시작 시 낮은 옛 peak 이 복원돼 트레일링이 안 걸린다(#54).
        val engine = createEngine()
        val state = TradingState(
            "KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.001,
            peakPrice = 53_000_000.0, peakPersistFailed = true,
        )
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 52_000_000.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 1) { positionManager.persistPeak(state) } // 신고점이 아닌데도 재시도
    }

    @Test
    fun `peak retry runs even while a pending sell blocks the rest of the tick`() = runTest {
        // 미해소 매도가 있으면 tick 이 조기 return 해 flush 지점에 도달하지 못한다. 재시도를
        // 그 뒤에 두면 미해소가 길어지는 동안 dirty 가 계속 남는다(#54).
        val engine = createEngine()
        val state = TradingState(
            "KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.001,
            peakPrice = 53_000_000.0, peakPersistFailed = true, pendingSellUuid = "s1",
        )
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 52_000_000.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        coEvery { positionManager.reconcilePendingSell(any(), any(), any()) } returns null

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 1) { positionManager.persistPeak(state) }
    }

    @Test
    fun `processTicker buys using REST price when store misses`() = runTest {
        val engine = createEngine()
        val state = TradingState("KRW-BTC")
        // store mock=null(setup) → getRealtimePrice null → REST(getTicker) 폴백
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 51_000_000.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true

        engine.processTicker("KRW-BTC", state, strategy)

        // REST 가격이 buy 로 그대로 전달 — 삭제한 delay(3000) 테스트의 REST 폴백 커버리지 대체.
        coVerify { positionManager.buy("KRW-BTC", state, 51_000_000.0, "test_strategy") }
    }

    // --- 매도 불가 dust (#234) ---
    // 거래소 최소주문(5,000원) 미만 보유는 팔 수 없다 — 그 티커의 진입을 막지 않는다. 주문 여부는 PositionManager 가 실잔고로 정한다.

    private fun observedEngine(observer: ShadowExitObserver) = TradingEngine(
        upbitClient = upbitClient,
        positionManager = positionManager,
        dailyResetManager = dailyResetManager,
        strategies = listOf(strategy),
        tradingProperties = tradingProperties,
        userId = 1L,
        username = "testuser",
        discordWebhookUrl = null,
        marketDataStore = marketDataStore,
        shadowExitObserver = observer,
    )

    @Test
    fun `a dust holding does not block a new entry and is not observed as a position`() = runTest {
        val observer = mockk<ShadowExitObserver>(relaxed = true)
        val engine = observedEngine(observer)
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 12_000_000.0, holdVolume = 0.0001) // 1,000원
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 10_000_000.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify { positionManager.buy("KRW-BTC", state, 10_000_000.0, "test_strategy") }
        coVerify(exactly = 0) { observer.onTick(any(), any(), any()) }
    }

    @Test
    fun `a dust holding still goes through exit evaluation`() = runTest {
        // 기록상 수량이 낡았을 수 있다 — 청산 평가를 끄면 실제로 팔 수 있는 포지션이 손절을 잃는다.
        val engine = createEngine()
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 12_000_000.0, holdVolume = 0.0001)
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 10_000_000.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        every { positionManager.checkStopLoss(any(), any()) } returns true
        coEvery { positionManager.sell(any(), any(), any(), any()) } returns null // 가드가 주문하지 않았다

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify { positionManager.sell("KRW-BTC", state, 10_000_000.0, SellReason.STOP_LOSS) }
        coVerify { positionManager.buy("KRW-BTC", state, 10_000_000.0, "test_strategy") }
    }

    @Test
    fun `a sellable or unknown-size holding keeps the entry gate closed`() = runTest {
        val engine = createEngine()
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 10_000_000.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true

        engine.processTicker("KRW-BTC", TradingState("KRW-BTC", position = true, holdVolume = 0.01), strategy) // 10만원
        engine.processTicker("KRW-BTC", TradingState("KRW-BTC", position = true), strategy) // 수량 미상

        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `processTicker sells then returns without evaluating buy in same tick`() = runTest {
        val engine = createEngine()
        // boughtToday=false 로 둬 sell 후 return 만을 격리 검증(프로덕션은 boughtToday 게이트로 이중 방어).
        val state = TradingState("KRW-BTC", position = true)
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
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        coEvery { positionManager.reconcilePendingBuy("KRW-BTC", state, any()) } returns null // 미해소, uuid 유지

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `processTicker treats an order known only by identifier as pending`() = runTest {
        // 응답을 못 받아 uuid 가 없는 주문도 미해소다 — 평가를 돌리면 같은 주문을 한 번 더 낸다(#227).
        val engine = createEngine()
        val buying = TradingState("KRW-BTC", pendingBuyIdentifier = "ctb-b")
        val selling = TradingState("KRW-BTC", position = true, pendingSellIdentifier = "ctb-s")
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        every { positionManager.checkStopLoss(any(), any()) } returns true
        coEvery { positionManager.reconcilePendingBuy(any(), any(), any()) } returns null
        coEvery { positionManager.reconcilePendingSell(any(), any(), any()) } returns null

        engine.processTicker("KRW-BTC", buying, strategy)
        engine.processTicker("KRW-BTC", selling, strategy)

        coVerify { positionManager.reconcilePendingBuy("KRW-BTC", buying, 100.0) }
        coVerify { positionManager.reconcilePendingSell("KRW-BTC", selling, 100.0) }
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
        coVerify(exactly = 0) { positionManager.sell(any(), any(), any(), any()) }
    }

    @Test
    fun `processTicker skips sell while pending sell is unresolved`() = runTest {
        val engine = createEngine()
        val state = TradingState("KRW-BTC", position = true, pendingSellUuid = "uuid-sell")
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

    // reconcile 로 늦게 확정된 청산도 그림자 관측에 보고한다 — 빠지면 #178 표본이 체결 확인 창 안에 끝난 매도만 담는다(#235).

    @Test
    fun `a swing exit filled at once is reported to the shadow observer at the tick price`() = runTest {
        val observer = mockk<ShadowExitObserver>(relaxed = true)
        val engine = observedEngine(observer)
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 120.0, holdVolume = 1_000.0)
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        every { positionManager.checkStopLoss(any(), any()) } returns true
        coEvery { positionManager.sell("KRW-BTC", state, 100.0, SellReason.STOP_LOSS) } returns
            tradeRec(TradeSide.SELL).copy(reason = "STOP_LOSS", executedVwap = 99.0)

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 1) { observer.onLiveExit("KRW-BTC", 100.0, "STOP_LOSS", 99.0, any()) }
    }

    @Test
    fun `a swing exit confirmed by reconcile is reported to the shadow observer at its decision price and time`() = runTest {
        val observer = mockk<ShadowExitObserver>(relaxed = true)
        val engine = observedEngine(observer)
        val decidedAt = Instant.parse("2026-09-28T00:00:00Z")
        val state = TradingState("KRW-BTC", position = true, pendingSellUuid = "uuid-sell", pendingSellSince = decidedAt)
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 90.0))
        coEvery { positionManager.reconcilePendingSell("KRW-BTC", state, any()) } answers {
            state.clearPendingSell()
            state.markSold()
            tradeRec(TradeSide.SELL).copy(price = 100.0, reason = "STOP_LOSS", executedVwap = 99.5)
        }

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 1) { observer.onLiveExit("KRW-BTC", 100.0, "STOP_LOSS", 99.5, decidedAt) }
    }

    @Test
    fun `a trailing fired before a late-confirmed exit is saved with the decision price and time`() = runTest {
        // 발동 기록이 pending 동안 살아 있다가 reconcile 확정에서 저장되는지 — 실제 관측기로 두 tick 을 잇는다.
        val repo = mockk<ShadowExitObservationRepository>()
        val saved = slot<ShadowExitObservationEntity>()
        every { repo.save(capture(saved)) } returns reactor.core.publisher.Mono.empty()
        val decidedAt = Instant.parse("2026-09-28T00:00:00Z")
        val observer = ShadowExitObserver(
            repo, userId = 1L, trailingStopPct = 1.5, trailingArmPct = 0.0,
            clock = Clock.fixed(decidedAt.plusSeconds(3_600), ZoneOffset.UTC), // 확정은 한 시간 뒤
        )
        val engine = observedEngine(observer)
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 100.0, peakPrice = 110.0, holdVolume = 1_000.0)
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns emptyList()
        every { positionManager.checkStopLoss(any(), any()) } returns true
        coEvery { positionManager.sell("KRW-BTC", state, 108.0, SellReason.STOP_LOSS) } answers {
            state.pendingSellUuid = "late" // 체결 확인 창을 넘겼다
            state.pendingSellSince = decidedAt
            null
        }
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 108.0)) // 고점 대비 −1.8% — 후보 발동
        engine.processTicker("KRW-BTC", state, strategy)

        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 95.0))
        coEvery { positionManager.reconcilePendingSell("KRW-BTC", state, any()) } answers {
            state.clearPendingSell()
            state.markSold()
            tradeRec(TradeSide.SELL).copy(price = 108.0, reason = "STOP_LOSS", executedVwap = 107.6)
        }
        engine.processTicker("KRW-BTC", state, strategy)

        assertEquals(108.0, saved.captured.observedTickPrice)
        assertEquals(108.0, saved.captured.liveExitPrice)
        assertEquals(107.6, saved.captured.liveExitVwap)
        assertEquals(decidedAt, saved.captured.liveExitAt)
    }

    @Test
    fun `a partial fill confirmed by reconcile is not reported as the exit`() = runTest {
        // 포지션이 남았다 — 발동 기록을 여기서 쓰면 원 포지션의 청산과 짝이 깨진다. 잔량이 팔리는 확정에서 보고한다.
        val observer = mockk<ShadowExitObserver>(relaxed = true)
        val engine = observedEngine(observer)
        val state = TradingState("KRW-BTC", position = true, holdVolume = 1.0, pendingSellUuid = "uuid-sell")
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 90.0))
        coEvery { positionManager.reconcilePendingSell("KRW-BTC", state, any()) } answers {
            state.clearPendingSell()
            state.holdVolume = 0.4
            tradeRec(TradeSide.SELL).copy(reason = "STOP_LOSS")
        }

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 0) { observer.onLiveExit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `processTicker retries syncPosition when state is unsynced`() = runTest {
        val engine = createEngine()
        val state = TradingState("KRW-BTC", unsynced = true)
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
        assertEquals(SellReason.DAILY_RESET, engine.decideSell(state, 100.0, "KRW-BTC", CombinedStrategy()))
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
        assertTrue(engine.evaluateChartExit("KRW-BTC", 50.0, CombinedStrategy()))
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
        assertTrue(engine.evaluateChartExit("KRW-BTC", 50.0, CombinedStrategy()))
        coVerify { upbitClient.getDayCandles("KRW-BTC", 60) }
    }

    @Test
    fun `evaluateChartExit returns false when candles insufficient`() = runBlocking {
        val engine = createEngine()
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns emptyList()
        coEvery { upbitClient.getDayCandles("KRW-BTC", 60) } returns listOf(Candle(tradePrice = 100.0))
        assertFalse(engine.evaluateChartExit("KRW-BTC", 50.0, CombinedStrategy()))
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
            upbitClient, positionManager, dailyResetManager,
            listOf(strategy), tradingProperties,
        )
        assertNull(engine.loadStoreDailyCandles("KRW-BTC"))
    }

    // --- 09:00 경계 stale window: store 최신 D1 이 오늘 거래일 봉일 때만 매수 판정 ---

    // 오늘 봉이 [0], 하루씩 과거로 21개. openTime 은 CandleAggregator 의 D1 정렬(UTC 자정 = KST 09:00)과 같다.
    private fun dailyNormalized(newest: LocalDate): List<NormalizedCandle> =
        (0 until 21).map { idx ->
            NormalizedCandle(
                Exchange.UPBIT, "KRW-BTC", 100.0, 100.0, 100.0, 100.0, 1.0,
                openTime = newest.minusDays(idx.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant(),
            )
        }

    private val today: LocalDate = LocalDate.of(2026, 9, 8)

    // 거래일 경계(KST 09:00 = UTC 자정)에서 [secondsAfter] 지난 고정 시계.
    private fun clockAfterBoundary(secondsAfter: Long): Clock =
        Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(secondsAfter), ZoneOffset.UTC)

    @Test
    fun `runSwing skips buy evaluation while store newest D1 is still yesterday's`() = runTest {
        // 09:00 직후 새 날 첫 1분봉이 오기 전 — store window 는 어제 봉으로 끝나고, 그 신호는 어제 매수를 만든 신호 그대로다.
        val engine = createEngine(clock = clockAfterBoundary(30))
        every { dailyResetManager.getTradingDate() } returns today
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns dailyNormalized(today.minusDays(1))
        coEvery { strategy.shouldBuyNormalized(any(), any(), any()) } returns true

        engine.processTicker("KRW-BTC", TradingState("KRW-BTC"), strategy)

        coVerify(exactly = 0) { strategy.shouldBuyNormalized(any(), any(), any()) }
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
        coVerify(exactly = 0) { upbitClient.getDayCandles(any(), any()) } // 경계 직후엔 REST 를 치지 않는다 — 다음 1분봉이 채운다
    }

    @Test
    fun `runSwing falls back to REST when store stays stale past the boundary grace`() = runTest {
        // 캔들 수집이 멈춘 티커: store 는 개수가 충분해 계속 반환되지만 어제 봉이다. 하루 종일 매수가 막히면 안 된다.
        val engine = createEngine(clock = clockAfterBoundary(10 * 60))
        every { dailyResetManager.getTradingDate() } returns today
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns dailyNormalized(today.minusDays(1))
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns dailyLegacy(today)
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true

        engine.processTicker("KRW-BTC", TradingState("KRW-BTC"), strategy)

        coVerify(exactly = 0) { strategy.shouldBuyNormalized(any(), any(), any()) }
        coVerify(exactly = 1) { positionManager.buy("KRW-BTC", any(), 100.0, "test_strategy") }
    }

    @Test
    fun `runSwing evaluates buy on store window once today's D1 exists`() = runTest {
        val engine = createEngine(clock = clockAfterBoundary(30))
        every { dailyResetManager.getTradingDate() } returns today
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns dailyNormalized(today)
        coEvery { strategy.shouldBuyNormalized(any(), any(), any()) } returns true

        engine.processTicker("KRW-BTC", TradingState("KRW-BTC"), strategy)

        coVerify(exactly = 1) { positionManager.buy("KRW-BTC", any(), 100.0, "test_strategy") }
    }

    // REST 일봉은 Upbit 형식 그대로 — candle_date_time_utc 가 UTC 자정.
    private fun dailyLegacy(newest: LocalDate): List<Candle> =
        (0 until 21).map { idx -> Candle(candleDateTimeUtc = "${newest.minusDays(idx.toLong())}T00:00:00", tradePrice = 100.0) }

    @Test
    fun `runSwing REST fallback also skips buy while newest daily candle is yesterday's`() = runTest {
        // store 부족(워밍업·watchlist 밖 티커) → REST. 그날 첫 체결 전 Upbit 일봉도 어제 봉이 [0] 이다.
        val engine = createEngine(clock = clockAfterBoundary(30))
        every { dailyResetManager.getTradingDate() } returns today
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns emptyList()
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns dailyLegacy(today.minusDays(1))
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true

        engine.processTicker("KRW-BTC", TradingState("KRW-BTC"), strategy)

        coVerify(exactly = 0) { strategy.shouldBuy(any(), any(), any()) }
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `runSwing REST fallback evaluates buy once today's daily candle exists`() = runTest {
        val engine = createEngine(clock = clockAfterBoundary(30))
        every { dailyResetManager.getTradingDate() } returns today
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        every { marketDataStore.getCandles(any(), any(), CandleInterval.D1, any()) } returns emptyList()
        coEvery { upbitClient.getDayCandles("KRW-BTC", any()) } returns dailyLegacy(today)
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true

        engine.processTicker("KRW-BTC", TradingState("KRW-BTC"), strategy)

        coVerify(exactly = 1) { positionManager.buy("KRW-BTC", any(), 100.0, "test_strategy") }
    }

    // --- resolveExitStrategy: 청산을 진입 전략으로 (entryStrategy 복원 + 폴백) ---

    @Test
    fun `resolveExitStrategy uses entryStrategy when present`() {
        val entry = namedStrategy("entry_strategy")
        val active = namedStrategy("active_strategy")
        val engine = createEngine(strategies = listOf(entry, active))
        val state = TradingState("KRW-BTC").apply { markBought(100.0, 1.0, "entry_strategy") }
        assertEquals("entry_strategy", engine.resolveExitStrategy(state, active).name)
    }

    @Test
    fun `resolveExitStrategy falls back to active when entryStrategy null`() {
        val active = namedStrategy("active_strategy")
        val engine = createEngine(strategies = listOf(active))
        val state = TradingState("KRW-BTC") // entryStrategy null (재시작 syncPosition 복원 시뮬)
        assertEquals(active.name, engine.resolveExitStrategy(state, active).name)
    }

    @Test
    fun `resolveExitStrategy falls back when entryStrategy not in list`() {
        val active = namedStrategy("active_strategy")
        val engine = createEngine(strategies = listOf(active))
        val state = TradingState("KRW-BTC").apply { markBought(100.0, 1.0, "removed_strategy") }
        assertEquals(active.name, engine.resolveExitStrategy(state, active).name)
    }

    // --- getRealtimePrice: store staleness 가드 (이슈 #27 — 얼어붙은 store 가격으로 매매 판단 방지) ---

    private fun storeTicker(price: Double, ageSeconds: Long) = NormalizedTicker(
        exchange = Exchange.UPBIT,
        market = "BTC/KRW",
        price = price,
        timestamp = Instant.now().minusSeconds(ageSeconds),
    )


    @Test
    fun `getRealtimePrice uses fresh store ticker`() {
        val engine = createEngine()
        every { marketDataStore.getLatestTicker(any(), any()) } returns storeTicker(70000000.0, ageSeconds = 1)

        assertEquals(70000000.0, engine.getRealtimePrice("KRW-BTC"))
    }

    @Test
    fun `getRealtimePrice returns null when store ticker stale`() {
        val engine = createEngine()
        every { marketDataStore.getLatestTicker(any(), any()) } returns storeTicker(69000000.0, ageSeconds = 60)

        assertNull(engine.getRealtimePrice("KRW-BTC")) // null → processTicker 의 REST 폴백 경로
    }

    @Test
    fun `getRealtimePrice staleness boundary around 30s`() {
        val engine = createEngine()
        // 25s — threshold(30s) 안쪽 (5s 는 느린 CI 대비 테스트 실행 마진)
        every { marketDataStore.getLatestTicker(any(), any()) } returns storeTicker(70000000.0, ageSeconds = 25)
        assertEquals(70000000.0, engine.getRealtimePrice("KRW-BTC"))

        // 32s — threshold 바깥 → null (processTicker 가 REST 로 폴백)
        every { marketDataStore.getLatestTicker(any(), any()) } returns storeTicker(70000000.0, ageSeconds = 32)
        assertNull(engine.getRealtimePrice("KRW-BTC"))
    }
}
