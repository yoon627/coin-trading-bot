package com.trading.bot.strategy

import com.trading.common.strategy.ExitGates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExitGatesTest {

    @Test
    fun `arm zero with profit and drop over trail fires - current behavior`() {
        assertTrue(ExitGates.isTrailingStopTriggered(0.3, 2.5, 2.1, 2.0, 0.0))
    }

    @Test
    fun `pnl zero never fires`() {
        assertFalse(ExitGates.isTrailingStopTriggered(0.0, 2.5, 2.1, 2.0, 0.0))
    }

    @Test
    fun `pnl negative never fires`() {
        assertFalse(ExitGates.isTrailingStopTriggered(-1.0, 2.5, 2.1, 2.0, 0.0))
    }

    @Test
    fun `drop below trail does not fire`() {
        assertFalse(ExitGates.isTrailingStopTriggered(1.0, 2.5, 1.9, 2.0, 0.0))
    }

    @Test
    fun `drop exactly at trail fires - inclusive boundary`() {
        // 경계값은 발동한다(>=).
        assertTrue(ExitGates.isTrailingStopTriggered(0.5, 2.6, 2.0, 2.0, 0.0))
    }

    @Test
    fun `peak below arm blocks firing`() {
        // arm=0 이면 발동했을 입력을 arm 게이트가 막는다.
        assertFalse(ExitGates.isTrailingStopTriggered(0.7, 3.0, 2.2, 2.0, 5.0))
    }

    @Test
    fun `peak at or above arm allows firing`() {
        assertTrue(ExitGates.isTrailingStopTriggered(3.6, 6.0, 2.3, 2.0, 5.0))
        // peakPnl == arm 이면 arm 을 충족한다.
        assertTrue(ExitGates.isTrailingStopTriggered(2.8, 5.0, 2.1, 2.0, 5.0))
    }

    @Test
    fun `nan inputs never fire`() {
        // avgBuyPrice=0 에서 나온 NaN 은 비교가 모두 false 라 발동하지 않는다.
        assertFalse(ExitGates.isTrailingStopTriggered(Double.NaN, 6.0, 2.3, 2.0, 0.0))
        assertFalse(ExitGates.isTrailingStopTriggered(1.0, Double.NaN, 2.3, 2.0, 5.0))
        assertFalse(ExitGates.isTrailingStopTriggered(1.0, 6.0, Double.NaN, 2.0, 0.0))
    }

    @Test
    fun `effectiveMaxHoldDays coerces non-positive to one`() {
        assertEquals(1, ExitGates.effectiveMaxHoldDays(0))
        assertEquals(1, ExitGates.effectiveMaxHoldDays(-5))
        assertEquals(1, ExitGates.effectiveMaxHoldDays(1))
        assertEquals(3, ExitGates.effectiveMaxHoldDays(3))
    }
}
