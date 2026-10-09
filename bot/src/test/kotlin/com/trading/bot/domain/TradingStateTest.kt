package com.trading.bot.domain

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TradingStateTest {

    @Test
    fun `pnlPercent calculates positive PnL correctly`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        assertEquals(4.0, state.pnlPercent(52000000.0), 0.01)
    }

    @Test
    fun `pnlPercent calculates negative PnL correctly`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        assertEquals(-2.0, state.pnlPercent(49000000.0), 0.01)
    }

    @Test
    fun `pnlPercent returns 0 when avgBuyPrice is 0`() {
        val state = TradingState("KRW-BTC")
        assertEquals(0.0, state.pnlPercent(50000000.0))
    }

    @Test
    fun `markBought stamps the trading date, not the calendar date`() {
        // 08:00 매수는 거래일 기준 전날(09:00 리셋 전)에 속한다 — 달력일로 찍으면 다음 tick 의
        // 리셋이 boughtToday 를 즉시 해제해 같은 거래일에 재진입이 뚫린다.
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001, now = LocalDateTime.of(2026, 6, 11, 8, 0))
        assertEquals(LocalDate.of(2026, 6, 10), state.boughtDate)

        val afterReset = TradingState("KRW-ETH")
        afterReset.markBought(3000000.0, 0.01, now = LocalDateTime.of(2026, 6, 11, 9, 0))
        assertEquals(LocalDate.of(2026, 6, 11), afterReset.boughtDate)
    }

    @Test
    fun `resetDaily clears boughtToday only when the trading date rolled over`() {
        val state = TradingState("KRW-BTC", boughtToday = true, boughtDate = LocalDate.of(2026, 6, 11))

        state.resetDaily(LocalDate.of(2026, 6, 11))
        assertTrue(state.boughtToday)

        state.resetDaily(LocalDate.of(2026, 6, 12))
        assertFalse(state.boughtToday)
    }

    @Test
    fun `dropFromPeakPercent calculates correctly`() {
        val state = TradingState("KRW-BTC")
        state.updatePeakPrice(55000000.0)
        // Drop: (55M - 53M) / 55M * 100 = 3.636%
        assertEquals(3.636, state.dropFromPeakPercent(53000000.0), 0.01)
    }

    @Test
    fun `dropFromPeakPercent returns 0 when peak is 0`() {
        val state = TradingState("KRW-BTC")
        assertEquals(0.0, state.dropFromPeakPercent(50000000.0))
    }

    @Test
    fun `updatePeakPrice only increases`() {
        val state = TradingState("KRW-BTC")
        state.updatePeakPrice(50000000.0)
        assertEquals(50000000.0, state.peakPrice)
        state.updatePeakPrice(55000000.0)
        assertEquals(55000000.0, state.peakPrice)
        state.updatePeakPrice(52000000.0) // lower, should not update
        assertEquals(55000000.0, state.peakPrice)
    }

    @Test
    fun `markBought sets initial position correctly`() {
        val state = TradingState("KRW-BTC")
        assertFalse(state.position)

        state.markBought(50000000.0, 0.001)

        assertTrue(state.position)
        assertEquals(50000000.0, state.avgBuyPrice)
        assertEquals(0.001, state.holdVolume)
        assertNotNull(state.buyDate)
        assertNotNull(state.lastTradeTime)
    }

    @Test
    fun `markBought sets boughtToday so daily entry gate engages`() {
        val state = TradingState("KRW-BTC")
        assertFalse(state.boughtToday)

        state.markBought(50000000.0, 0.001)

        assertTrue(state.boughtToday)
    }

    @Test
    fun `markBought calculates weighted average for additional buys`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001) // 50K spent
        state.markBought(60000000.0, 0.001) // 60K spent

        // Average: (50M * 0.001 + 60M * 0.001) / (0.001 + 0.001) = 55M
        assertEquals(55000000.0, state.avgBuyPrice, 0.01)
        assertEquals(0.002, state.holdVolume, 0.0001)
    }

    @Test
    fun `markSold resets all position state`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        assertTrue(state.position)

        state.markSold()

        assertFalse(state.position)
        assertEquals(0.0, state.avgBuyPrice)
        assertEquals(0.0, state.holdVolume)
        assertEquals(0.0, state.peakPrice)
        assertNull(state.buyDate)
        assertNotNull(state.lastTradeTime)
    }

    @Test
    fun `resetDaily clears boughtToday when the state has no recorded trading date`() {
        // boughtDate = null 은 이 컬럼 이전에 저장된 상태 — 거래일을 모르므로 보수적으로 해제한다.
        val state = TradingState("KRW-BTC", boughtToday = true)
        state.resetDaily(LocalDate.of(2026, 6, 11))
        assertFalse(state.boughtToday)
    }

    @Test
    fun `peak price updates during markBought`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        assertEquals(50000000.0, state.peakPrice)

        state.markBought(55000000.0, 0.001) // higher price
        assertEquals(55000000.0, state.peakPrice)
    }

    @Test
    fun `markBought stores entryStrategy on initial entry`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001, "macd_cross")
        assertEquals("macd_cross", state.entryStrategy)
    }

    @Test
    fun `markBought preserves entryStrategy on additional buys`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001, "macd_cross")
        state.markBought(60000000.0, 0.001, "golden_cross") // 추가매수 — 최초 진입 전략 유지
        assertEquals("macd_cross", state.entryStrategy)
    }

    @Test
    fun `markSold clears entryStrategy`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001, "macd_cross")
        state.markSold()
        assertNull(state.entryStrategy)
    }

    @Test
    fun `markBought replace does not double-count an already-synced position`() {
        // 재시작 복원 시나리오: durable pendingBuyUuid 복원 + syncPosition 이 거래소 잔고를 이미 반영
        val state = TradingState(
            "KRW-BTC",
            position = true,
            avgBuyPrice = 50_000_000.0,
            holdVolume = 0.001,
            entryStrategy = "macd_cross",
            buyDate = LocalDate.of(2026, 7, 19),
            pendingBuyUuid = "uuid-1",
            pendingBuyStrategy = "macd_cross",
        )

        // reconcile completeBuy 는 거래소 실잔고(절대값)로 확정 — averaging 이 아니라 절대 세팅이어야 이중계상이 안 난다.
        state.markBought(50_000_000.0, 0.001, "macd_cross", replace = true)

        assertEquals(0.001, state.holdVolume, 1e-9) // 0.002 (2×) 가 아니라 0.001 유지
        assertEquals(50_000_000.0, state.avgBuyPrice, 0.01)
        assertNull(state.pendingBuyUuid) // pending 해소
    }

    @Test
    fun `markBought replace preserves durable buyDate and entryStrategy`() {
        val entryDate = LocalDate.of(2026, 7, 19)
        val state = TradingState(
            "KRW-BTC",
            position = true,
            avgBuyPrice = 50_000_000.0,
            holdVolume = 0.001,
            entryStrategy = "macd_cross",
            buyDate = entryDate,
            pendingBuyUuid = "uuid-1",
        )

        // 다음 거래일 재시작 시점에 확정되어도 진입일/진입전략은 최초 값을 유지(now 로 덮지 않음).
        state.markBought(50_000_000.0, 0.001, "golden_cross", replace = true)

        assertEquals(entryDate, state.buyDate)
        assertEquals("macd_cross", state.entryStrategy)
    }

    @Test
    fun `a pending order is identified by its uuid once known and by its identifier until then`() {
        val state = TradingState("KRW-BTC")
        state.beginBuyOrder("ctb-b", "combined", priorVolume = 0.5)
        assertTrue(state.hasPendingBuy())

        state.adoptBuyOrder("u-b")
        assertEquals("u-b", state.pendingBuyUuid)
        assertNull(state.pendingBuyIdentifier)
        assertTrue(state.hasPendingBuy())

        state.beginSellOrder("ctb-s", SellReason.STOP_LOSS, since = java.time.Instant.EPOCH, volume = 1.0, triggerPrice = 100.0, priorVolume = 1.0)
        assertTrue(state.hasPendingSell())
        state.adoptSellOrder("u-s")
        assertEquals("u-s", state.pendingSellUuid)
        assertNull(state.pendingSellIdentifier)
    }

    @Test
    fun `every transition that ends a pending order also forgets its identifier`() {
        // identifier 만 남으면 끝난 주문이 계속 pending 으로 보여 그 티커의 매매가 영영 멈춘다(#227).
        fun pending() = TradingState("KRW-BTC", position = true, pendingBuyIdentifier = "ctb-b", pendingSellIdentifier = "ctb-s")
        assertTrue(pending().hasPendingBuy() && pending().hasPendingSell())

        val bought = pending().apply { markBought(10.0, 1.0, replace = true) }
        assertFalse(bought.hasPendingBuy())
        listOf(pending().apply { releaseHoldings() }, pending().apply { markSold() }).forEach {
            assertFalse(it.hasPendingBuy())
            assertFalse(it.hasPendingSell())
        }
        assertFalse(pending().apply { clearPendingBuy() }.hasPendingBuy())
        assertFalse(pending().apply { clearPendingSell() }.hasPendingSell())
    }

    @Test
    fun `clearHalt resets halt state`() {
        val state = TradingState(
            "KRW-BTC",
            halted = true,
            haltReason = "reconcile failures exceeded",
            reconcileFailureCount = 5,
        )
        state.clearHalt()
        assertFalse(state.halted)
        assertNull(state.haltReason)
        assertEquals(0, state.reconcileFailureCount)
    }

    // --- 사람이 거래소를 확인하고 막힌 pending 을 지운다 (#246) ---

    private val today = LocalDate.of(2026, 10, 8)

    @Test
    fun `releasing a buy pending clears its fields and leaves the entry trace a fill would leave`() {
        // 주문 전에 진입 메타를 지워 두므로 pending 만 지우면 흔적이 없다 — 목록 밖이면 다음 시작이 싣지 않고, 실려도 보유상한이 안 걸린다.
        val state = TradingState("KRW-BTC", pendingBuyIdentifier = "ctb-1", pendingBuyStrategy = "combined", pendingBuyPriorVolume = 0.0, reconcileFailureCount = 2)

        state.releasePending(TradeSide.BUY, today)

        assertFalse(state.hasPendingBuy())
        assertNull(state.pendingBuyStrategy)
        assertNull(state.pendingBuyPriorVolume)
        assertEquals("combined", state.entryStrategy)
        assertEquals(today, state.buyDate)
        assertTrue(state.boughtToday)
        assertEquals(today, state.boughtDate)
        assertEquals(0, state.reconcileFailureCount)
        assertFalse(state.position, "보유 여부는 다음 시작의 잔고 동기화가 정한다")
    }

    @Test
    fun `releasing a sell pending clears all its fields and keeps the position`() {
        val state = TradingState("KRW-BTC", position = true, holdVolume = 0.001, entryStrategy = "combined", buyDate = today).apply {
            beginSellOrder("ctb-s", SellReason.STOP_LOSS, since = java.time.Instant.EPOCH, volume = 0.001, triggerPrice = 1.0, priorVolume = 0.001)
            adoptSellOrder("u-s")
        }

        state.releasePending(TradeSide.SELL, today)

        assertFalse(state.hasPendingSell())
        assertEquals(state.copy().apply { clearPendingSell() }, state, "매도 pending 필드가 남았다")
        assertTrue(state.position)
        assertEquals(0.001, state.holdVolume)
        assertEquals("combined", state.entryStrategy)
    }

    @Test
    fun `the pending reference names the uuid when known and the identifier otherwise`() {
        val byIdentifier = TradingState("KRW-BTC", pendingBuyIdentifier = "ctb-1")
        val byUuid = TradingState("KRW-BTC", pendingSellUuid = "u-1", pendingSellIdentifier = null)

        assertEquals(PendingRef("identifier", "ctb-1"), byIdentifier.pendingRef(TradeSide.BUY))
        assertEquals(PendingRef("uuid", "u-1"), byUuid.pendingRef(TradeSide.SELL))
        assertNull(byIdentifier.pendingRef(TradeSide.SELL))
    }

    @Test
    fun `the cleared fields are listed in full by column name so a release can be undone by hand`() {
        val buy = TradingState("KRW-BTC", pendingBuyIdentifier = "ctb-1", pendingBuyStrategy = "combined").pendingFields(TradeSide.BUY)
        assertEquals(4, buy.size)
        assertEquals("ctb-1", buy["pending_buy_identifier"])
        assertEquals("combined", buy["pending_buy_strategy"])
        assertEquals(9, TradingState("KRW-BTC").pendingFields(TradeSide.SELL).size)
    }

    @Test
    fun `releasing a buy pending keeps an entry trace that is already there`() {
        // dust 흡수처럼 기존 보유 위의 주문이면 원래 진입 메타가 정본이다.
        val earlier = LocalDate.of(2026, 10, 1)
        val state = TradingState("KRW-BTC", position = true, entryStrategy = "first", buyDate = earlier, pendingBuyIdentifier = "ctb-1", pendingBuyStrategy = "second")

        state.releasePending(TradeSide.BUY, today)

        assertEquals("first", state.entryStrategy)
        assertEquals(earlier, state.buyDate)
    }

    @Test
    fun `releasing the sell of a position with no entry trace leaves today as its buy date`() {
        // 잔고 동기화로만 잡힌 보유는 흔적이 pending 매도뿐이다 — 지운 뒤 목록 밖이면 다음 시작이 싣지 않아 코인이 방치된다.
        val state = TradingState("KRW-BTC", position = true, holdVolume = 0.001, pendingSellIdentifier = "ctb-s")

        state.releasePending(TradeSide.SELL, today)

        assertEquals(today, state.buyDate)
    }
}
