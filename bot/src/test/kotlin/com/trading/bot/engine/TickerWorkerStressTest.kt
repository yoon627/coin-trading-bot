package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Ticker
import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest

/** 실 디스패처에서 여러 작업자가 동시에 정리돼도 활성 목록·상태에서 빠지는 것이 없다. */
class TickerWorkerStressTest {

    @RepeatedTest(20)
    fun `eight tickers released at once all leave the active list and their states`(): Unit = runBlocking {
        val upbitClient = mockk<UpbitClient>(relaxed = true)
        val positionManager = mockk<PositionManager>(relaxed = true)
        val dailyResetManager = mockk<DailyResetManager>(relaxed = true)
        val strategy = mockk<TradingStrategy>(relaxed = true)
        val store = relaxedMarketDataStore()
        every { store.getLatestTicker(any(), any()) } returns null
        every { strategy.name } returns "test_strategy"
        every { dailyResetManager.getTradingDate() } returns LocalDate.of(2026, 11, 20)
        coEvery { upbitClient.getTicker(any()) } coAnswers { firstArg<String>().split(",").map { Ticker(market = it, tradePrice = 1.0) } }
        val engine = TradingEngine(
            upbitClient = upbitClient, positionManager = positionManager, dailyResetManager = dailyResetManager,
            strategy = strategy, tradingProperties = TradingProperties(intervalSeconds = 1), userId = 1L, marketDataStore = store,
        )
        // 목록 밖이고 진입 메타만 남은 행 — 동기화 뒤 모두 정리된다.
        val leftovers = (1..8).associate { "KRW-T$it" to TradingState("KRW-T$it", entryStrategy = "test_strategy") }

        engine.start(listOf("KRW-BTC"), leftovers)
        try {
            withTimeout(5_000) { while (engine.getActiveTickers().size > 1) delay(10) }

            assertEquals(listOf("KRW-BTC"), engine.getActiveTickers())
            assertEquals(setOf("KRW-BTC"), engine.getStates().keys)
            assertTrue(engine.isLoopAlive())
        } finally {
            engine.stop()
        }
    }
}
