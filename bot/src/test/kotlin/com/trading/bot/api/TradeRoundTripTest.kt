package com.trading.bot.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.trading.bot.persistence.entity.TradeRecordEntity
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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

    /**
     * 잔량이 남았다고 매도 정보까지 비우면 **이미 실현된 매도가 화면에서 통째로 사라진다** —
     * "10개 사서 4개 팔고 6개 보유 중"이 "10개 사서 보유 중"으로만 보인다.
     */
    @Test
    fun `부분 매도 후 잔량이 남아도 실현된 매도는 노출한다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 100.0, 10.0, "2026-08-01T10:00"),
                rec("KRW-BTC", "SELL", 120.0, 4.0, "2026-08-01T12:00", pnlPercent = 19.9, reason = "MANUAL"),
            )
        )

        val rt = rts.single()
        // 매도가 있었으므로 '매도 없음'(open) 은 아니다 — 잔량이 남은 일부 청산이다
        assertFalse(rt.open)
        assertTrue(rt.partiallyClosed)
        assertEquals(1, rt.sellCount)
        // 실현된 매도 정보는 그대로 보인다
        assertEquals(LocalDateTime.parse("2026-08-01T12:00"), rt.exitAt)
        assertEquals(120.0, rt.exitPrice)
        assertEquals(19.9, rt.pnlPercent)
        // 판 만큼의 원가만 차감: 480 − 평단 100 × 매도 4 = 80.
        // 전체 매수액을 빼면 480 − 1000 = −520 이라 팔지도 않은 6개가 손실로 잡힌다.
        assertEquals(80.0, rt.pnlAmountGross)
    }

    /**
     * 엔진 매수 수량은 거래소 실잔고라 매도 수량과 직접 비교할 수 있다.
     * 작은 초과도 그만큼의 원가를 모르는 건 같으므로 비율 여유를 두면 그 초과가 손익에 섞인다.
     */
    @Test
    fun `실측 매수라면 초과 매도가 아주 적어도 손익을 계산하지 않는다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 100.0, 100.0, "2026-08-01T10:00", strategy = "combined"),
                // 0.5% 초과 — 비율 톨러런스를 뒀다면 정상 매도로 새어나간다
                rec("KRW-BTC", "SELL", 120.0, 100.5, "2026-08-01T12:00", pnlPercent = 19.9, reason = "MANUAL"),
            )
        )

        val rt = rts.single()
        assertTrue(rt.partial)
        assertNull(rt.pnlAmountGross)
    }

    /**
     * 수동 매수는 `주문금액 / 조회시점 가격` 으로 **추정**한 수량을 남기는데(#105) 매도는 실제 잔고를 판다.
     * 그래서 이전 포지션이 없는 정상 매매에서도 매도가 조금 더 많게 기록될 수 있다 — 이를 초과 매도로
     * 단정하면 멀쩡한 거래의 손익이 화면에서 사라진다.
     */
    @Test
    fun `추정 매수의 작은 기록 오차는 초과 매도로 보지 않는다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 100.0, 100.0, "2026-08-01T10:00", strategy = "manual"),
                // 0.3% 차이 — 추정 수량과 실제 체결의 오차 범위
                rec("KRW-BTC", "SELL", 120.0, 100.3, "2026-08-01T12:00", pnlPercent = 19.9, reason = "MANUAL"),
            )
        )

        val rt = rts.single()
        assertFalse(rt.partial, "기록 오차를 초과 매도로 오인하면 정상 거래의 손익이 사라진다")
        assertNotNull(rt.pnlAmountGross)
    }

    /**
     * 수동 `sellAll` 은 거래소 잔고 전체를 팔기 때문에 이전 포지션의 잔여분까지 매도 수량에 들어간다.
     * 그 잔여분의 원가는 이 그룹에 없으므로 신규 평단을 적용하면 손익이 부풀려진다.
     */
    @Test
    fun `매수보다 많이 팔렸으면 손익을 계산하지 않는다`() {
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 100.0, 2.0, "2026-08-01T10:00", strategy = "manual"),
                rec("KRW-BTC", "SELL", 120.0, 3.0, "2026-08-01T12:00", pnlPercent = 19.9, reason = "MANUAL"),
            )
        )

        val rt = rts.single()
        assertTrue(rt.partial, "잔여분 원가를 모르므로 매수 기반 값을 신뢰할 수 없다")
        assertNull(rt.pnlAmountGross)
        // 매도 자체의 값은 그대로 노출한다
        assertEquals(120.0, rt.exitPrice)
        assertEquals(19.9, rt.pnlPercent)
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

    // --- 엔진 매수 기록은 증분이 아니라 누적 스냅샷이다 ---
    // PositionManager.completeBuy 는 거래소 전체 잔고·평단을 그대로 적는다(#20 — syncPosition 복원분과
    // 이중계상되지 않게). 그래서 한 포지션의 엔진 BUY 행을 합산하면 수량이 부풀고 잔량이 0 이 되지 않는다.
    // 반면 수동 매수(executeBuy)는 그 주문의 증분을 적으므로 합산이 맞다.

    @Test
    fun `수동 매수 위에 엔진이 매수하면 엔진 기록이 포지션 전체를 담는다`() {
        // 운영 실측(KRW-BTC 2026-06): manual 0.00009832 → engine 0.00022853 → 전량 매도 0.00022853.
        // 합산하면 0.00032685 가 되어 전량 매도인데도 잔량이 남은 것처럼 보인다.
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-BTC", "BUY", 101712000.0, 0.00009832, "2026-06-02T11:59", strategy = "manual"),
                rec("KRW-BTC", "BUY", 94677000.0, 0.00022853, "2026-06-11T08:14", strategy = "rsi_bounce"),
                rec("KRW-BTC", "SELL", 98466000.0, 0.00022853, "2026-06-14T21:40", pnlPercent = 3.902),
            )
        )

        val rt = rts.single()
        assertFalse(rt.open, "전량 매도했으므로 청산된 라운드트립이어야 한다")
        assertEquals(0.00022853, rt.buyVolume, 1e-12)
        assertEquals(94677000.0 * 0.00022853, rt.buyAmount, 1e-6)
        // 런타임 entryStrategy 와 같은 값이어야 한다 — 수동 매수는 TradingState 를 세우지 않으므로
        // 이 포지션의 진입 전략은 엔진이 적은 rsi_bounce 다. 'manual' 로 잡으면 SELL 기록과 갈린다.
        assertEquals("rsi_bounce", rt.strategy)
    }

    @Test
    fun `수동 매수만 있으면 그대로 manual 로 귀속된다`() {
        val rt = assembleRoundTrips(
            listOf(
                rec("KRW-XRP", "BUY", 2000.0, 10.0, "2026-08-10T01:00", strategy = "manual"),
                rec("KRW-XRP", "SELL", 2100.0, 10.0, "2026-08-11T00:00", pnlPercent = 4.9),
            )
        ).single()

        assertEquals("manual", rt.strategy)
    }

    @Test
    fun `엔진이 두 번 기록해도 마지막 것이 포지션 전체다`() {
        // 재시작 reconcile 등으로 한 포지션에 엔진 BUY 가 두 번 남는 경우.
        val rt = assembleRoundTrips(
            listOf(
                rec("KRW-SOL", "BUY", 2500.0, 4.0, "2026-08-12T01:00", strategy = "knee_reversal"),
                rec("KRW-SOL", "BUY", 3000.0, 5.0, "2026-08-12T02:00", strategy = "knee_reversal"),
                rec("KRW-SOL", "SELL", 3300.0, 5.0, "2026-08-13T00:00", pnlPercent = 9.9),
            )
        ).single()

        assertFalse(rt.open)
        assertEquals(5.0, rt.buyVolume, 1e-9)
        assertEquals(3000.0, rt.entryPrice!!, 1e-9) // 마지막 스냅샷의 평단
    }

    @Test
    fun `수동 매수만 여러 건이면 증분이므로 합산한다`() {
        val rt = assembleRoundTrips(
            listOf(
                rec("KRW-XRP", "BUY", 2000.0, 5.0, "2026-08-10T01:00", strategy = "manual"),
                rec("KRW-XRP", "BUY", 2200.0, 5.0, "2026-08-10T02:00", strategy = "manual"),
                rec("KRW-XRP", "SELL", 2400.0, 10.0, "2026-08-11T00:00", pnlPercent = 14.3),
            )
        ).single()

        assertFalse(rt.open)
        assertEquals(10.0, rt.buyVolume, 1e-9)
        assertEquals(21000.0, rt.buyAmount, 1e-6) // 10000 + 11000
    }

    @Test
    fun `부분 매도 뒤의 새 매수는 별개 라운드트립이다`() {
        // 잔량이 남은 채 새 매수가 들어오면 현재 구현은 전부 한 그룹으로 합쳐 보유기간·손익을 왜곡한다.
        val rts = assembleRoundTrips(
            listOf(
                rec("KRW-ETH", "BUY", 100.0, 10.0, "2026-08-01T10:00", strategy = "manual"),
                rec("KRW-ETH", "SELL", 110.0, 4.0, "2026-08-01T12:00", pnlPercent = 9.9),
                rec("KRW-ETH", "BUY", 120.0, 3.0, "2026-08-02T10:00", strategy = "manual"),
                rec("KRW-ETH", "SELL", 130.0, 9.0, "2026-08-02T15:00", pnlPercent = 8.0),
            )
        )

        assertEquals(2, rts.size, "매도가 시작된 뒤의 매수는 새 포지션으로 끊어야 한다")
    }
}
