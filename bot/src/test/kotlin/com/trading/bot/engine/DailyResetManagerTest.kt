package com.trading.bot.engine

import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DailyResetManagerTest {

    private val manager = DailyResetManager(TradingProperties())
    private val kst = ZoneId.of("Asia/Seoul")

    private fun fixedClock(dateTime: String): Clock =
        Clock.fixed(LocalDateTime.parse(dateTime).atZone(kst).toInstant(), kst)

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

    // --- maxHoldDays / Clock 주입 (#27 보유지평 일반화) ---

    @Test
    fun `getTradingDate uses previous day just before 9am`() {
        val m = DailyResetManager(TradingProperties(), fixedClock("2026-06-11T08:59:59"))
        assertEquals(LocalDate.of(2026, 6, 10), m.getTradingDate())
    }

    @Test
    fun `getTradingDate uses same day at 9am boundary`() {
        val m = DailyResetManager(TradingProperties(), fixedClock("2026-06-11T09:00:00"))
        assertEquals(LocalDate.of(2026, 6, 11), m.getTradingDate())
    }

    @Test
    fun `shouldSellForDailyReset with maxHoldDays 3 holds until third day`() {
        val m = DailyResetManager(TradingProperties(maxHoldDays = 3), fixedClock("2026-06-11T10:00:00"))
        val heldTwoDays = TradingState("KRW-BTC", position = true, buyDate = LocalDate.of(2026, 6, 9))
        assertFalse(m.shouldSellForDailyReset(heldTwoDays))
        val heldThreeDays = TradingState("KRW-BTC", position = true, buyDate = LocalDate.of(2026, 6, 8))
        assertTrue(m.shouldSellForDailyReset(heldThreeDays))
    }

    @Test
    fun `shouldSellForDailyReset coerces maxHoldDays 0 to 1`() {
        // env 오설정(0/음수)이 매수 당일 즉시 청산 루프가 되지 않도록 — 1과 동일 동작.
        val m = DailyResetManager(TradingProperties(maxHoldDays = 0), fixedClock("2026-06-11T10:00:00"))
        val boughtToday = TradingState("KRW-BTC", position = true, buyDate = LocalDate.of(2026, 6, 11))
        assertFalse(m.shouldSellForDailyReset(boughtToday))
        val boughtYesterday = TradingState("KRW-BTC", position = true, buyDate = LocalDate.of(2026, 6, 10))
        assertTrue(m.shouldSellForDailyReset(boughtYesterday))
    }

    @Test
    fun `shouldSellForDailyReset returns false when buyDate is ahead of trading date`() {
        // 00:00~09:00 매수분: buyDate(달력일)=오늘 > tradingDate=어제 — 음수 경과는 미청산.
        val m = DailyResetManager(TradingProperties(), fixedClock("2026-06-11T08:30:00"))
        val state = TradingState("KRW-BTC", position = true, buyDate = LocalDate.of(2026, 6, 11))
        assertFalse(m.shouldSellForDailyReset(state))
    }
}
