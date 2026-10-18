package com.trading.bot.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TradePnlTest {

    private val roundTrip = 0.001

    @Test
    fun `netPercent subtracts the round-trip fee from the gross return`() {
        assertEquals(3.9, TradePnl.netPercent(52_000_000.0, 50_000_000.0, roundTrip)!!, 1e-9)
    }

    @Test
    fun `netPercent keeps losses negative`() {
        assertEquals(-4.1, TradePnl.netPercent(48_000_000.0, 50_000_000.0, roundTrip)!!, 1e-9)
    }

    @Test
    fun `netPercent gives up instead of reporting a fee-sized fake loss`() {
        // 평단을 모를 때(외부 입금분 등) 0 을 돌려주면 −0.1% 손실 거래가 기록에 남는다.
        assertNull(TradePnl.netPercent(52_000_000.0, 0.0, roundTrip))
        // 현재가가 0 이면 −100% 가 나온다. 시세 조회 실패를 전량 손실로 기록하지 않는다.
        assertNull(TradePnl.netPercent(0.0, 50_000_000.0, roundTrip))
    }

    @Test
    fun `amount multiplies the net rate by the principal, not the proceeds`() {
        // 원금 50M × 0.001 = 5만 원의 3.9%.
        assertEquals(1950.0, TradePnl.amount(3.9, 50_000_000.0, 0.001)!!, 1e-6)
    }

    @Test
    fun `amount scales with the sold quantity so partial exits stay additive`() {
        val whole = TradePnl.amount(3.9, 50_000_000.0, 0.001)!!
        val half = TradePnl.amount(3.9, 50_000_000.0, 0.0005)!!
        assertEquals(whole, half * 2, 1e-6)
    }

    @Test
    fun `amount stays null when the rate or basis is unknown`() {
        assertNull(TradePnl.amount(null, 50_000_000.0, 0.001))
        assertNull(TradePnl.amount(3.9, 0.0, 0.001))
        assertNull(TradePnl.amount(3.9, 50_000_000.0, 0.0))
    }

    @Test
    fun `estimatedFee is one leg, so a round trip is the sum of both rows`() {
        assertEquals(50.0, TradePnl.estimatedFee(100_000.0, roundTrip), 1e-9)
        val buy = TradePnl.estimatedFee(100_000.0, roundTrip)
        val sell = TradePnl.estimatedFee(100_000.0, roundTrip)
        assertEquals(100_000.0 * roundTrip, buy + sell, 1e-9)
    }
}
