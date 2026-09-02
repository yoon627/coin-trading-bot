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
