package com.trading.bot.kis.engine

import com.trading.bot.domain.SellReason
import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.marketdata.KisMarketCalendar
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import com.trading.bot.kis.domain.KisHolding
import com.trading.common.domain.NormalizedCandle
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class KisStockTradingEngineTest {
    private lateinit var pm: StockPositionManager
    private lateinit var strategy: TradingStrategy
    private lateinit var client: KisClient
    private lateinit var store: MarketDataStore
    private lateinit var engine: KisStockTradingEngine

    @BeforeEach
    fun setup() {
        pm = mockk()
        strategy = mockk()
        client = mockk()
        store = mockk()
        every { strategy.name } returns "rsi"
        every { strategy.minCandles } returns 21
        engine = engine(liveEnabled = false)
    }

    private fun engine(liveEnabled: Boolean, entryGate: () -> String? = { null }) = KisStockTradingEngine(
        userId = 1L, positionManager = pm, client = client,
        strategies = listOf(strategy), tradingProperties = TradingProperties(),
        marketDataStore = store, marketCalendar = mockk<KisMarketCalendar>(),
        liveEnabled = liveEnabled, entryGate = entryGate,
    )

    /** 지표가 매수 신호를 내도록 — 캔들 충분 + 전략 true. */
    private fun buySignal() {
        every { store.getCandles(any(), any(), any(), any()) } returns List(30) { mockk<NormalizedCandle>(relaxed = true) }
        coEvery { strategy.shouldBuyNormalized(any(), any(), any()) } returns true
        coEvery { pm.submitBuy(any(), any(), any(), any()) } returns null
    }

    private val pos = StockPosition("005930").apply { position = true; avgBuyPrice = 100.0 }

    @Test
    fun `stop-loss takes priority`() = runTest {
        every { pm.checkStopLoss(pos, 90) } returns true
        every { pm.checkTrailingStop(pos, 90) } returns true
        every { pm.checkTakeProfit(pos, 90) } returns true
        assertEquals(SellReason.STOP_LOSS, engine.decideSell("005930", pos, 90))
    }

    @Test
    fun `trailing before take-profit`() = runTest {
        every { pm.checkStopLoss(pos, 105) } returns false
        every { pm.checkTrailingStop(pos, 105) } returns true
        every { pm.checkTakeProfit(pos, 105) } returns true
        assertEquals(SellReason.TRAILING_STOP, engine.decideSell("005930", pos, 105))
    }

    @Test
    fun `take-profit when only tp`() = runTest {
        every { pm.checkStopLoss(pos, 103) } returns false
        every { pm.checkTrailingStop(pos, 103) } returns false
        every { pm.checkTakeProfit(pos, 103) } returns true
        assertEquals(SellReason.TAKE_PROFIT, engine.decideSell("005930", pos, 103))
    }

    @Test
    fun `no sell when no gate (chartExit off)`() = runTest {
        every { pm.checkStopLoss(pos, 101) } returns false
        every { pm.checkTrailingStop(pos, 101) } returns false
        every { pm.checkTakeProfit(pos, 101) } returns false
        assertNull(engine.decideSell("005930", pos, 101))
    }

    // --- reconcile 미해소면 live 신규 진입만 막는다 (#67). 매도·동기화는 계속 ---

    private val flat = StockPosition("005930")

    @Test
    fun `live 에서 진입 게이트가 사유를 주면 매수를 보내지 않는다`() = runTest {
        buySignal()
        val e = engine(liveEnabled = true, entryGate = { "reconcile 미완료" })

        e.tryEnter("005930", flat, 100)

        coVerify(exactly = 0) { pm.submitBuy(any(), any(), any(), any()) }
    }

    @Test
    fun `live 에서 게이트가 비어 있으면 매수한다`() = runTest {
        buySignal()
        val e = engine(liveEnabled = true, entryGate = { null })

        e.tryEnter("005930", flat, 100)

        coVerify(exactly = 1) { pm.submitBuy(flat, 100, "rsi", true) }
    }

    @Test
    fun `dry-run 은 게이트를 보지 않는다`() = runTest {
        buySignal()
        val e = engine(liveEnabled = false, entryGate = { "reconcile 미완료" })

        e.tryEnter("005930", flat, 100)

        coVerify(exactly = 1) { pm.submitBuy(flat, 100, "rsi", false) }
    }

    @Test
    fun `게이트가 걸려 있어도 보유 포지션의 청산은 나간다`() = runTest {
        val e = engine(liveEnabled = true, entryGate = { "reconcile 미완료" })
        every { pm.syncFromHoldings(any(), any(), any()) } answers { firstArg<StockPosition>().apply { position = true; avgBuyPrice = 100.0 } }
        every { store.getLatestTicker(any(), any()) } returns null
        coEvery { client.getCurrentPrice("005930") } returns 90L
        every { pm.checkStopLoss(any(), 90) } returns true
        coEvery { pm.submitSell(any(), any(), any()) } returns null
        val holding = mockk<KisHolding>(relaxed = true)
        every { holding.pdno } returns "005930"
        every { holding.heldQty() } returns 10L
        every { holding.avgBuyPrice() } returns 100.0

        e.processSymbol("005930", listOf(holding))

        coVerify(exactly = 1) { pm.submitSell(any(), SellReason.STOP_LOSS, true) }
    }

    // --- stop() 은 drain 을 보장한다 (#91) ---
    // 키 교체는 옛 엔진을 멈추고 새 엔진을 곧바로 띄운다. cancel 만 하고 반환하면 구 루프의 in-flight 주문과 새 엔진이
    // 겹쳐 두 계좌에 동시 주문된다(WAL 중복 가드는 accountNo 별). Upbit TradingEngine.stop() 과 같은 cancelAndJoin.

    @Test
    @Timeout(10) // 막으려는 실패 모드가 hang 이라 타임아웃 없이는 실패 대신 CI 가 멈춘다.
    fun `stop waits for the in-flight tick to drain and is not logged as a loop failure`() = runBlocking {
        val calendar = mockk<KisMarketCalendar>()
        every { calendar.isTradingNow() } returns true
        val entered = CompletableDeferred<Unit>()
        val drained = AtomicBoolean(false)
        coEvery { client.getHoldings() } coAnswers {
            entered.complete(Unit)
            withContext(NonCancellable) { delay(150) }
            drained.set(true)
            currentCoroutineContext().ensureActive() // stop() 의 취소가 잔고 조회에서 CancellationException 으로 드러난다
            emptyList()
        }
        val logger = org.slf4j.LoggerFactory.getLogger(KisStockTradingEngine::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val live = KisStockTradingEngine(
            userId = 1L, positionManager = pm, client = client,
            strategies = listOf(strategy), tradingProperties = TradingProperties(),
            marketDataStore = store, marketCalendar = calendar, liveEnabled = true,
        )

        try {
            live.start(listOf("005930"))
            entered.await()
            live.stop()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        assertTrue(drained.get(), "stop() 은 진행 중 tick(잔고 조회·주문 후처리)이 끝난 뒤에 반환해야 한다")
        assertFalse(live.isRunning())
        // 정지의 취소를 loop error(ERROR → Discord 알림)나 잔고 조회 장애(WARN)로 기록하면 키 교체마다 오알림이 난다.
        val noise = appender.list.filter { it.level.isGreaterOrEqual(ch.qos.logback.classic.Level.WARN) }.map { it.formattedMessage }
        assertTrue(noise.isEmpty(), "stop() 취소가 오류로 기록됐다: $noise")
    }
}
