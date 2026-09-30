package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.Ticker
import com.trading.bot.domain.TradingState
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.AccumulateProperties
import com.trading.common.config.TradingProperties
import com.trading.common.config.UniverseProperties
import com.trading.common.strategy.TradingStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** 활성 티커 집합 교체(`applyTickers`)와 자동 유니버스 갱신. 선정 규칙 자체는 UniverseSelectorTest. */
class TradingEngineUniverseTest {

    private lateinit var upbitClient: UpbitClient
    private lateinit var positionManager: PositionManager
    private lateinit var dailyResetManager: DailyResetManager
    private lateinit var strategy: TradingStrategy
    private lateinit var marketDataStore: MarketDataStore

    @BeforeEach
    fun setup() {
        upbitClient = mockk(relaxed = true)
        positionManager = mockk(relaxed = true)
        dailyResetManager = mockk(relaxed = true)
        strategy = mockk()
        marketDataStore = mockk(relaxed = true)
        every { marketDataStore.getLatestTicker(any(), any()) } returns null
        every { strategy.name } returns "test_strategy"
        every { strategy.minCandles } returns 21
        every { dailyResetManager.checkAndReset(any()) } returns false
    }

    private fun createEngine(
        accumulate: AccumulateProperties = AccumulateProperties(),
        universe: UniverseProperties = UniverseProperties(),
        source: UniverseSource? = null,
    ) = TradingEngine(
        upbitClient = upbitClient,
        positionManager = positionManager,
        dailyResetManager = dailyResetManager,
        strategies = listOf(strategy),
        tradingProperties = TradingProperties(intervalSeconds = 1),
        userId = 1L,
        username = "testuser",
        marketDataStore = marketDataStore,
        accumulateProperties = accumulate,
        universeProperties = universe,
        universeSource = source,
    )

    private fun held(ticker: String) = TradingState(ticker, position = true, avgBuyPrice = 100.0, holdVolume = 1.0)

    /** 엔진이 산 포지션 — 진입 메타가 있어야 재시작 때 잔류로 실린다. */
    private fun heldEntry(ticker: String) =
        held(ticker).apply { entryStrategy = "test_strategy"; buyDate = LocalDate.of(2026, 9, 1) }

    private val engineLogger = LoggerFactory.getLogger(TradingEngine::class.java) as Logger
    private val engineLogs = ListAppender<ILoggingEvent>()

    @AfterEach
    fun detachLogs() {
        engineLogger.detachAppender(engineLogs)
    }

    private fun captureEngineLogs(): ListAppender<ILoggingEvent> {
        engineLogs.start()
        engineLogger.addAppender(engineLogs)
        return engineLogs
    }

    @Test
    fun `applyTickers keeps held or pending tickers, seeds new ones and drops flat ones`() = runBlocking {
        val engine = createEngine()
        engine.start(listOf("KRW-ETH", "KRW-X", "KRW-P"), mapOf("KRW-ETH" to held("KRW-ETH"), "KRW-P" to TradingState("KRW-P", pendingBuyUuid = "u")))
        engine.stop()

        engine.applyTickers(listOf("KRW-A"))

        assertEquals(listOf("KRW-ETH", "KRW-P", "KRW-A"), engine.getActiveTickers())
        assertEquals(setOf("KRW-ETH", "KRW-P", "KRW-A"), engine.getStates().keys)
        coVerify(exactly = 1) { positionManager.syncPosition("KRW-A", any()) }
    }

    @Test
    fun `applyTickers keeps a ticker whose order is known only by identifier`() = runBlocking {
        // 목록에서 빠진 티커의 불명 주문을 버리면 아무도 확정하지 않는다 — 재시작 잔류 판정도 같은 술어를 쓴다(#227).
        val engine = createEngine()
        engine.start(listOf("KRW-ETH", "KRW-Q"), mapOf("KRW-Q" to TradingState("KRW-Q", pendingSellIdentifier = "ctb-q")))
        engine.stop()

        engine.applyTickers(listOf("KRW-ETH"))

        assertEquals(setOf("KRW-ETH", "KRW-Q"), engine.getActiveTickers().toSet())
    }

    @Test
    fun `start keeps a ticker outside the list whose order is known only by identifier`() = runBlocking {
        val firstTick = CompletableDeferred<Unit>()
        coEvery { upbitClient.getTicker("KRW-BTC") } coAnswers { firstTick.complete(Unit); listOf(Ticker(tradePrice = 100.0)) }
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns false
        val engine = createEngine()

        engine.start(listOf("KRW-BTC"), linkedMapOf("KRW-Q" to TradingState("KRW-Q", pendingSellIdentifier = "ctb-q")))
        withTimeout(5_000) { firstTick.await() }
        engine.stop()

        assertEquals(listOf("KRW-BTC", "KRW-Q"), engine.getActiveTickers())
    }

    @Test
    fun `applyTickers seeds a new ticker from its durable state, not a blank one`() = runBlocking {
        // 재시작 전 남긴 pending uuid·halt 가 빈 상태로 덮이면 reconcile 이 영영 돌지 않고 다음 upsert 가 DB 행까지 지운다.
        val engine = createEngine()
        engine.start(listOf("KRW-ETH"))
        engine.stop()
        coEvery { positionManager.loadState("KRW-A") } returns TradingState("KRW-A", pendingBuyUuid = "orphan", halted = true)

        engine.applyTickers(listOf("KRW-A"))

        val seeded = engine.getStates().getValue("KRW-A")
        assertEquals("orphan", seeded.pendingBuyUuid)
        assertTrue(seeded.halted)
    }

    @Test
    fun `applyTickers caps the active set without dropping protected tickers`() = runBlocking {
        val engine = createEngine(accumulate = AccumulateProperties(tickers = "KRW-BTC"))
        engine.start(listOf("KRW-ETH"), mapOf("KRW-ETH" to held("KRW-ETH")))
        engine.stop()

        engine.applyTickers((1..25).map { "KRW-T$it" })

        val active = engine.getActiveTickers()
        assertEquals(20, active.size)
        assertEquals("KRW-BTC", active.first())
        assertTrue("KRW-ETH" in active)
        assertEquals((1..18).map { "KRW-T$it" }, active.drop(2))
    }

    @Test
    fun `refreshUniverse replaces swing tickers with the selection when auto is on`() = runBlocking {
        val source = mockk<UniverseSource>()
        coEvery { source.select(any(), any()) } returns listOf("KRW-A", "KRW-B")
        val engine = createEngine(
            accumulate = AccumulateProperties(tickers = "KRW-BTC"),
            universe = UniverseProperties(auto = true, altCount = 2),
            source = source,
        )
        engine.start(listOf("KRW-ETH"))
        engine.stop()

        assertTrue(engine.refreshUniverse())

        assertEquals(listOf("KRW-BTC", "KRW-A", "KRW-B"), engine.getActiveTickers())
        coVerify(atLeast = 1) { source.select(setOf("KRW-BTC"), 2) }
    }

    @Test
    fun `a ticker kept only because it was held does not re-enter after it is sold`() = runBlocking {
        val source = mockk<UniverseSource>()
        coEvery { source.select(any(), any()) } returns listOf("KRW-A")
        val engine = createEngine(universe = UniverseProperties(auto = true, altCount = 1), source = source)
        val eth = held("KRW-ETH")
        engine.start(listOf("KRW-ETH"), mapOf("KRW-ETH" to eth))
        engine.stop()
        engine.refreshUniverse()
        assertEquals(listOf("KRW-ETH", "KRW-A"), engine.getActiveTickers())

        // 청산됨 — 목록엔 아직 있지만 유니버스 밖이라 새로 사지 않는다.
        eth.markSold()
        coEvery { upbitClient.getTicker("KRW-ETH") } returns listOf(com.trading.bot.domain.Ticker(tradePrice = 100.0))
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        engine.processTicker("KRW-ETH", eth, strategy)

        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `start with auto universe seeds every durable ticker so held or pending ones survive a restart`() = runBlocking {
        // 자동 선정 티커는 bot_state.tickers 에 없다. 재시작 때 durable 행을 버리면 그 보유·pending 은 아무도 reconcile 하지 않는다.
        val source = mockk<UniverseSource>()
        coEvery { source.select(any(), any()) } returns listOf("KRW-A")
        val engine = createEngine(universe = UniverseProperties(auto = true, altCount = 1), source = source)

        engine.start(listOf("KRW-ETH"), mapOf("KRW-Z" to TradingState("KRW-Z", pendingBuyUuid = "orphan"), "KRW-OLD" to TradingState("KRW-OLD")))
        // 진입 흔적이 없는 잔재(OLD)는 애초에 싣지 않는다 — 유니버스가 회전할수록 쌓이는 행마다 계좌를 조회하지 않게.
        assertFalse("KRW-OLD" in engine.getActiveTickers())
        assertTrue("KRW-Z" in engine.getActiveTickers())
        engine.stop()

        engine.refreshUniverse()
        assertTrue("KRW-Z" in engine.getActiveTickers())
        assertFalse("KRW-OLD" in engine.getActiveTickers())
    }

    @Test
    fun `a durable row without entry metadata is revived on restart when the exchange still holds the coin`() = runBlocking {
        // 외부·수동 보유를 syncPosition 으로 편입한 포지션은 entryStrategy·buyDate 가 없다 — 잔고가 있으면 살려야 청산 평가를 받는다.
        val source = mockk<UniverseSource>()
        coEvery { source.select(any(), any()) } returns listOf("KRW-A")
        val checked = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { positionManager.heldCurrencies() } coAnswers { checked.complete(Unit); setOf("M") }
        // 되살린 뒤 syncPosition 이 실잔고로 position 을 세운다 — 그래야 첫 유니버스 갱신의 보호 집합에 든다.
        coEvery { positionManager.syncPosition("KRW-M", any()) } coAnswers { secondArg<TradingState>().position = true }
        val engine = createEngine(universe = UniverseProperties(auto = true, altCount = 1), source = source)

        engine.start(listOf("KRW-ETH"), mapOf("KRW-M" to TradingState("KRW-M"), "KRW-OLD" to TradingState("KRW-OLD")))
        checked.await()
        engine.stop()

        assertTrue("KRW-M" in engine.getActiveTickers())
        assertFalse("KRW-OLD" in engine.getActiveTickers())
    }

    @Test
    fun `dormant revival is retried on the next loop when the first account lookup fails`() = runBlocking {
        val source = mockk<UniverseSource>()
        coEvery { source.select(any(), any()) } returns listOf("KRW-A")
        val secondAttempt = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { positionManager.heldCurrencies() } throws RuntimeException("429") andThenAnswer { secondAttempt.complete(Unit); setOf("M") }
        coEvery { positionManager.syncPosition("KRW-M", any()) } coAnswers { secondArg<TradingState>().position = true }
        val engine = createEngine(universe = UniverseProperties(auto = true, altCount = 1), source = source)

        engine.start(listOf("KRW-ETH"), mapOf("KRW-M" to TradingState("KRW-M")))
        secondAttempt.await()
        engine.stop()

        assertTrue("KRW-M" in engine.getActiveTickers())
    }

    @Test
    fun `a dormant ticker the selection already seeded keeps its live state when the account lookup recovers`() = runBlocking {
        // 조회가 실패하는 동안 선정이 dormant 티커를 DB 행으로 시딩해 돌린다. 회복 뒤 기동 시점 사본으로 바꾸면 그 사이에 낸
        // 주문의 pending 이 메모리와 DB 에서 함께 사라진다(#260).
        val source = mockk<UniverseSource>()
        coEvery { source.select(any(), any()) } returns listOf("KRW-M")
        val dormant = TradingState("KRW-M")
        val live = TradingState("KRW-M")
        var seeded = false
        coEvery { positionManager.loadState("KRW-M") } coAnswers { seeded = true; live }
        val recovered = CompletableDeferred<Unit>()
        coEvery { positionManager.heldCurrencies() } coAnswers {
            if (!seeded) throw RuntimeException("accounts down")
            // 시딩 뒤 도는 state 가 낸 주문 — dormant 행에는 pending 이 없다(있으면 흔적으로 활성에 실린다).
            live.pendingSellIdentifier = "ctb-live"
            recovered.complete(Unit)
            setOf("M", "N")
        }
        coEvery { positionManager.syncPosition(any(), any()) } coAnswers { secondArg<TradingState>().position = true }
        val engine = createEngine(universe = UniverseProperties(auto = true, altCount = 1), source = source)

        engine.start(listOf("KRW-ETH"), mapOf("KRW-M" to dormant, "KRW-N" to TradingState("KRW-N")))
        withTimeout(5_000) { recovered.await() }
        engine.stop()

        assertSame(live, engine.getStates()["KRW-M"])
        assertEquals("ctb-live", engine.getStates().getValue("KRW-M").pendingSellIdentifier)
        coVerify(exactly = 0) { positionManager.persistState(match { it === dormant }) }
        // 선정되지 않은 보유 dormant 는 그대로 되살아난다.
        assertTrue("KRW-N" in engine.getActiveTickers())
    }

    @Test
    fun `a held dormant ticker seeded by a selection that stopped midway joins the active set once the lookup recovers`() = runBlocking {
        // 선정 반영은 티커마다 시딩한 뒤 마지막에 활성 집합을 바꾼다 — 뒤 티커의 로드가 실패하면 시딩한 보유 티커가 활성 밖에 남아
        // 다음 09:00 갱신까지 청산 평가를 못 받는다. 되살리기가 그 state 를 두고 활성에 붙인다.
        val source = mockk<UniverseSource>()
        coEvery { source.select(any(), any()) } returns listOf("KRW-M", "KRW-Y")
        val live = TradingState("KRW-M")
        var seeded = false
        coEvery { positionManager.loadState("KRW-M") } coAnswers { seeded = true; live }
        coEvery { positionManager.loadState("KRW-Y") } throws RuntimeException("db read failed")
        val recovered = CompletableDeferred<Unit>()
        coEvery { positionManager.heldCurrencies() } coAnswers {
            if (!seeded) throw RuntimeException("accounts down")
            recovered.complete(Unit)
            setOf("M")
        }
        coEvery { positionManager.syncPosition(any(), any()) } coAnswers { secondArg<TradingState>().position = true }
        val engine = createEngine(universe = UniverseProperties(auto = true, altCount = 2), source = source)

        engine.start(listOf("KRW-ETH"), mapOf("KRW-M" to TradingState("KRW-M")))
        withTimeout(5_000) { recovered.await() }
        engine.stop()

        assertTrue("KRW-M" in engine.getActiveTickers())
        // 여기서는 dormant 사본과 시딩한 state 가 구조적으로 같다 — 동등 비교로는 사본으로 바뀐 것을 가려내지 못한다.
        assertSame(live, engine.getStates()["KRW-M"])
    }

    @Test
    fun `before the first successful selection no swing ticker enters, exits still run`() = runBlocking {
        // 선정 API 가 죽은 채 재시작하면 durable 잔재 전부가 활성인데, 유니버스가 "제한 없음"이면 그들이 전부 진입 대상이 된다.
        val source = mockk<UniverseSource>()
        coEvery { source.select(any(), any()) } returns null
        val engine = createEngine(universe = UniverseProperties(auto = true), source = source)
        engine.start(listOf("KRW-ETH"))
        engine.stop()

        coEvery { upbitClient.getTicker("KRW-ETH") } returns listOf(com.trading.bot.domain.Ticker(tradePrice = 100.0))
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        engine.processTicker("KRW-ETH", TradingState("KRW-ETH"), strategy)
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any(), any()) }

        val holding = held("KRW-ETH")
        engine.processTicker("KRW-ETH", holding, strategy)
        coVerify(exactly = 1) { positionManager.checkStopLoss(holding, 100.0) }
    }

    @Test
    fun `applyTickers persists a newly selected ticker that turns out to be held`() = runBlocking {
        // durable 행 없이 수동 보유가 발견되면 바로 남겨야 재시작 후에도 청산 평가를 받는다.
        val engine = createEngine()
        engine.start(listOf("KRW-ETH"))
        engine.stop()
        coEvery { positionManager.loadState("KRW-A") } returns null // durable 행 없음(relaxed 기본값은 mock 객체라 명시)
        coEvery { positionManager.syncPosition("KRW-A", any()) } coAnswers { secondArg<TradingState>().position = true }

        engine.applyTickers(listOf("KRW-A"))

        coVerify(exactly = 1) { positionManager.persistState(match { it.ticker == "KRW-A" }) }
    }

    @Test
    fun `applyTickers keeps a ticker whose balance could not be synced`() = runBlocking {
        val engine = createEngine()
        engine.start(listOf("KRW-ETH"), mapOf("KRW-ETH" to TradingState("KRW-ETH", unsynced = true)))
        engine.stop()

        engine.applyTickers(listOf("KRW-A"))

        assertTrue("KRW-ETH" in engine.getActiveTickers())
    }

    @Test
    fun `refreshUniverse keeps the previous list when the source fails`() = runBlocking {
        val source = mockk<UniverseSource>()
        coEvery { source.select(any(), any()) } returns null
        val engine = createEngine(universe = UniverseProperties(auto = true), source = source)
        engine.start(listOf("KRW-ETH"))
        engine.stop()

        assertFalse(engine.refreshUniverse())
        assertEquals(listOf("KRW-ETH"), engine.getActiveTickers())
    }

    @Test
    fun `auto off never consults the source and leaves the requested list as is`() = runBlocking {
        val source = mockk<UniverseSource>()
        val engine = createEngine(source = source)
        engine.start(listOf("KRW-ETH", "KRW-XRP"))
        engine.stop()

        assertFalse(engine.refreshUniverse())
        assertEquals(listOf("KRW-ETH", "KRW-XRP"), engine.getActiveTickers())
        coVerify(exactly = 0) { source.select(any(), any()) }
    }

    // --- 비-auto 잔류 (#226) ---
    // 사용자 목록에서 빠진 티커라도 엔진이 산 스윙 포지션·미해소 주문은 청산될 때까지 관리하고, 새로 사지는 않는다.

    @Test
    fun `without auto a held or pending ticker outside the requested list stays active`() = runBlocking {
        val logs = captureEngineLogs()
        val engine = createEngine(accumulate = AccumulateProperties(tickers = "KRW-ADA"))
        engine.start(
            listOf("KRW-BTC"),
            linkedMapOf(
                "KRW-XRP" to heldEntry("KRW-XRP"),
                "KRW-P" to TradingState("KRW-P", pendingSellUuid = "s1"),
                "KRW-OLD" to TradingState("KRW-OLD"),
                // 적립 설정에서 빠진 사다리 보유분 — 스윙 청산 규칙으로 시장가에 팔리지 않게 싣지 않고 알린다.
                "KRW-LAD" to TradingState("KRW-LAD", position = true, entryStrategy = "accumulate", rungsFilled = 2),
                // 첫 단 매수가 미체결이면 rungsFilled 는 아직 0 — 단 매수의 triggerPrice 로 사다리임을 안다.
                "KRW-LAD0" to TradingState("KRW-LAD0", pendingBuyUuid = "b1", pendingBuyTriggerPrice = 100.0),
                // 적립 설정 안의 사다리는 원래 활성이다 — 알릴 대상이 아니다.
                "KRW-ADA" to TradingState("KRW-ADA", position = true, entryStrategy = "accumulate", rungsFilled = 1),
            ),
        )
        engine.stop()

        assertEquals(listOf("KRW-ADA", "KRW-BTC", "KRW-XRP", "KRW-P"), engine.getActiveTickers())
        val ladderWarning = logs.list.single { it.level == Level.WARN && "사다리" in it.formattedMessage }.formattedMessage
        assertTrue("[KRW-LAD, KRW-LAD0]" in ladderWarning, ladderWarning)
    }

    @Test
    fun `dust on a retained ticker outside the list is handed to release instead of staying forever`() = runBlocking {
        // 목록 밖은 새로 사지 않으므로(#226) 흡수될 길이 없다 — 팔 수도 없으니 잔류가 영구화된다(#234).
        val engine = createEngine()
        engine.start(listOf("KRW-BTC"), linkedMapOf("KRW-XRP" to heldEntry("KRW-XRP")))
        engine.stop()
        val xrp = engine.getStates().getValue("KRW-XRP") // 1코인
        coEvery { upbitClient.getTicker("KRW-XRP") } returns listOf(Ticker(tradePrice = 1_000.0)) // 1,000원어치

        engine.processTicker("KRW-XRP", xrp, strategy)
        coVerify(exactly = 1) { positionManager.releaseDust("KRW-XRP", xrp, 1_000.0) }

        coEvery { upbitClient.getTicker("KRW-XRP") } returns listOf(Ticker(tradePrice = 10_000.0)) // 1만원 — 팔 수 있다
        engine.processTicker("KRW-XRP", xrp, strategy)
        coVerify(exactly = 1) { positionManager.releaseDust(any(), any(), any()) }
    }

    @Test
    fun `getUserTickers is the requested list only and a no-op start does not replace it`() = runBlocking {
        val engine = createEngine(accumulate = AccumulateProperties(tickers = "KRW-BTC"))
        engine.start(listOf("KRW-ETH"), mapOf("KRW-XRP" to heldEntry("KRW-XRP")))
        engine.start(listOf("KRW-SOL"))
        engine.stop()

        assertEquals(listOf("KRW-ETH"), engine.getUserTickers())
        assertTrue("KRW-XRP" in engine.getActiveTickers())
    }

    @Test
    fun `without auto the loop still exits a retained holding and reconciles a retained pending sell`(): Unit = runBlocking {
        val xrp = heldEntry("KRW-XRP")
        val sold = CompletableDeferred<Unit>()
        val reconciled = CompletableDeferred<Unit>()
        coEvery { upbitClient.getTicker(any()) } returns listOf(Ticker(tradePrice = 90.0))
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns false
        every { positionManager.checkStopLoss(xrp, any()) } returns true
        coEvery { positionManager.sell("KRW-XRP", xrp, any(), SellReason.STOP_LOSS) } coAnswers { sold.complete(Unit); null }
        coEvery { positionManager.reconcilePendingSell("KRW-P", any(), any()) } coAnswers { reconciled.complete(Unit); null }
        val engine = createEngine()

        engine.start(listOf("KRW-BTC"), mapOf("KRW-XRP" to xrp, "KRW-P" to TradingState("KRW-P", pendingSellUuid = "s1")))
        withTimeout(5_000) {
            sold.await()
            reconciled.await()
        }
        engine.stop()
    }

    @Test
    fun `without auto a retained ticker is not bought again on a later day while a listed ticker is`() = runBlocking {
        val xrp = heldEntry("KRW-XRP")
        val engine = createEngine()
        engine.start(listOf("KRW-BTC"), mapOf("KRW-XRP" to xrp))
        engine.stop()
        xrp.markSold()
        xrp.boughtToday = false // 다음 거래일 — 당일 1회 게이트가 대신 막지 않게 한다
        coEvery { upbitClient.getTicker(any()) } returns listOf(Ticker(tradePrice = 100.0))
        coEvery { upbitClient.getDayCandles(any(), any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        val btc = TradingState("KRW-BTC")

        engine.processTicker("KRW-XRP", xrp, strategy)
        engine.processTicker("KRW-BTC", btc, strategy)

        coVerify(exactly = 0) { positionManager.buy("KRW-XRP", any(), any(), any(), any()) }
        coVerify(exactly = 1) { positionManager.buy("KRW-BTC", btc, 100.0, "test_strategy", any()) }
    }

    @Test
    fun `without auto a retained row that turns out flat is dropped after the startup sync`() = runBlocking {
        val firstTick = CompletableDeferred<Unit>()
        coEvery { upbitClient.getTicker("KRW-BTC") } coAnswers { firstTick.complete(Unit); listOf(Ticker(tradePrice = 100.0)) }
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns false
        // 우리 주문으로 설명 안 되는 락(releaseHoldings 뒤)은 unsynced — 실제 포지션일 수 있으니 남긴다.
        coEvery { positionManager.syncPosition("KRW-U", any()) } coAnswers { secondArg<TradingState>().unsynced = true }
        val engine = createEngine()

        engine.start(
            listOf("KRW-BTC"),
            linkedMapOf(
                // 엔진이 멈춘 사이 거래소에서 팔려 메타만 남은 행 — 매도 경로가 안 돌아 메타가 영영 안 지워진다.
                "KRW-GONE" to TradingState("KRW-GONE", entryStrategy = "test_strategy"),
                "KRW-XRP" to heldEntry("KRW-XRP"),
                "KRW-U" to TradingState("KRW-U", entryStrategy = "test_strategy"),
                "KRW-P" to TradingState("KRW-P", pendingBuyUuid = "b1"),
            ),
        )
        withTimeout(5_000) { firstTick.await() }
        engine.stop()

        assertEquals(listOf("KRW-BTC", "KRW-XRP", "KRW-U", "KRW-P"), engine.getActiveTickers())
        assertEquals(engine.getActiveTickers().toSet(), engine.getStates().keys)
        // 잔고 없음·귀속 불명 락 없음이 확인된 행이라 옛 진입 메타도 비운다 — 남기면 그 위에 사람이 다시 산 코인이
        // 다음 기동에 잔류로 실려 옛 buyDate 로 보유상한 매도된다.
        coVerify { positionManager.persistState(match { it.ticker == "KRW-GONE" && it.entryStrategy == null && it.buyDate == null }) }
    }

    @Test
    fun `without auto the 09 00 boundary drops a retained ticker sold during the day after flushing its reset`() = runBlocking {
        val xrp = heldEntry("KRW-XRP")
        val firstTick = CompletableDeferred<Unit>()
        val engine = createEngine()
        var activeAtBoundary: List<String> = emptyList()
        var boundaries = 0
        every { dailyResetManager.checkAndReset(any()) } answers {
            if (boundaries++ == 0) {
                activeAtBoundary = engine.getActiveTickers()
                xrp.markSold() // 낮 동안 엔진이 청산했다
                true
            } else {
                false
            }
        }
        coEvery { upbitClient.getTicker("KRW-BTC") } coAnswers { firstTick.complete(Unit); listOf(Ticker(tradePrice = 100.0)) }
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns false

        engine.start(listOf("KRW-BTC"), mapOf("KRW-XRP" to xrp))
        withTimeout(5_000) { firstTick.await() }
        engine.stop()

        assertTrue("KRW-XRP" in activeAtBoundary, "보유 중에는 잔류로 활성이어야 한다")
        assertEquals(listOf("KRW-BTC"), engine.getActiveTickers())
        coVerify { positionManager.persistState(xrp) } // 리셋을 flush 한 뒤 뺀다
    }

    @Test
    fun `with auto the prune step does not run because applyTickers owns the active set`() = runBlocking {
        val source = mockk<UniverseSource>()
        coEvery { source.select(any(), any()) } returns null // 선정 실패 — 정리가 대신 돌아 선정분을 자르면 안 된다
        val firstTick = CompletableDeferred<Unit>()
        coEvery { upbitClient.getTicker("KRW-ETH") } coAnswers { firstTick.complete(Unit); listOf(Ticker(tradePrice = 100.0)) }
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns false
        val engine = createEngine(universe = UniverseProperties(auto = true, altCount = 1), source = source)

        engine.start(listOf("KRW-ETH"), mapOf("KRW-GONE" to TradingState("KRW-GONE", entryStrategy = "test_strategy")))
        withTimeout(5_000) { firstTick.await() }
        engine.stop()

        assertTrue("KRW-GONE" in engine.getActiveTickers())
    }

    @Test
    fun `a restart with new inputs drops states from the previous run`() = runBlocking {
        val engine = createEngine()
        // 보유 상태라 정리 단계는 빼지 않는다 — 빠진다면 start 가 직전 실행 상태를 버렸기 때문이다.
        engine.start(listOf("KRW-BTC"), mapOf("KRW-BTC" to held("KRW-BTC")))
        engine.stop()

        engine.start(listOf("KRW-ETH"))
        engine.stop()

        assertFalse("KRW-BTC" in engine.getStates().keys)
    }

    @Test
    fun `restartSnapshot keeps unconfirmed dormant rows`() = runBlocking {
        val source = mockk<UniverseSource>()
        coEvery { source.select(any(), any()) } returns listOf("KRW-A")
        val lookedUp = CompletableDeferred<Unit>()
        coEvery { positionManager.heldCurrencies() } coAnswers { lookedUp.complete(Unit); throw RuntimeException("accounts down") }
        val engine = createEngine(universe = UniverseProperties(auto = true, altCount = 1), source = source)

        engine.start(listOf("KRW-ETH"), mapOf("KRW-M" to TradingState("KRW-M")))
        withTimeout(5_000) { lookedUp.await() }
        engine.stop()

        // 잔고 조회가 아직 성공 못 한 행 — 복귀 재기동이 버리면 조회가 회복돼도 되살아나지 않는다.
        assertTrue("KRW-M" in engine.restartSnapshot())
    }

    @Test
    fun `ticker groups split the active set into accumulate and exit-only tickers`() = runBlocking {
        val engine = createEngine(accumulate = AccumulateProperties(tickers = "KRW-ADA"))
        engine.start(listOf("KRW-BTC"), mapOf("KRW-XRP" to heldEntry("KRW-XRP")))
        engine.stop()

        assertEquals(listOf("KRW-ADA"), engine.getAccumulateTickers())
        assertEquals(listOf("KRW-BTC"), engine.getEntryTickers())
        assertEquals(listOf("KRW-XRP"), engine.getExitOnlyTickers())
    }

    @Test
    fun `with auto the selected alts are the entry tickers, not the user list`() = runBlocking {
        // auto 는 사용자 목록 대신 선정 알트로 매매한다 — 화면이 사용자 목록을 거래쌍으로 보이면 실제 대상이 어디에도 안 보인다.
        val source = mockk<UniverseSource>()
        coEvery { source.select(any(), any()) } returns listOf("KRW-A")
        val engine = createEngine(universe = UniverseProperties(auto = true, altCount = 1), source = source)
        engine.start(listOf("KRW-ETH"), mapOf("KRW-XRP" to heldEntry("KRW-XRP")))
        engine.stop()
        engine.refreshUniverse()

        assertEquals(listOf("KRW-A"), engine.getEntryTickers())
        assertEquals(listOf("KRW-XRP"), engine.getExitOnlyTickers())
    }

    @Test
    fun `resume restarts with the previous user list and keeps a retained holding active`() = runBlocking {
        val engine = createEngine()
        engine.start(listOf("KRW-BTC"), mapOf("KRW-XRP" to heldEntry("KRW-XRP")))
        engine.stop()

        engine.resume()
        engine.stop()

        assertEquals(listOf("KRW-BTC"), engine.getUserTickers())
        assertTrue("KRW-XRP" in engine.getActiveTickers())
    }
}
