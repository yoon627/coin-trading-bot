package com.trading.bot.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.trading.bot.persistence.entity.TradeRecordEntity
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TradeRoundTripTest {

    private fun rec(
        ticker: String,
        side: String,
        price: Double,
        volume: Double,
        at: String,
        pnlPercent: Double? = null,
        reason: String? = null,
        strategy: String? = null,
    ) = TradeRecordEntity(
        ticker = ticker,
        side = side,
        price = price,
        volume = volume,
        totalAmount = price * volume,
        pnlPercent = pnlPercent,
        reason = reason,
        strategy = strategy,
        userId = 1L,
        createdAt = LocalDateTime.parse(at),
    )

    @Test
    fun `단일 매수-매도가 한 라운드트립으로 조립된다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 100.0, 2.0, "2026-08-01T10:00", strategy = "combined"),
                rec("KRW-BTC", "SELL", 120.0, 2.0, "2026-08-01T14:30", pnlPercent = 19.8, reason = "TAKE_PROFIT"),
            )
        )

        assertEquals(1, rts.size)
        val rt = rts[0]
        assertEquals("KRW-BTC", rt.ticker)
        assertEquals(100.0, rt.entryPrice)
        assertEquals(120.0, rt.exitPrice)
        assertEquals(19.8, rt.pnlPercent)
        assertEquals("TAKE_PROFIT", rt.reason)
        assertEquals("combined", rt.strategy)
        assertFalse(rt.open)
        assertFalse(rt.partial)
        // 240 - 200 = 40 (수수료 미차감 gross)
        assertEquals(40.0, rt.pnlAmountGross)
        // 10:00 → 14:30 = 4시간 30분
        assertEquals(16200L, rt.holdingSeconds)
    }

    @Test
    fun `분할 매수는 금액가중 평단으로 합산된다`() {
        // 100원×1 + 200원×3 = 700원 / 4개 = 175원
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-ETH", "BUY", 100.0, 1.0, "2026-08-01T10:00"),
                rec("KRW-ETH", "BUY", 200.0, 3.0, "2026-08-01T11:00"),
                rec("KRW-ETH", "SELL", 300.0, 4.0, "2026-08-01T12:00"),
            )
        )

        assertEquals(1, rts.size)
        val rt = rts[0]
        assertEquals(175.0, rt.entryPrice)
        assertEquals(2, rt.buyCount)
        assertEquals(700.0, rt.buyAmount)
        assertEquals(4.0, rt.buyVolume)
        // 진입 시각은 최초 매수 시각
        assertEquals(LocalDateTime.parse("2026-08-01T10:00"), rt.entryAt)
    }

    @Test
    fun `미청산 보유분은 open 으로 표시된다`() {
        val rts = assembleRoundTrips(
            listOf(rec("KRW-BTC", "BUY", 100.0, 1.0, "2026-08-01T10:00"))
        )

        assertEquals(1, rts.size)
        val rt = rts[0]
        assertTrue(rt.open)
        assertNull(rt.exitAt)
        assertNull(rt.exitPrice)
        assertNull(rt.pnlPercent)
        assertNull(rt.pnlAmountGross)
        assertNull(rt.holdingSeconds)
    }

    @Test
    fun `여러 티커가 교차 기록돼도 티커별로 분리된다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 100.0, 1.0, "2026-08-01T10:00"),
                rec("KRW-ETH", "BUY", 50.0, 2.0, "2026-08-01T10:30"),
                rec("KRW-BTC", "SELL", 110.0, 1.0, "2026-08-01T11:00"),
                rec("KRW-ETH", "SELL", 60.0, 2.0, "2026-08-01T11:30"),
            )
        )

        assertEquals(2, rts.size)
        val btc = rts.first { it.ticker == "KRW-BTC" }
        val eth = rts.first { it.ticker == "KRW-ETH" }
        assertEquals(100.0, btc.entryPrice)
        assertEquals(10.0, btc.pnlAmountGross)
        assertEquals(50.0, eth.entryPrice)
        assertEquals(20.0, eth.pnlAmountGross)
    }

    @Test
    fun `선행 매수 없는 매도는 partial 로 표시되고 크래시하지 않는다`() {
        val rts = assembleRoundTrips(
            listOf(rec("KRW-BTC", "SELL", 120.0, 1.0, "2026-08-01T14:00", pnlPercent = 5.0, reason = "STOP_LOSS"))
        )

        assertEquals(1, rts.size)
        val rt = rts[0]
        assertTrue(rt.partial)
        assertFalse(rt.open)
        assertNull(rt.entryAt)
        assertNull(rt.entryPrice)
        assertEquals(0, rt.buyCount)
        // 매수 금액을 모르므로 손익 금액은 계산하지 않는다 (0 으로 채우면 가짜 손익이 된다)
        assertNull(rt.pnlAmountGross)
        assertNull(rt.holdingSeconds)
        // DB 에 남아있는 수익률은 그대로 노출
        assertEquals(5.0, rt.pnlPercent)
    }

    @Test
    fun `매도 후 재매수는 별개 라운드트립이 되고 최신순으로 반환된다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 100.0, 1.0, "2026-08-01T10:00"),
                rec("KRW-BTC", "SELL", 110.0, 1.0, "2026-08-01T11:00"),
                rec("KRW-BTC", "BUY", 105.0, 1.0, "2026-08-02T10:00"),
                rec("KRW-BTC", "SELL", 120.0, 1.0, "2026-08-02T11:00"),
            )
        )

        assertEquals(2, rts.size)
        // 최신 라운드트립이 먼저
        assertEquals(LocalDateTime.parse("2026-08-02T11:00"), rts[0].exitAt)
        assertEquals(LocalDateTime.parse("2026-08-01T11:00"), rts[1].exitAt)
        assertEquals(105.0, rts[0].entryPrice)
        assertEquals(100.0, rts[1].entryPrice)
    }

    @Test
    fun `보유 중인 포지션이 청산된 것보다 먼저 온다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 100.0, 1.0, "2026-08-01T10:00"),
                rec("KRW-BTC", "SELL", 110.0, 1.0, "2026-08-01T11:00"),
                rec("KRW-ETH", "BUY", 50.0, 1.0, "2026-08-03T10:00"),
            )
        )

        assertEquals(2, rts.size)
        assertTrue(rts[0].open)
        assertEquals("KRW-ETH", rts[0].ticker)
    }

    @Test
    fun `빈 입력은 빈 목록을 반환한다`() {
        assertEquals(emptyList<TradeRoundTrip>(), assembleRoundTrips(emptyList()))
    }

    /**
     * 프론트(`screens.jsx` OrdersPage)가 읽는 키와 실제 와이어 포맷이 어긋나면 화면이 조용히 빈다.
     * 앱은 Jackson SNAKE_CASE 전략(`application.yml`)을 쓰므로 그 변환 결과를 계약으로 고정한다.
     */
    @Test
    fun `DTO 는 프론트가 읽는 snake_case 키로 직렬화된다`() {
        val mapper = ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(JavaTimeModule())

        val rt = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 100.0, 2.0, "2026-08-01T10:00", strategy = "combined"),
                rec("KRW-BTC", "SELL", 120.0, 2.0, "2026-08-01T14:30", pnlPercent = 19.8, reason = "TAKE_PROFIT"),
            )
        ).single()
        val json = mapper.writeValueAsString(rt)

        listOf(
            "ticker", "entry_at", "entry_price", "buy_count", "buy_amount", "buy_volume",
            "exit_at", "exit_price", "sell_amount", "sell_volume",
            "pnl_percent", "pnl_amount_gross", "holding_seconds", "reason", "strategy", "open", "partial",
        ).forEach { key ->
            assertTrue(json.contains("\"$key\""), "직렬화 JSON 에 키가 없다: $key — $json")
        }
    }
}
