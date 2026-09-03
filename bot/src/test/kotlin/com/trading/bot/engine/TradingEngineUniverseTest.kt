package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
}
