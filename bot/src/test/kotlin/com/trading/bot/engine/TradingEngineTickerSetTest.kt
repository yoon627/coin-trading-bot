package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.Ticker
import com.trading.bot.domain.TradingState
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 활성 티커 집합 — 사용자 목록 밖 잔류(#226)·청산 뒤 정리·재기동·화면 분류.
 * 사용자 목록에서 빠진 티커라도 엔진이 산 스윙 포지션·미해소 주문은 청산될 때까지 관리하고, 새로 사지는 않는다.
 */
class TradingEngineTickerSetTest {

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

    private fun createEngine() = TradingEngine(
        upbitClient = upbitClient,
        positionManager = positionManager,
        dailyResetManager = dailyResetManager,
        strategies = listOf(strategy),
        tradingProperties = TradingProperties(intervalSeconds = 1),
        userId = 1L,
        username = "testuser",
        marketDataStore = marketDataStore,
    )

    private fun held(ticker: String) = TradingState(ticker, position = true, avgBuyPrice = 100.0, holdVolume = 1.0)

    /** 엔진이 산 포지션 — 진입 메타가 있어야 재시작 때 잔류로 실린다. */
    private fun heldEntry(ticker: String) =
        held(ticker).apply { entryStrategy = "test_strategy"; buyDate = LocalDate.of(2026, 9, 1) }

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
    fun `a held or pending ticker outside the requested list stays active`() = runBlocking {
        val engine = createEngine()
        engine.start(
            listOf("KRW-BTC"),
            linkedMapOf(
                "KRW-XRP" to heldEntry("KRW-XRP"),
                "KRW-P" to TradingState("KRW-P", pendingSellUuid = "s1"),
                // 진입 흔적 없는 잔재(청산 완료)는 싣지 않는다.
                "KRW-OLD" to TradingState("KRW-OLD"),
            ),
        )
        engine.stop()

        assertEquals(listOf("KRW-BTC", "KRW-XRP", "KRW-P"), engine.getActiveTickers())
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
        val engine = createEngine()
        engine.start(listOf("KRW-ETH"), mapOf("KRW-XRP" to heldEntry("KRW-XRP")))
        engine.start(listOf("KRW-SOL"))
        engine.stop()

        assertEquals(listOf("KRW-ETH"), engine.getUserTickers())
        assertTrue("KRW-XRP" in engine.getActiveTickers())
    }

    @Test
    fun `the loop still exits a retained holding and reconciles a retained pending sell`(): Unit = runBlocking {
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
    fun `a retained ticker is not bought again on a later day while a listed ticker is`() = runBlocking {
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

        coVerify(exactly = 0) { positionManager.buy("KRW-XRP", any(), any(), any()) }
        coVerify(exactly = 1) { positionManager.buy("KRW-BTC", btc, 100.0, "test_strategy") }
    }

    @Test
    fun `a retained row that turns out flat is dropped after the startup sync`() = runBlocking {
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
    fun `the 09 00 boundary drops a retained ticker sold during the day after flushing its reset`() = runBlocking {
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
    fun `ticker groups split the active set into entry and exit-only tickers`() = runBlocking {
        val engine = createEngine()
        engine.start(listOf("KRW-BTC"), mapOf("KRW-XRP" to heldEntry("KRW-XRP")))
        engine.stop()

        assertEquals(listOf("KRW-BTC"), engine.getEntryTickers())
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
