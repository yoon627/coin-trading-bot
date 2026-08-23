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

    /**
     * 엔진 매수는 거래소 실잔고·평단을 남기는 **스냅샷**이라 합산하면 같은 보유분을 두 번 센다.
     * 아래는 "1개 보유(평단 100) → 추가 매수로 3개 보유(평단 200)" 를 뜻한다.
     */
    @Test
    fun `분할 매수는 합산하지 않고 마지막 스냅샷을 쓴다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-ETH", "BUY", 100.0, 1.0, "2026-08-01T10:00"),
                rec("KRW-ETH", "BUY", 200.0, 3.0, "2026-08-01T11:00"),
                rec("KRW-ETH", "SELL", 300.0, 3.0, "2026-08-01T12:00"),
            )
        )

        val rt = rts.single()
        // 합산했다면 평단 175 · 수량 4 · 매수액 700 이 되어 실제보다 부풀려진다
        assertEquals(200.0, rt.entryPrice)
        assertEquals(3.0, rt.buyVolume)
        assertEquals(600.0, rt.buyAmount)
        // 매수 횟수는 그대로 2회
        assertEquals(2, rt.buyCount)
        // 진입 시각은 최초 매수 시각
        assertEquals(LocalDateTime.parse("2026-08-01T10:00"), rt.entryAt)
        // 전량 청산으로 잡힌다
        assertFalse(rt.partiallyClosed)
        assertEquals(300.0, rt.pnlAmountGross)
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
     * 엔진 청산은 전량이지만 `ManualTradeController` 는 수량 지정 매도를 지원한다.
     * SELL 하나를 곧 포지션 종료로 보면 첫 매도가 전체 매수액과 비교돼 손익이 틀리고 나머지는 고아가 된다.
     */
    @Test
    fun `부분 매도 두 번으로 전량 청산되면 한 라운드트립이다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 100.0, 10.0, "2026-08-01T10:00", strategy = "combined"),
                rec("KRW-BTC", "SELL", 110.0, 4.0, "2026-08-01T12:00", pnlPercent = 10.0, reason = "MANUAL"),
                rec("KRW-BTC", "SELL", 120.0, 6.0, "2026-08-01T15:00", pnlPercent = 20.0, reason = "TAKE_PROFIT"),
            )
        )

        assertEquals(1, rts.size)
        val rt = rts[0]
        assertFalse(rt.open)
        assertFalse(rt.partial)
        assertEquals(2, rt.sellCount)
        // 손익은 매도 총액(440+720=1160) − 매수 총액(1000)
        assertEquals(160.0, rt.pnlAmountGross)
        // 매도 평균가 = 1160 / 10
        assertEquals(116.0, rt.exitPrice)
        // 수익률은 수량가중 평균 = (10×4 + 20×6) / 10
        assertEquals(16.0, rt.pnlPercent)
        // 마지막 매도 기준
        assertEquals(LocalDateTime.parse("2026-08-01T15:00"), rt.exitAt)
        assertEquals("TAKE_PROFIT", rt.reason)
    }

    @Test
    fun `부분 매도 후 잔량이 남으면 일부 청산으로 표시된다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 100.0, 10.0, "2026-08-01T10:00"),
                rec("KRW-BTC", "SELL", 120.0, 4.0, "2026-08-01T12:00", pnlPercent = 19.9, reason = "MANUAL"),
            )
        )

        val rt = rts.single()
        // 매도가 있었으므로 open 은 아니다 — 다만 잔량이 남아 일부 청산이다.
        assertFalse(rt.open)
        assertTrue(rt.partiallyClosed)
        assertEquals(1, rt.sellCount)
        // 판 만큼의 원가만 차감한다: 480 − (평단 100 × 매도수량 4) = 80.
        // 전체 매수액을 빼면 480 − 1000 = −520 이라 팔지도 않은 매수분이 손실로 잡힌다.
        assertEquals(80.0, rt.pnlAmountGross)
    }

    /**
     * 운영 데이터(user 4, KRW-BTC)의 실제 패턴 — 매수 스냅샷과 전량 매도가 번갈아 이어진다.
     * 매수를 증분으로 오해해 합산하면 잔량이 0 에 닿지 않아 이 매매들이 전부 한 줄로 뭉쳤다.
     */
    @Test
    fun `매도 후 재매수가 반복돼도 각각 분리된다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 90000000.0, 0.0000983168, "2026-06-02T11:59"),
                rec("KRW-BTC", "BUY", 95000000.0, 0.00022853, "2026-06-11T08:14"),
                rec("KRW-BTC", "SELL", 99000000.0, 0.00022853, "2026-06-14T21:40", pnlPercent = 4.1, reason = "TAKE_PROFIT"),
                rec("KRW-BTC", "BUY", 96000000.0, 0.00020766, "2026-07-14T14:36"),
                rec("KRW-BTC", "SELL", 97000000.0, 0.00020766, "2026-07-16T07:36", pnlPercent = 0.9, reason = "TRAILING_STOP"),
                rec("KRW-BTC", "BUY", 98000000.0, 0.00023017, "2026-07-17T17:47"),
                rec("KRW-BTC", "SELL", 99500000.0, 0.00023017, "2026-07-21T00:00", pnlPercent = 1.4, reason = "DAILY_RESET"),
            )
        )

        // 잔량은 끝까지 0 이 되지 않지만 매도→매수 전환에서 갈린다
        assertEquals(3, rts.size)
        assertEquals(listOf("DAILY_RESET", "TRAILING_STOP", "TAKE_PROFIT"), rts.map { it.reason })
        // 첫 그룹만 매수 2건(팔리지 않은 최초 매수분 포함)
        assertEquals(listOf(1, 1, 2), rts.map { it.buyCount })
    }

    /**
     * 조회 상한으로 앞이 잘리면 남은 첫 BUY 가 분할 매수의 중간일 수 있다.
     * 그때 평단·손익은 일부 매수만으로 계산된 값이라 정상처럼 보여선 안 된다.
     */
    @Test
    fun `앞이 잘려 들어오면 가장 오래된 그룹은 partial 로 표시된다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 200.0, 1.0, "2026-08-01T11:00"),
                rec("KRW-BTC", "SELL", 210.0, 1.0, "2026-08-01T12:00", pnlPercent = 5.0),
                rec("KRW-BTC", "BUY", 100.0, 1.0, "2026-08-02T10:00"),
                rec("KRW-BTC", "SELL", 130.0, 1.0, "2026-08-02T12:00", pnlPercent = 30.0),
            ),
            truncatedHead = true,
        )

        assertEquals(2, rts.size)
        // 최신순이므로 마지막이 가장 오래된 그룹
        val oldest = rts.last()
        assertTrue(oldest.partial, "잘린 쪽 그룹은 매수가 누락됐을 수 있어 partial 이어야 한다")
        assertNull(oldest.pnlAmountGross, "믿을 수 없는 매수액으로 손익을 계산하면 안 된다")
        // 뒤따르는 그룹은 온전하다
        assertFalse(rts.first().partial)
        assertEquals(30.0, rts.first().pnlAmountGross)
    }

    /**
     * 수동 `sellAll` 은 거래소 잔고 전체를 팔기 때문에 이전 포지션에서 넘어온 잔여분까지 매도 수량에 들어간다.
     * 그 잔여분의 원가는 이 그룹에 없으므로 신규 평단을 적용하면 손익이 부풀려진다.
     */
    @Test
    fun `매수보다 많이 팔렸으면 손익을 계산하지 않는다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 100.0, 2.0, "2026-08-01T10:00"),
                // 이전 포지션 잔여분 1개가 섞여 3개가 팔렸다
                rec("KRW-BTC", "SELL", 120.0, 3.0, "2026-08-01T12:00", pnlPercent = 19.9, reason = "MANUAL"),
            )
        )

        val rt = rts.single()
        assertTrue(rt.partial, "잔여분 원가를 모르므로 매수 기반 값을 신뢰할 수 없다")
        assertNull(rt.pnlAmountGross, "신규 평단을 잔여분에 적용하면 손익이 부풀려진다")
        // 매도 자체의 값은 그대로 노출한다
        assertEquals(120.0, rt.exitPrice)
        assertEquals(19.9, rt.pnlPercent)
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
            "sell_count", "exit_at", "exit_price", "sell_amount", "sell_volume",
            "pnl_percent", "pnl_amount_gross", "holding_seconds", "reason", "strategy",
            "open", "partially_closed", "partial",
        ).forEach { key ->
            assertTrue(json.contains("\"$key\""), "직렬화 JSON 에 키가 없다: $key — $json")
        }
    }
}
