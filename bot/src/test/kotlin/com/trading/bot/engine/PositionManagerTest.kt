package com.trading.bot.engine

import com.trading.bot.client.UpbitAuthProvider
import com.trading.bot.client.UpbitClient
import com.trading.bot.config.UpbitProperties
import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class PositionManagerTest {

    private val config = TradingProperties(takeProfitPct = 2.0, maxLossPct = 5.0)

    // Dummy client - checkTakeProfit/checkStopLoss don't call API
    private val dummyClient = UpbitClient(
        WebClient.builder().build(),
        UpbitAuthProvider(UpbitProperties(accessKey = "x", secretKey = "x")),
    )

    private val manager = PositionManager(dummyClient, config)

    @Test
    fun `checkTakeProfit returns true when pnl exceeds threshold`() {
        val state = TradingState(ticker = "KRW-BTC", position = true, avgBuyPrice = 100.0)
        assertTrue(manager.checkTakeProfit(state, 103.0))
    }

    @Test
    fun `checkTakeProfit returns false when pnl below threshold`() {
        val state = TradingState(ticker = "KRW-BTC", position = true, avgBuyPrice = 100.0)
        assertFalse(manager.checkTakeProfit(state, 101.0))
    }

    @Test
    fun `checkStopLoss returns true when loss exceeds threshold`() {
        val state = TradingState(ticker = "KRW-BTC", position = true, avgBuyPrice = 100.0)
        assertTrue(manager.checkStopLoss(state, 94.0))
    }

    @Test
    fun `checkStopLoss returns false when loss below threshold`() {
        val state = TradingState(ticker = "KRW-BTC", position = true, avgBuyPrice = 100.0)
        assertFalse(manager.checkStopLoss(state, 98.0))
    }

    @Test
    fun `no position means no take profit or stop loss`() {
        val state = TradingState(ticker = "KRW-BTC", position = false)
        assertFalse(manager.checkTakeProfit(state, 200.0))
        assertFalse(manager.checkStopLoss(state, 1.0))
    }
}
