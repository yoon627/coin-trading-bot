package com.trading.bot.kis.engine

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #64 — durable flush 대상 변경만 dirty 로 잡히는지, 거래일 리셋이 저장된 날짜를 근거로 도는지. */
class StockPositionDurableTest {

    private val today = LocalDate.of(2026, 7, 29)

    @Test
    fun `peak update marks dirty only when the peak actually rises`() {
        val pos = StockPosition("005930").apply { position = true; markDurableClean() }

        pos.updatePeak(80_000)
        assertTrue(pos.durableDirty)

        pos.markDurableClean()
        pos.updatePeak(79_000) // 고점 아래 — 저장할 변화가 없다
        assertFalse(pos.durableDirty, "고점이 안 오르면 매 tick 저장돼선 안 된다(write 증폭)")

        pos.updatePeak(81_000)
        assertTrue(pos.durableDirty)
    }

    @Test
    fun `daily reset opens the gate only when the trading day changed`() {
        val pos = StockPosition("005930").apply { markBoughtAccepted("rsi", today) }

        pos.resetDaily(today)
        assertTrue(pos.boughtToday, "같은 거래일에는 진입 게이트가 유지돼야 한다")

        pos.markDurableClean()
        pos.resetDaily(today.plusDays(1))
        assertFalse(pos.boughtToday)
        assertTrue(pos.durableDirty, "게이트가 열린 것도 저장 대상 변화다")
    }

    @Test
    fun `flat sync clears entry metadata so the next entry does not inherit it`() {
        val pos = StockPosition("005930").apply {
            markBoughtAccepted("rsi", today)
            syncFromHolding(10, 70_000.0)
            updatePeak(90_000)
            markDurableClean()
        }

        pos.syncFromHolding(0, 0.0) // 거래소에서 청산 확인

        assertEquals(0.0, pos.peakPrice)
        assertEquals(null, pos.entryStrategy)
        assertTrue(pos.durableDirty)
    }
}
