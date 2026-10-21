package com.trading.bot.engine

import ch.qos.logback.classic.Level
import com.trading.bot.characterization.capturingLogs
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.Ticker
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import com.trading.common.strategy.TradingStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 엔진은 자기 거래소의 상태만 만들고 쓴다. 다른 거래소 행을 받으면 버리고 한 번 알린다. */
class TradingEngineExchangeTest {

    private val client = mockk<ExchangeClient>(relaxed = true)
    private val positionManager = mockk<PositionManager>(relaxed = true)
    private val dailyResetManager = mockk<DailyResetManager>(relaxed = true) {
        every { checkAndReset(any()) } returns false
    }
    private val strategy = mockk<TradingStrategy> {
        every { name } returns "test_strategy"
        every { minCandles } returns 21
        coEvery { shouldBuy(any(), any(), any()) } returns false
    }
    private val marketDataStore = mockk<MarketDataStore>(relaxed = true) {
        every { getLatestTicker(any(), any()) } returns null
    }

    private fun engine(exchange: Exchange) = TradingEngine(
        upbitClient = client,
        positionManager = positionManager,
        dailyResetManager = dailyResetManager,
        strategy = strategy,
        tradingProperties = TradingProperties(intervalSeconds = 1),
        userId = 1L,
        username = "u1",
        marketDataStore = marketDataStore,
        exchange = exchange,
    )

    private fun kisPendingSell() = TradingState(
        "KRX-005930", exchange = Exchange.KIS_PAPER, position = true, holdVolume = 3.0, entryStrategy = "test_strategy",
        pendingSellUuid = "KIS_PAPER:20261020:0001", pendingSellReason = SellReason.STOP_LOSS,
    )

    @Test
    fun `a ticker the engine adds gets the engine's exchange`() = runBlocking {
        val synced = CompletableDeferred<Unit>()
        coEvery { positionManager.syncPosition("KRX-005930", any()) } coAnswers { synced.complete(Unit) }
        val engine = engine(Exchange.KIS_PAPER)

        engine.start(listOf("KRX-005930"))
        withTimeout(5_000) { synced.await() }
        engine.stop()

        assertEquals(Exchange.KIS_PAPER, engine.getStates().getValue("KRX-005930").exchange)
    }

    @Test
    fun `start drops another exchange's rows with a single error`() = runBlocking {
        val engine = engine(Exchange.UPBIT)

        val errors = capturingLogs(TradingEngine::class.java) { logs ->
            engine.start(listOf("KRW-BTC"), linkedMapOf("KRW-BTC" to TradingState("KRW-BTC"), "KRX-005930" to kisPendingSell()))
            logs.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }
        }
        engine.stop()

        assertEquals(1, errors.size, "$errors")
        assertTrue("KRX-005930" in errors.single(), errors.single())
        assertEquals(listOf("KRW-BTC"), engine.getActiveTickers())
        assertFalse("KRX-005930" in engine.getStates())
    }

    @Test
    fun `an upbit engine does not reconcile a KIS row's pending sell`() = runBlocking {
        // 두 번째 tick 이 오면 첫 루프 한 바퀴가 끝났다.
        val ticks = AtomicInteger()
        val secondTick = CompletableDeferred<Unit>()
        coEvery { client.getTicker("KRW-BTC") } coAnswers {
            if (ticks.incrementAndGet() == 2) secondTick.complete(Unit)
            listOf(Ticker(tradePrice = 100.0))
        }
        val engine = engine(Exchange.UPBIT)

        engine.start(listOf("KRW-BTC"), linkedMapOf("KRX-005930" to kisPendingSell()))
        withTimeout(5_000) { secondTick.await() }
        engine.stop()

        coVerify(exactly = 0) { positionManager.reconcilePendingSell("KRX-005930", any(), any()) }
        coVerify(exactly = 0) { positionManager.syncPosition("KRX-005930", any()) }
    }

    @Test
    fun `a halt that needs an order review is not cleared by halt clear`() = runBlocking {
        val engine = engine(Exchange.UPBIT)
        val state = TradingState("KRW-BTC", halted = true, haltReason = "주문 확인 필요(BUY ctb-1): x", reviewReason = "주문 확인 필요(BUY ctb-1): x")
        engine.start(listOf("KRW-BTC"), linkedMapOf("KRW-BTC" to state))
        engine.stop()

        assertFalse(engine.clearHalt("KRW-BTC"))

        assertTrue(state.halted)
        coVerify(exactly = 0) { positionManager.persistStateOrThrow(any()) }
    }
}
