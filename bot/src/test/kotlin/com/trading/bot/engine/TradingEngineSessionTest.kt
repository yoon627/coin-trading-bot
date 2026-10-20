package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Ticker
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.TradingCalendar
import com.trading.bot.exchange.UpbitTradingCalendar
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/** 장외에는 평가·주문을 하지 않고 미해소 주문만 확정한다. */
class TradingEngineSessionTest {

    private val upbitClient = mockk<UpbitClient>(relaxed = true)
    private val positionManager = mockk<PositionManager>(relaxed = true)
    private val dailyResetManager = mockk<DailyResetManager>(relaxed = true) {
        every { checkAndReset(any()) } returns false
    }
    private val strategy = mockk<TradingStrategy>(relaxed = true) {
        every { name } returns "test_strategy"
        every { minCandles } returns 21
    }

    private object ClosedCalendar : TradingCalendar by UpbitTradingCalendar {
        override fun isSessionOpen(now: Instant): Boolean = false
    }

    private fun engine() = TradingEngine(
        upbitClient = upbitClient,
        positionManager = positionManager,
        dailyResetManager = dailyResetManager,
        strategy = strategy,
        tradingProperties = TradingProperties(intervalSeconds = 1),
        calendar = ClosedCalendar,
    )

    private fun pendingBuy(ticker: String) = TradingState(ticker).apply { beginBuyOrder("ctb-$ticker", "test_strategy", 0.0) }

    @Test
    fun `a closed session reconciles a pending buy but evaluates nothing once it clears`() = runTest {
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        val state = pendingBuy("KRW-BTC")
        coEvery { positionManager.reconcilePendingBuy("KRW-BTC", state, 100.0) } answers { state.clearPendingBuy(); null }

        engine().processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 1) { positionManager.reconcilePendingBuy("KRW-BTC", state, 100.0) }
        coVerify(exactly = 0) { upbitClient.getDayCandles(any(), any()) }
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `a closed session keeps a held position out of sell evaluation`() = runTest {
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        val state = TradingState("KRW-BTC", position = true, holdVolume = 1.0, avgBuyPrice = 200.0)

        engine().processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 0) { positionManager.checkStopLoss(any(), any()) }
        coVerify(exactly = 0) { positionManager.sell(any(), any(), any(), any()) }
    }

    @Test
    fun `the loop skips tickers without a pending order while the session is closed`() = runBlocking {
        coEvery { upbitClient.getTicker(any()) } returns listOf(Ticker(tradePrice = 100.0))
        val reconciled = CompletableDeferred<Unit>()
        coEvery { positionManager.reconcilePendingBuy("KRW-ETH", any(), any()) } answers { reconciled.complete(Unit); null }
        val engine = engine()

        engine.start(listOf("KRW-BTC", "KRW-ETH"), mapOf("KRW-ETH" to pendingBuy("KRW-ETH")))
        try {
            withTimeout(5_000) { reconciled.await() }
        } finally {
            engine.stop()
        }

        coVerify(exactly = 0) { upbitClient.getTicker("KRW-BTC") }
    }
}
