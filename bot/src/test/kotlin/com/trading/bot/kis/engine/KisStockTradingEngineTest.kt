package com.trading.bot.kis.engine

import com.trading.bot.domain.SellReason
import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.marketdata.KisMarketCalendar
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KisStockTradingEngineTest {
    private lateinit var pm: StockPositionManager
    private lateinit var engine: KisStockTradingEngine

    @BeforeEach
    fun setup() {
        pm = mockk()
        val strategy = mockk<TradingStrategy>()
        every { strategy.name } returns "rsi"
        every { strategy.minCandles } returns 21
        engine = KisStockTradingEngine(
            userId = 1L, positionManager = pm, client = mockk<KisClient>(),
            strategies = listOf(strategy), tradingProperties = TradingProperties(),
            marketDataStore = mockk<MarketDataStore>(), marketCalendar = mockk<KisMarketCalendar>(),
            liveEnabled = false,
        )
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
}
