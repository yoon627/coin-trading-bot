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
    fun `ledger without balance is reset and the flat peak re-anchors to the current price`() {
        // 옛 고점(150)을 남기면 현재가 95 가 곧바로 눌림 진입 조건을 만족해 수동 청산을 같은 tick 에 되돌린다.
        val state = TradingState("KRW-BTC", rungsFilled = 3, lastActionPrice = 90.0, flatPeak = 150.0)

        val note = LadderStateMapper.reconcile(state, params, price = 95.0)

        assertNotNull(note)
        assertEquals(0, state.rungsFilled)
        assertEquals(0.0, state.lastActionPrice)
        assertEquals(95.0, state.flatPeak)
    }

    @Test
    fun `rungs above what the remaining cost supports are lowered`() {
        // 부분 매도가 반복돼 원가 30,000 만 남았는데 장부는 5단 — 단당 매도 대금이 최소주문 아래로 내려간다.
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 100.0, holdVolume = 300.0, rungsFilled = 5, lastActionPrice = 100.0)

        val note = LadderStateMapper.reconcile(state, params, price = 100.0)

        assertNotNull(note)
        assertEquals(2, state.rungsFilled)
    }

    @Test
    fun `cost tolerance agrees with the ninety percent sell rule`() {
        // 4단에서 한 단이 90% 체결돼 rung 을 소모(→3): 남은 원가 3.1단은 3 으로 읽혀야 한다(다시 4 로 살아나면 안 된다).
        val consumed = TradingState("KRW-BTC", position = true, avgBuyPrice = 20_000.0, holdVolume = 3.1, rungsFilled = 3, lastActionPrice = 20_000.0)
        assertNull(LadderStateMapper.reconcile(consumed, params, price = 20_000.0))
        assertEquals(3, consumed.rungsFilled)

        // 89% 체결이라 rung 을 남겼다(4): 남은 원가 3.11단은 4 로 읽혀야 한다.
        val kept = TradingState("KRW-BTC", position = true, avgBuyPrice = 20_000.0, holdVolume = 3.11, rungsFilled = 4, lastActionPrice = 20_000.0)
        assertNull(LadderStateMapper.reconcile(kept, params, price = 20_000.0))
        assertEquals(4, kept.rungsFilled)
    }

    @Test
    fun `a normal partial sell does not trip the cost cap`() {
        // 5단(원가 100,000) 에서 1/5 을 팔면 원가 80,000·4단 — 부동소수 오차로 5단 요구가 나오면 안 된다.
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 20_000.0, holdVolume = 4.0, rungsFilled = 4, lastActionPrice = 20_000.0)

        assertNull(LadderStateMapper.reconcile(state, params, price = 20_000.0))
        assertEquals(4, state.rungsFilled)
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
        // 원가 45,000 = 3단(20,000 단위 올림). 기준가는 건드리지 않는다.
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 90.0, holdVolume = 500.0, rungsFilled = 3, lastActionPrice = 85.0)

        assertNull(LadderStateMapper.reconcile(state, params, price = 88.0))
        assertEquals(3, state.rungsFilled)
        assertEquals(85.0, state.lastActionPrice)
    }

    @Test
    fun `a manual add-on buy raises the rung count so the next sell is not a final sell`() {
        // 1단(20,000) 위에 40,000 을 수동 매수 — 1단으로 두면 다음 상승에 60,000 전량이 isFinal 로 나간다.
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 100.0, holdVolume = 600.0, rungsFilled = 1, lastActionPrice = 100.0)

        val note = LadderStateMapper.reconcile(state, params, price = 100.0)

        assertNotNull(note)
        assertEquals(3, state.rungsFilled)
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
