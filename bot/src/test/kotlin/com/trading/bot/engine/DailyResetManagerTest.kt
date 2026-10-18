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
        states["KRW-BTC"]!!.boughtToday = true

        val secondResult = manager.checkAndReset(states)
        assertFalse(secondResult)
        assertTrue(states["KRW-BTC"]!!.boughtToday)
    }

    // --- 재시작 뒤 boughtToday ---

    @Test
    fun `checkAndReset keeps boughtToday for state bought on the current trading date`() {
        // 재시작 직후엔 lastResetDate 가 없어 첫 tick 이 리셋을 호출한다.
        // 이때 복원된 boughtToday 를 지우면 당일 재매수가 열린다.
        val m = DailyResetManager(TradingProperties(), fixedClock("2026-06-11T10:00:00"))
        val states = mapOf(
            "KRW-BTC" to TradingState("KRW-BTC", boughtToday = true, boughtDate = LocalDate.of(2026, 6, 11)),
        )

        m.checkAndReset(states)

        assertTrue(states["KRW-BTC"]!!.boughtToday)
    }

    @Test
    fun `checkAndReset clears boughtToday for state bought on a previous trading date`() {
        // 09:00 경계를 넘겨 재시작하면 어제의 boughtToday 는 해제돼야 당일 매수가 열린다.
        val m = DailyResetManager(TradingProperties(), fixedClock("2026-06-11T10:00:00"))
        val states = mapOf(
            "KRW-BTC" to TradingState("KRW-BTC", boughtToday = true, boughtDate = LocalDate.of(2026, 6, 10)),
        )

        m.checkAndReset(states)

        assertFalse(states["KRW-BTC"]!!.boughtToday)
    }

    @Test
    fun `checkAndReset resets only the states whose trading date rolled over`() {
        val m = DailyResetManager(TradingProperties(), fixedClock("2026-06-11T10:00:00"))
        val states = mapOf(
            "KRW-BTC" to TradingState("KRW-BTC", boughtToday = true, boughtDate = LocalDate.of(2026, 6, 10)),
            "KRW-ETH" to TradingState("KRW-ETH", boughtToday = true, boughtDate = LocalDate.of(2026, 6, 11)),
        )

        m.checkAndReset(states)

        assertFalse(states["KRW-BTC"]!!.boughtToday)
        assertTrue(states["KRW-ETH"]!!.boughtToday)
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

    // --- maxHoldDays ---

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
        // 0 이하로 잘못 설정해도 매수 당일 청산이 반복되지 않게 1 과 같이 동작한다.
        val m = DailyResetManager(TradingProperties(maxHoldDays = 0), fixedClock("2026-06-11T10:00:00"))
        val boughtToday = TradingState("KRW-BTC", position = true, buyDate = LocalDate.of(2026, 6, 11))
        assertFalse(m.shouldSellForDailyReset(boughtToday))
        val boughtYesterday = TradingState("KRW-BTC", position = true, buyDate = LocalDate.of(2026, 6, 10))
        assertTrue(m.shouldSellForDailyReset(boughtYesterday))
    }

    // --- 보유상한 초과 ---

    private fun warningsWhileDeciding(block: () -> Unit): List<String> {
        val logger = org.slf4j.LoggerFactory.getLogger(DailyResetManager::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.filter { it.level == ch.qos.logback.classic.Level.WARN }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `shouldSellForDailyReset warns once per position from the first overrun day`() {
        // maxHoldDays=1 이면 초과 첫날(경과 2일)부터 잡혀야 한다.
        val m = DailyResetManager(TradingProperties(maxHoldDays = 1), fixedClock("2026-07-21T10:00:00"), userId = 4)
        val overrun = TradingState("KRW-BTC", position = true, buyDate = LocalDate.of(2026, 7, 19))

        val warnings = warningsWhileDeciding {
            assertTrue(m.shouldSellForDailyReset(overrun))
            assertTrue(m.shouldSellForDailyReset(overrun))
        }

        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("KRW-BTC held 2 trading days (limit 1"), warnings.single())
        assertTrue(warnings.single().contains("user 4"), warnings.single())
    }

    @Test
    fun `shouldSellForDailyReset warns again for a new position or another ticker`() {
        val m = DailyResetManager(TradingProperties(maxHoldDays = 1), fixedClock("2026-07-24T10:00:00"))
        val first = TradingState("KRW-BTC", position = true, buyDate = LocalDate.of(2026, 7, 17))
        val rebought = TradingState("KRW-BTC", position = true, buyDate = LocalDate.of(2026, 7, 21))
        val other = TradingState("KRW-ETH", position = true, buyDate = LocalDate.of(2026, 7, 19))

        val warnings = warningsWhileDeciding {
            m.shouldSellForDailyReset(first)
            m.shouldSellForDailyReset(rebought)
            m.shouldSellForDailyReset(other)
            m.shouldSellForDailyReset(rebought)
        }

        assertEquals(3, warnings.size, warnings.toString())
    }

    @Test
    fun `shouldSellForDailyReset does not warn when the hold limit fires on time`() {
        val m = DailyResetManager(TradingProperties(maxHoldDays = 1), fixedClock("2026-07-21T10:00:00"))
        val onTime = TradingState("KRW-BTC", position = true, buyDate = LocalDate.of(2026, 7, 20))

        val warnings = warningsWhileDeciding {
            assertTrue(m.shouldSellForDailyReset(onTime))
        }

        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun `shouldSellForDailyReset returns false when buyDate is ahead of trading date`() {
        // 00:00~09:00 매수분은 buyDate(달력일)가 tradingDate 보다 늦다. 경과가 음수면 청산하지 않는다.
        val m = DailyResetManager(TradingProperties(), fixedClock("2026-06-11T08:30:00"))
        val state = TradingState("KRW-BTC", position = true, buyDate = LocalDate.of(2026, 6, 11))
        assertFalse(m.shouldSellForDailyReset(state))
    }
}
