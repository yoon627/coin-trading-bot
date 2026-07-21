package com.trading.bot.domain

import java.time.LocalDate
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
    fun `resetDaily clears boughtToday flag`() {
        val state = TradingState("KRW-BTC", boughtToday = true)
        state.resetDaily()
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
}
