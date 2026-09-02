package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Ticker
import com.trading.bot.domain.TradingState
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.AccumulateProperties
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.LadderAction
import com.trading.common.strategy.TradingStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 프로파일 dispatch 만 본다 — 사다리 규칙 자체는 AccumulateLadderTest, 전이는 PositionManagerAccumulateTest. */
class TradingEngineAccumulateTest {

    private lateinit var upbitClient: UpbitClient
    private lateinit var positionManager: PositionManager
    private lateinit var dailyResetManager: DailyResetManager
    private lateinit var strategy: TradingStrategy
    private lateinit var marketDataStore: MarketDataStore
    private val accumulate = AccumulateProperties(tickers = "KRW-BTC")

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
        every { dailyResetManager.shouldSellForDailyReset(any()) } returns false
        coEvery { positionManager.buyRung(any(), any(), any(), any(), any()) } returns null
        coEvery { positionManager.sellVolume(any(), any(), any(), any()) } returns null
    }

    private fun createEngine(props: AccumulateProperties = accumulate) = TradingEngine(
        upbitClient = upbitClient,
        positionManager = positionManager,
        dailyResetManager = dailyResetManager,
        strategies = listOf(strategy),
        tradingProperties = TradingProperties(intervalSeconds = 1),
        userId = 1L,
        username = "testuser",
        marketDataStore = marketDataStore,
        accumulateProperties = props,
    )

    private fun price(ticker: String, price: Double) {
        coEvery { upbitClient.getTicker(ticker) } returns listOf(Ticker(tradePrice = price))
    }

    @Test
    fun `accumulate ticker never evaluates swing exits and adds a rung on a drop`() = runTest {
        val engine = createEngine()
        price("KRW-BTC", 90.0)
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 100.0, holdVolume = 200.0, rungsFilled = 1, lastActionPrice = 100.0)

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 0) { positionManager.sell(any(), any(), any(), any()) }
        coVerify(exactly = 0) { positionManager.checkStopLoss(any(), any()) }
        coVerify(exactly = 0) { positionManager.checkTakeProfit(any(), any()) }
        coVerify(exactly = 0) { positionManager.checkTrailingStop(any(), any()) }
        coVerify(exactly = 0) { dailyResetManager.shouldSellForDailyReset(any()) }
        coVerify(exactly = 1) { positionManager.buyRung("KRW-BTC", state, 90.0, LadderAction.Buy(20_000.0, 90.0), accumulate.ladderParams()) }
    }

    @Test
    fun `accumulate ticker sells a rung on a rise`() = runTest {
        val engine = createEngine()
        price("KRW-BTC", 104.0)
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 100.0, holdVolume = 400.0, rungsFilled = 2, lastActionPrice = 97.0)

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 1) { positionManager.sellVolume("KRW-BTC", state, 104.0, LadderAction.Sell(200.0, 104.0, isFinal = false)) }
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `accumulate ticker ignores the once-a-day gate`() = runTest {
        val engine = createEngine()
        price("KRW-BTC", 97.0)
        val state = TradingState("KRW-BTC", boughtToday = true, flatPeak = 100.0)

        engine.processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 1) { positionManager.buyRung("KRW-BTC", state, 97.0, LadderAction.Buy(20_000.0, 97.0), any()) }
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `accumulate ticker tracks and persists the flat peak while flat`() = runTest {
        val engine = createEngine()
        price("KRW-BTC", 105.0)
        val state = TradingState("KRW-BTC", flatPeak = 100.0)

        engine.processTicker("KRW-BTC", state, strategy)

        assertEquals(105.0, state.flatPeak)
        coVerify(exactly = 1) { positionManager.persistPeak(state) }
        coVerify(exactly = 0) { positionManager.buyRung(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `accumulate ticker folds an existing holding into the ladder once`() = runTest {
        val engine = createEngine()
        price("KRW-BTC", 100.0)
        // rung 장부 없이 45,000 원어치 보유(스윙에서 넘어온 포지션) → 3단으로 편입, 기준가 = 평단.
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 90.0, holdVolume = 500.0)

        engine.processTicker("KRW-BTC", state, strategy)
        assertEquals(3, state.rungsFilled)
        assertEquals(90.0, state.lastActionPrice)

        // 이후 tick 은 장부를 신뢰한다 — 수동으로 바꾼 값이 다시 덮이지 않는다.
        state.rungsFilled = 4
        engine.processTicker("KRW-BTC", state, strategy)
        assertEquals(4, state.rungsFilled)
    }

    @Test
    fun `swing ticker still uses the ordinary path and passes reserved krw`() = runBlocking {
        val engine = createEngine()
        // BTC(적립) 는 50,000 원어치 보유 → 미투입 예산 50,000 이 스윙에서 예약된다.
        val btc = TradingState("KRW-BTC", position = true, avgBuyPrice = 50.0, holdVolume = 1_000.0, rungsFilled = 3, lastActionPrice = 50.0)
        engine.start(listOf("KRW-ETH"), mapOf("KRW-BTC" to btc))
        engine.stop()
        assertEquals(50_000.0, engine.reservedKrw())

        price("KRW-ETH", 1_000.0)
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        val eth = TradingState("KRW-ETH")
        engine.processTicker("KRW-ETH", eth, strategy)

        coVerify(exactly = 1) { positionManager.buy("KRW-ETH", eth, 1_000.0, "test_strategy", 50_000.0) }
        coVerify(exactly = 0) { positionManager.buyRung(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `start unions accumulate tickers with the requested list`() = runBlocking {
        val engine = createEngine()
        engine.start(listOf("KRW-ETH", "KRW-BTC"))
        assertEquals(listOf("KRW-BTC", "KRW-ETH"), engine.getActiveTickers())
        engine.stop()
    }

    @Test
    fun `disabled profile leaves the swing engine untouched`() = runBlocking {
        val engine = createEngine(AccumulateProperties())
        engine.start(listOf("KRW-ETH"))
        assertEquals(listOf("KRW-ETH"), engine.getActiveTickers())
        assertEquals(0.0, engine.reservedKrw())
        engine.stop()

        price("KRW-BTC", 90.0)
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 100.0, holdVolume = 200.0)
        engine.processTicker("KRW-BTC", state, strategy)
        coVerify(exactly = 1) { positionManager.checkStopLoss(state, 90.0) }
        coVerify(exactly = 0) { positionManager.buyRung(any(), any(), any(), any(), any()) }
    }
}
