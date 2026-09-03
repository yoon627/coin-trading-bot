package com.trading.bot.strategy

import com.trading.common.strategy.AccumulateLadder
import com.trading.common.strategy.LadderAction
import com.trading.common.strategy.LadderInput
import com.trading.common.strategy.LadderParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccumulateLadderTest {

    private val params = LadderParams(budgetKrw = 100_000.0, maxRungs = 5, stepDownPct = 3.0, stepUpPct = 3.0)

    private fun flat(price: Double, flatPeak: Double) =
        LadderInput(rungsFilled = 0, lastActionPrice = 0.0, flatPeak = flatPeak, avgBuyPrice = 0.0, holdVolume = 0.0, price = price)

    private fun holding(
        price: Double,
        rungs: Int,
        lastAction: Double,
        avg: Double,
        hold: Double,
    ) = LadderInput(rungsFilled = rungs, lastActionPrice = lastAction, flatPeak = 0.0, avgBuyPrice = avg, holdVolume = hold, price = price)

    @Test
    fun `rung amount is budget divided by max rungs`() {
        assertEquals(20_000.0, params.rungAmountKrw)
    }

    @Test
    fun `no entry until a flat peak has been observed`() {
        assertEquals(LadderAction.Hold, AccumulateLadder.decide(flat(price = 100.0, flatPeak = 0.0), params))
    }

    @Test
    fun `first rung enters on a pullback from the flat peak`() {
        assertEquals(LadderAction.Buy(20_000.0, 97.0), AccumulateLadder.decide(flat(price = 97.0, flatPeak = 100.0), params))
        assertEquals(LadderAction.Hold, AccumulateLadder.decide(flat(price = 97.5, flatPeak = 100.0), params))
    }

    @Test
    fun `next rung buys when price falls stepDown below the last action price`() {
        val state = holding(price = 97.0, rungs = 1, lastAction = 100.0, avg = 100.0, hold = 200.0)
        assertEquals(LadderAction.Buy(20_000.0, 97.0), AccumulateLadder.decide(state, params))
        assertEquals(LadderAction.Hold, AccumulateLadder.decide(state.copy(price = 97.5), params))
    }

    @Test
    fun `no rung beyond maxRungs even on a deeper drop`() {
        val state = holding(price = 50.0, rungs = 5, lastAction = 80.0, avg = 90.0, hold = 1_000.0)
        assertEquals(LadderAction.Hold, AccumulateLadder.decide(state, params))
    }

    @Test
    fun `budget gate uses measured cost, not rung count`() {
        // 2단만 채웠지만 실측 원가(90 × 1000 = 90,000)가 예산에 육박해 다음 단 20,000 이 들어갈 자리가 없다.
        val state = holding(price = 80.0, rungs = 2, lastAction = 90.0, avg = 90.0, hold = 1_000.0)
        assertEquals(LadderAction.Hold, AccumulateLadder.decide(state, params))
        // 원가 79,000 이면 20,000 이 정확히 들어간다(경계 포함).
        assertEquals(LadderAction.Buy(20_000.0, 80.0), AccumulateLadder.decide(state.copy(holdVolume = 79_000.0 / 90.0), params))
    }

    @Test
    fun `sells one rung when price rises stepUp above the average buy price`() {
        val state = holding(price = 103.0, rungs = 4, lastAction = 90.0, avg = 100.0, hold = 800.0)
        assertEquals(LadderAction.Sell(200.0, 103.0, isFinal = false), AccumulateLadder.decide(state, params))
        assertEquals(LadderAction.Hold, AccumulateLadder.decide(state.copy(price = 102.9), params))
    }

    @Test
    fun `after a sell the next sell is referenced to the last action price`() {
        // 직전 매도가 110 > 평단 100 → 다음 매도는 113.3 부터.
        val state = holding(price = 112.0, rungs = 3, lastAction = 110.0, avg = 100.0, hold = 600.0)
        assertEquals(LadderAction.Hold, AccumulateLadder.decide(state, params))
        assertEquals(LadderAction.Sell(200.0, 113.3, isFinal = false), AccumulateLadder.decide(state.copy(price = 113.3), params))
    }

    @Test
    fun `last rung sells the whole position`() {
        val state = holding(price = 103.0, rungs = 1, lastAction = 100.0, avg = 100.0, hold = 200.0)
        assertEquals(LadderAction.Sell(200.0, 103.0, isFinal = true), AccumulateLadder.decide(state, params))
    }

    @Test
    fun `holds instead of placing a sell below the exchange minimum order`() {
        // 4단 중 1단 = 10 개 × 103 = 1,030원 < 5,000원.
        val state = holding(price = 103.0, rungs = 4, lastAction = 90.0, avg = 100.0, hold = 40.0)
        assertEquals(LadderAction.Hold, AccumulateLadder.decide(state, params))
    }

    @Test
    fun `inconsistent ledger and balance never trades`() {
        assertEquals(LadderAction.Hold, AccumulateLadder.decide(holding(price = 50.0, rungs = 3, lastAction = 100.0, avg = 100.0, hold = 0.0), params))
        assertEquals(LadderAction.Hold, AccumulateLadder.decide(holding(price = 150.0, rungs = 0, lastAction = 0.0, avg = 100.0, hold = 500.0), params))
    }

    @Test
    fun `non-positive price never trades`() {
        assertEquals(LadderAction.Hold, AccumulateLadder.decide(flat(price = 0.0, flatPeak = 100.0), params))
        assertEquals(LadderAction.Hold, AccumulateLadder.decide(holding(price = -1.0, rungs = 2, lastAction = 100.0, avg = 100.0, hold = 400.0), params))
    }

    @Test
    fun `params reject configurations that cannot place an order`() {
        assertTrue(runCatching { LadderParams(budgetKrw = 10_000.0, maxRungs = 3, stepDownPct = 3.0, stepUpPct = 3.0) }.isFailure)
        assertTrue(runCatching { LadderParams(budgetKrw = 100_000.0, maxRungs = 0, stepDownPct = 3.0, stepUpPct = 3.0) }.isFailure)
        assertTrue(runCatching { LadderParams(budgetKrw = 100_000.0, maxRungs = 5, stepDownPct = 0.0, stepUpPct = 3.0) }.isFailure)
        assertTrue(runCatching { LadderParams(budgetKrw = 100_000.0, maxRungs = 5, stepDownPct = 3.0, stepUpPct = -1.0) }.isFailure)
        // 100% 눌림은 기준가 0 — 영영 진입하지 않는 설정. Infinity 는 무한 주문액·트리거.
        assertTrue(runCatching { LadderParams(budgetKrw = 100_000.0, maxRungs = 5, stepDownPct = 100.0, stepUpPct = 3.0) }.isFailure)
        assertTrue(runCatching { LadderParams(budgetKrw = Double.POSITIVE_INFINITY, maxRungs = 5, stepDownPct = 3.0, stepUpPct = 3.0) }.isFailure)
        assertTrue(runCatching { LadderParams(budgetKrw = 100_000.0, maxRungs = 5, stepDownPct = 3.0, stepUpPct = Double.POSITIVE_INFINITY) }.isFailure)
    }
}
