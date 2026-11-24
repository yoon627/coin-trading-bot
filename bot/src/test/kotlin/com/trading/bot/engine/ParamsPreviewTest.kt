package com.trading.bot.engine

import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.StoredBook
import com.trading.bot.domain.TradingState
import com.trading.bot.domain.UserTradingParams
import com.trading.common.config.TradingProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParamsPreviewTest {
    private val entry = ExitParamsSnapshot(takeProfitPct = 5.0, maxLossPct = 5.0, trailingStopPct = 2.0, trailingArmPct = 3.0, maxHoldDays = 3)
    private fun held(peak: Double = 100.0) = TradingState("KRW-BTC", avgBuyPrice = 100.0, peakPrice = peak, position = true, exitParams = entry)

    @Test
    fun `an unchanged snapshot is not a change`() {
        assertNull(ParamsPreview.change(held(), entry, 97.0, 0))
    }

    // -3% 는 손절 5% 에서는 버티지만 2% 로 좁히면 곧바로 팔린다.
    @Test
    fun `a tighter stop-loss that the current loss already crosses sells at once`() {
        val change = ParamsPreview.change(held(), entry.copy(maxLossPct = 2.0), 97.0, 0)!!

        assertEquals(-3.0, change.pnlPct!!, 1e-9)
        assertEquals(listOf("stop_loss"), change.triggers)
    }

    @Test
    fun `take-profit, trailing and hold days use the same rules as trading`() {
        // 고점 +6% 에서 +4% 로 2% 넘게 빠졌다 — 추적 1% 면 걸리고, 익절 3% 로 낮추면 +4% 도 걸린다.
        val change = ParamsPreview.change(held(peak = 106.0), entry.copy(takeProfitPct = 3.0, trailingStopPct = 1.0), 104.0, 1)!!
        assertEquals(listOf("take_profit", "trailing_stop"), change.triggers)

        val hold = ParamsPreview.change(held(), entry.copy(maxHoldDays = 1), 100.0, 1)!!
        assertEquals(listOf("max_hold_days"), hold.triggers)
    }

    // 멈춘 봇의 상태는 평단이 비어 있고 저장된 장부에만 있다 — 그래도 곧바로 팔릴 포지션을 놓치면 안 된다.
    @Test
    fun `a stopped bot's position uses the stored book price`() {
        val stopped = TradingState("KRW-BTC", storedBook = StoredBook(0.01, 100.0), exitParams = entry)

        assertEquals(listOf("stop_loss"), ParamsPreview.change(stopped, entry.copy(maxLossPct = 2.0), 97.0, 0)!!.triggers)
    }

    // 잔량을 놓아 준 행·매도 중인 행은 저장이 바꾸지 않는다.
    @Test
    fun `released and selling rows are not changed`() {
        assertNull(ParamsPreview.change(held().apply { position = false }, entry.copy(maxLossPct = 2.0), 97.0, 0))
        assertNull(ParamsPreview.change(held().apply { pendingSellUuid = "s" }, entry.copy(maxLossPct = 2.0), 97.0, 0))
    }

    @Test
    fun `an unknown price still shows the change without triggers`() {
        val change = ParamsPreview.change(held(), entry.copy(maxLossPct = 2.0), null, 0)!!

        assertNull(change.pnlPct)
        assertTrue(change.triggers.isEmpty())
    }

    // 사용자가 정하지 않은 항목은 env, env 전용 값이 범위를 벗어나도 사용자 저장은 막지 않는다.
    @Test
    fun `user params overlay env and are range-checked only on the editable fields`() {
        val env = TradingProperties(intervalSeconds = 61, takeProfitPct = 5.0)
        val merged = UserTradingParams(takeProfitPct = 4.0).applyTo(env)

        assertEquals(4.0, merged.takeProfitPct)
        assertEquals(env.maxLossPct, merged.maxLossPct)
        assertTrue(UserTradingParams(takeProfitPct = 4.0).violations(env).isEmpty())
        assertEquals(listOf("trading.max-loss-pct=0.0 (허용 (0, 100))"), UserTradingParams(maxLossPct = 0.0).violations(env))
    }
}
