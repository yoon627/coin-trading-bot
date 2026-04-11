package com.trading.bot.engine

import com.trading.bot.domain.TradingState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class DailyResetManagerTest {

    private val manager = DailyResetManager()
    private val kst = ZoneId.of("Asia/Seoul")

    @Test
    fun `getTradingDate returns a date`() {
        val date = manager.getTradingDate()
        assertNotNull(date)
    }

    @Test
    fun `checkAndReset resets states on first call`() {
        val states = mapOf(
            "KRW-BTC" to TradingState("KRW-BTC", boughtToday = true),
            "KRW-ETH" to TradingState("KRW-ETH", boughtToday = true),
        )

        val result = manager.checkAndReset(states)
        assertTrue(result)
        assertFalse(states["KRW-BTC"]!!.boughtToday)
        assertFalse(states["KRW-ETH"]!!.boughtToday)
    }

    @Test
    fun `checkAndReset does not reset twice on same day`() {
        val states = mapOf(
            "KRW-BTC" to TradingState("KRW-BTC", boughtToday = true),
        )

        manager.checkAndReset(states)
        // Set it back to true
        states["KRW-BTC"]!!.boughtToday = true

        val secondResult = manager.checkAndReset(states)
        assertFalse(secondResult)
        assertTrue(states["KRW-BTC"]!!.boughtToday) // not reset again
    }

    @Test
    fun `shouldSellForDailyReset returns false when no position`() {
        val state = TradingState("KRW-BTC", position = false)
        assertFalse(manager.shouldSellForDailyReset(state))
    }

    @Test
    fun `shouldSellForDailyReset returns false when no buy date`() {
        val state = TradingState("KRW-BTC", position = true, buyDate = null)
        assertFalse(manager.shouldSellForDailyReset(state))
    }

    @Test
    fun `shouldSellForDailyReset returns true when bought on previous trading day`() {
        val tradingDate = manager.getTradingDate()
        val state = TradingState(
            "KRW-BTC",
            position = true,
            buyDate = tradingDate.minusDays(1),
        )
        assertTrue(manager.shouldSellForDailyReset(state))
    }

    @Test
    fun `shouldSellForDailyReset returns false when bought today`() {
        val tradingDate = manager.getTradingDate()
        val state = TradingState(
            "KRW-BTC",
            position = true,
            buyDate = tradingDate,
        )
        assertFalse(manager.shouldSellForDailyReset(state))
    }
}
