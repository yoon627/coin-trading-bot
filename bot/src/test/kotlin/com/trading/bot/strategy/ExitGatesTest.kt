package com.trading.bot.strategy

import com.trading.common.strategy.ExitGates
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
    fun `peak below arm blocks firing`() {
        // arm(5) > trail(2): 현행(arm=0)이면 발동이었을 입력 — arm 게이트가 막는다.
        assertFalse(ExitGates.isTrailingStopTriggered(0.7, 3.0, 2.2, 2.0, 5.0))
    }

    @Test
    fun `peak at or above arm allows firing`() {
        assertTrue(ExitGates.isTrailingStopTriggered(3.6, 6.0, 2.3, 2.0, 5.0))
        // 경계: peakPnl == arm 정확히 도달도 arm 충족(>=)
        assertTrue(ExitGates.isTrailingStopTriggered(2.8, 5.0, 2.1, 2.0, 5.0))
    }

    @Test
    fun `nan inputs never fire`() {
        // avgBuyPrice=0 유래 비정상 입력 — IEEE NaN 비교는 false 이므로 발동하지 않아야 한다.
        assertFalse(ExitGates.isTrailingStopTriggered(Double.NaN, 6.0, 2.3, 2.0, 0.0))
        assertFalse(ExitGates.isTrailingStopTriggered(1.0, Double.NaN, 2.3, 2.0, 5.0))
        assertFalse(ExitGates.isTrailingStopTriggered(1.0, 6.0, Double.NaN, 2.0, 0.0))
    }
}
