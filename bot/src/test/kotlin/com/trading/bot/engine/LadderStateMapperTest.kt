package com.trading.bot.engine

import com.trading.bot.domain.TradingState
import com.trading.common.strategy.LadderParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LadderStateMapperTest {

    private val params = LadderParams(budgetKrw = 100_000.0, maxRungs = 5, stepDownPct = 3.0, stepUpPct = 3.0)

    @Test
    fun `existing swing holding is folded into the ladder by measured cost`() {
        // 45,000 원어치 보유(2.25단) → 3단, 기준가는 평단.
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 90.0, holdVolume = 500.0)

        val note = LadderStateMapper.reconcile(state, params, price = 88.0)

        assertNotNull(note)
        assertEquals(3, state.rungsFilled)
        assertEquals(90.0, state.lastActionPrice)
    }

    @Test
    fun `folding never exceeds maxRungs`() {
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 100.0, holdVolume = 5_000.0) // 500,000 원어치

        LadderStateMapper.reconcile(state, params, price = 100.0)

        assertEquals(5, state.rungsFilled)
    }

    @Test
    fun `ledger without balance is reset`() {
        val state = TradingState("KRW-BTC", rungsFilled = 3, lastActionPrice = 90.0)

        val note = LadderStateMapper.reconcile(state, params, price = 95.0)

        assertNotNull(note)
        assertEquals(0, state.rungsFilled)
        assertEquals(0.0, state.lastActionPrice)
    }

    @Test
    fun `flat peak is initialized from the current price only when unset`() {
        val fresh = TradingState("KRW-BTC")
        assertNull(LadderStateMapper.reconcile(fresh, params, price = 120.0))
        assertEquals(120.0, fresh.flatPeak)

        val restored = TradingState("KRW-BTC", flatPeak = 150.0)
        assertNull(LadderStateMapper.reconcile(restored, params, price = 120.0))
        assertEquals(150.0, restored.flatPeak)
    }

    @Test
    fun `consistent state is left alone`() {
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 90.0, holdVolume = 500.0, rungsFilled = 2, lastActionPrice = 85.0)

        assertNull(LadderStateMapper.reconcile(state, params, price = 88.0))
        assertEquals(2, state.rungsFilled)
        assertEquals(85.0, state.lastActionPrice)
    }

    @Test
    fun `input mirrors the state`() {
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 90.0, holdVolume = 500.0, rungsFilled = 2, lastActionPrice = 85.0, flatPeak = 0.0)
        val input = LadderStateMapper.toInput(state, price = 80.0)
        assertEquals(2, input.rungsFilled)
        assertEquals(85.0, input.lastActionPrice)
        assertEquals(90.0, input.avgBuyPrice)
        assertEquals(500.0, input.holdVolume)
        assertEquals(80.0, input.price)
    }
}
