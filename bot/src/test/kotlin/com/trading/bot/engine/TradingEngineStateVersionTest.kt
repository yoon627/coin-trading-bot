package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderTrade
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.Ticker
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradingState
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.TradingStateService
import com.trading.common.strategy.TradingStrategy
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** 한 번 충돌한 종목을 tick 마다 돌려도 ERROR 는 한 번이고, 정지 때 재기록도 하지 않는다(#365). */
class TradingEngineStateVersionTest {

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>()
    private val store = VersionedStateStore(stateService)
    private val manager = PositionManager(
        upbit, TradingProperties(), stateService, 1L,
        commitFill = { persist, _: TradeRecord -> persist(); true },
    )
    private val strategy = mockk<TradingStrategy>(relaxed = true)
    private val marketData = mockk<MarketDataStore>(relaxed = true)
    private val engine = TradingEngine(
        upbitClient = upbit,
        positionManager = manager,
        dailyResetManager = mockk(relaxed = true),
        strategy = strategy,
        tradingProperties = TradingProperties(intervalSeconds = 1),
        userId = 1L,
        username = "testuser",
        discordWebhookUrl = null,
        marketDataStore = marketData,
    )

    @Test
    fun `a conflicted ticker with a pending sell alerts once over many ticks and is not rewritten on stop`(): Unit = runBlocking {
        every { marketData.getLatestTicker(any(), any()) } returns null
        coEvery { upbit.getTicker("KRW-BTC") } returns listOf(Ticker(market = "KRW-BTC", tradePrice = 11_000_000.0))
        coEvery { upbit.getOrder("s-1") } returns Order(
            uuid = "s-1", state = "done", executedVolume = "0.01", paidFee = "50",
            trades = listOf(OrderTrade(price = "11000000", volume = "0.01", funds = "110000")),
        )
        val state = TradingState(
            "KRW-BTC", position = true, holdVolume = 0.01, avgBuyPrice = 10_000_000.0, peakPrice = 10_000_000.0,
            bookKnown = true, rowVersion = 5L,
        ).apply { adoptSellOrder("s-1"); pendingSellReason = SellReason.TAKE_PROFIT }
        store.versions["KRW-BTC"] = 5L
        store.writeElsewhere("KRW-BTC")

        val logger = LoggerFactory.getLogger("com.trading.bot") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            repeat(4) { engine.processTicker("KRW-BTC", state, strategy) }
            val errors = appender.list.filter { it.level == Level.ERROR }
            assertEquals(1, errors.size, errors.joinToString { it.formattedMessage })
            assertTrue(state.hasPendingSell(), "체결을 기록할 수 없어 pending 이 남는다")

            engine.start(listOf("KRW-BTC"), linkedMapOf("KRW-BTC" to state))
            engine.stop()
            val writes = store.writes
            engine.flushUnpersisted()

            assertEquals(writes, store.writes, "충돌 종목은 정지 때 다시 쓰지 않는다")
            assertEquals(listOf(state), engine.unpersistedSells(), "DB 에 없을 수 있는 매도라 reload 를 막는다")

            // 정지 뒤 시작은 DB 의 행을 다시 읽는다. 그 상태는 행의 버전을 들고 있어 저장된다.
            val reloaded = TradingState("KRW-BTC", position = true, holdVolume = 0.01, rowVersion = store.versions["KRW-BTC"])
            manager.persistState(reloaded)
            assertFalse(reloaded.versionConflict)
            assertEquals(store.versions["KRW-BTC"], reloaded.rowVersion)
        } finally {
            logger.detachAppender(appender)
        }
    }
}
