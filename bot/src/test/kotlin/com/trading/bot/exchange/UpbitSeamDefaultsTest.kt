package com.trading.bot.exchange

import com.trading.bot.client.FILL_POLL_ATTEMPTS
import com.trading.bot.client.FILL_POLL_DELAY_MS
import com.trading.bot.client.UpbitApiException
import com.trading.bot.client.UpbitOrderErrorClassifier
import com.trading.bot.client.isRejectedAsBelowMinimumOrder
import com.trading.bot.client.provesOrderNotPlaced
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Upbit 기본 seam 이 옮기기 전 식·상수와 같은 값을 내는지 경계값으로 고정한다. */
class UpbitSeamDefaultsTest {

    @Test
    fun `upbit market rules keep the 5000 KRW minimum with the boundary itself sellable`() {
        assertEquals(5_000.0, UpbitMarketRules.minOrderNotional)
        assertTrue(UpbitMarketRules.tracksDust)
        assertFalse(UpbitMarketRules.isBelowMinOrder(0.0005, 10_000_000.0))
        assertTrue(UpbitMarketRules.isBelowMinOrder(0.00049, 10_000_000.0))
        assertTrue(UpbitMarketRules.isDustVolume(0.00049, 10_000_000.0))
    }

    @Test
    fun `upbit dust needs a recorded holding below the minimum`() {
        val held = TradingState("KRW-BTC", position = true, holdVolume = 0.0004)
        assertTrue(UpbitMarketRules.isDust(held, 10_000_000.0))
        assertFalse(UpbitMarketRules.isDust(held, 20_000_000.0))
        assertFalse(UpbitMarketRules.isDust(held.copy(holdVolume = 0.0), 10_000_000.0))
        assertFalse(UpbitMarketRules.isDust(held.copy(position = false), 10_000_000.0))
    }

    @Test
    fun `upbit market buy is the floored KRW amount sent as ord_type price`() {
        val size = UpbitMarketRules.buySize(investAmount = 99_999.99, price = 50_000_000.0)
        assertEquals(MarketBuySize.QuoteAmount(99_999), size)
        assertEquals(
            OrderRequest(market = "KRW-BTC", side = "bid", ordType = "price", price = "99999", identifier = "ctb-1"),
            UpbitMarketRules.buyOrderRequest("KRW-BTC", size, "ctb-1"),
        )
        assertThrows<IllegalArgumentException> { UpbitMarketRules.buyOrderRequest("KRW-BTC", MarketBuySize.Quantity(1), "ctb-2") }
    }

    @Test
    fun `upbit calendar is always open and turns the trading day at 09 00 KST`() {
        assertTrue(UpbitTradingCalendar.isSessionOpen(Instant.parse("2026-10-18T15:00:00Z")))
        val day = LocalDate.of(2026, 10, 18)
        assertEquals(day.minusDays(1), UpbitTradingCalendar.tradingDate(day.atTime(8, 59, 59)))
        assertEquals(day, UpbitTradingCalendar.tradingDate(day.atTime(9, 0)))
        listOf(LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 12, 31, 23, 59)).forEach {
            assertEquals(TradingDay.of(it), UpbitTradingCalendar.tradingDate(it))
        }
    }

    @Test
    fun `upbit calendar reads the trading day in the clock zone like TradingDay`() {
        // 00:30Z 는 KST 09:30(그날)이고 UTC 로는 09:00 전이라 전날이다.
        val instant = Instant.parse("2026-10-18T00:30:00Z")
        listOf(Clock.fixed(instant, TradingDay.KST), Clock.fixed(instant, ZoneOffset.UTC)).forEach { clock ->
            assertEquals(TradingDay.of(clock), UpbitTradingCalendar.tradingDate(clock))
        }
        assertEquals(LocalDate.of(2026, 10, 18), UpbitTradingCalendar.tradingDate(Clock.fixed(instant, TradingDay.KST)))
    }

    @Test
    fun `upbit hold days are calendar days`() {
        assertEquals(0, UpbitTradingCalendar.tradingDaysBetween(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16)))
        // 주말을 건너뛰지 않는다.
        assertEquals(3, UpbitTradingCalendar.tradingDaysBetween(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 19)))
    }

    @Test
    fun `upbit error classifier agrees with the rejection helpers`() {
        val errors = listOf(
            UpbitApiException(400, "insufficient_funds_bid", null, ""),
            UpbitApiException(400, "validation_error", null, ""),
            UpbitApiException(403, "market_offline", null, ""),
            UpbitApiException(400, "under_min_total_bid", null, ""),
            UpbitApiException(400, "under_min_total_ask", null, ""),
            UpbitApiException(500, "under_min_total_ask", null, ""),
            UpbitApiException(399, "insufficient_funds_bid", null, ""),
            UpbitApiException(499, "create_ask_error", null, ""),
            UpbitApiException(400, "duplicated_identifier", null, ""),
            UpbitApiException(429, null, null, ""),
            IOException("connection reset"),
        )
        errors.forEach { e ->
            val expected = when {
                !e.provesOrderNotPlaced() -> OrderFailure.UNKNOWN_OUTCOME
                e.isRejectedAsBelowMinimumOrder() -> OrderFailure.REJECTED_BELOW_MIN_ORDER
                else -> OrderFailure.REJECTED
            }
            assertEquals(expected, UpbitOrderErrorClassifier.classify(e), e.toString())
        }
        assertEquals(OrderFailure.REJECTED_BELOW_MIN_ORDER, UpbitOrderErrorClassifier.classify(errors[3]))
        assertEquals(OrderFailure.UNKNOWN_OUTCOME, UpbitOrderErrorClassifier.classify(errors[5]))
        assertEquals(OrderFailure.REJECTED, UpbitOrderErrorClassifier.classify(errors[0]))
    }

    @Test
    fun `the open order gate allows every order and ignores rejections`() {
        val now = Instant.parse("2026-10-18T00:00:00Z")
        TradeSide.entries.forEach { side ->
            OrderGate.OPEN.onDefinitiveRejection("KRW-BTC", side, now)
            assertTrue(OrderGate.OPEN.mayPlace("KRW-BTC", side, now))
        }
    }

    @Test
    fun `upbit polling and unknown-order constants are unchanged`() {
        assertEquals(FillPollPolicy(10, 300), FillPollPolicy.UPBIT)
        assertEquals(FillPollPolicy(FILL_POLL_ATTEMPTS, FILL_POLL_DELAY_MS), FillPollPolicy.UPBIT)
        assertEquals(UnknownOrderPolicy(2, Duration.ofSeconds(60), supportsClientOrderId = true), UnknownOrderPolicy.UPBIT)
    }
}
