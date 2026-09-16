package com.trading.bot.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [SharedBalanceSim] — 한 계좌의 현금 장부 위에서 "어느 진입이 실제로 체결됐을까" 를 다시 센다(#180).
 * 계기([LiveSemanticsArm]) 결과는 그대로 두고, 동시 진입이 잔고에 막히는 라이브 제약만 후처리로 얹는다.
 */
class SharedBalanceSimTest {

    private fun trade(
        market: String, entryDate: String, entryBar: String, exitDate: String, exitBar: String, pnlPct: Double,
    ) = LiveSemanticsArm.Trade(
        market = market, entryDate = entryDate, exitDate = exitDate, entryPrice = 100.0, exitPrice = 100.0 * (1 + pnlPct / 100),
        netPnlPct = pnlPct, reason = "TEST", entryBarUtc = entryBar, exitBarUtc = exitBar,
    )

    private val order = listOf("KRW-BTC", "KRW-ETH", "KRW-XRP")

    @Test
    fun `with enough cash every entry fills and the notional follows the live sizing rule`() {
        val trades = listOf(
            trade("KRW-BTC", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T04:00:00", +2.0),
            trade("KRW-ETH", "2026-01-02", "2026-01-02T00:00:00", "2026-01-02", "2026-01-02T08:00:00", -1.0),
        )
        val r = SharedBalanceSim.run(trades, order, SharedBalanceSim.Sizing.live(initialKrw = 1_000_000.0, investRatio = 0.1, maxInvestAmount = 100_000.0))

        assertEquals(2, r.fills.size)
        assertTrue(r.skipped.isEmpty())
        // 첫 진입: min(1,000,000 × 0.1, 100,000) = 100,000. 청산 후 현금 1,002,000 → 두 번째 min(100,200, 100,000) = 100,000.
        assertEquals(100_000.0, r.fills[0].notionalKrw, 1e-9)
        assertEquals(2_000.0, r.fills[0].pnlKrw, 1e-9)
        assertEquals(100_000.0, r.fills[1].notionalKrw, 1e-9)
        assertEquals(-1_000.0, r.fills[1].pnlKrw, 1e-9)
        assertEquals(1_001_000.0, r.finalCashKrw, 1e-9)
        assertEquals(1_000.0, r.pnlKrw, 1e-9)
    }

    @Test
    fun `simultaneous entries beyond the slot count are skipped in market order`() {
        val trades = listOf(
            trade("KRW-XRP", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T08:00:00", +5.0),
            trade("KRW-BTC", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T08:00:00", +1.0),
            trade("KRW-ETH", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T08:00:00", +3.0),
        )
        val r = SharedBalanceSim.run(trades, order, SharedBalanceSim.Sizing.slots(slots = 1, notionalKrw = 100_000.0))

        // 같은 봉이면 activeTickers 순서(BTC → ETH → XRP)로 순회한다 — BTC 만 체결, 나머지는 잔고 부족으로 skip.
        assertEquals(listOf("KRW-BTC"), r.fills.map { it.trade.market })
        assertEquals(listOf("KRW-ETH", "KRW-XRP"), r.skipped.map { it.market })
        assertEquals(1_000.0, r.pnlKrw, 1e-9)
        // 독립(10만원 고정) 기준은 셋을 다 세므로 9,000원 — 그 차가 편향의 크기다.
        assertEquals(9_000.0, SharedBalanceSim.independentPnlKrw(trades, 100_000.0), 1e-9)
    }

    @Test
    fun `an exit at the same bar as another market's entry frees cash first`() {
        val trades = listOf(
            trade("KRW-BTC", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T08:00:00", +1.0),
            // ETH 는 BTC 가 청산되는 그 봉에서 진입한다 — 라이브는 같은 tick 에서 청산을 먼저 평가하므로 현금이 돌아와 있다.
            trade("KRW-ETH", "2026-01-01", "2026-01-01T08:00:00", "2026-01-01", "2026-01-01T12:00:00", +1.0),
        )
        val r = SharedBalanceSim.run(trades, order, SharedBalanceSim.Sizing.slots(slots = 1, notionalKrw = 100_000.0))

        assertEquals(listOf("KRW-BTC", "KRW-ETH"), r.fills.map { it.trade.market })
        assertTrue(r.skipped.isEmpty())
    }

    @Test
    fun `entries below the minimum order amount are skipped under the live rule`() {
        val trades = listOf(
            trade("KRW-BTC", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T08:00:00", 0.0),
        )
        // 40,000 × 0.1 = 4,000 < 5,000(MIN_ORDER_AMOUNT_KRW) → 라이브는 주문을 내지 않는다.
        val r = SharedBalanceSim.run(trades, order, SharedBalanceSim.Sizing.live(initialKrw = 40_000.0, investRatio = 0.1, maxInvestAmount = 100_000.0))

        assertTrue(r.fills.isEmpty())
        assertEquals(1, r.skipped.size)
        assertEquals(40_000.0, r.finalCashKrw, 1e-9)
    }

    @Test
    fun `trades without bar timestamps are rejected instead of being ordered by guess`() {
        val legacy = LiveSemanticsArm.Trade(
            market = "KRW-BTC", entryDate = "2026-01-01", exitDate = "2026-01-01",
            entryPrice = 100.0, exitPrice = 101.0, netPnlPct = 1.0, reason = "TEST",
        )
        assertThrows<IllegalArgumentException> {
            SharedBalanceSim.run(listOf(legacy), order, SharedBalanceSim.Sizing.slots(slots = 8, notionalKrw = 100_000.0))
        }
    }

    @Test
    fun `slots equal to the market count reproduce the independent fixed-notional result`() {
        val trades = listOf(
            trade("KRW-BTC", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T08:00:00", +1.0),
            trade("KRW-ETH", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T08:00:00", -2.0),
            trade("KRW-XRP", "2026-01-01", "2026-01-01T04:00:00", "2026-01-02", "2026-01-02T00:00:00", +4.0),
            trade("KRW-BTC", "2026-01-02", "2026-01-02T00:00:00", "2026-01-02", "2026-01-02T04:00:00", +0.5),
        )
        val r = SharedBalanceSim.run(trades, order, SharedBalanceSim.Sizing.slots(slots = order.size, notionalKrw = 100_000.0))

        assertTrue(r.skipped.isEmpty())
        assertEquals(SharedBalanceSim.independentPnlKrw(trades, 100_000.0), r.pnlKrw, 1e-9)
    }

    @Test
    fun `a trade that exits on its entry bar is closed after its own entry`() {
        // 진입 봉 손절(exitOnEntryBar) — entryBarUtc == exitBarUtc. "청산 먼저" 규칙을 그대로 적용하면 열리지도 않은 포지션을 닫는다.
        val trades = listOf(
            trade("KRW-BTC", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T00:00:00", -5.0),
            // 같은 봉에 ETH 도 진입 — 슬롯 1 이면 BTC 의 같은 봉 청산은 ETH 진입에 현금을 돌려주지 않는다(봉 안 순서를 모르므로 보수적).
            trade("KRW-ETH", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T04:00:00", +2.0),
        )
        val r = SharedBalanceSim.run(trades, order, SharedBalanceSim.Sizing.slots(slots = 1, notionalKrw = 100_000.0))

        assertEquals(listOf("KRW-BTC"), r.fills.map { it.trade.market })
        assertEquals(-5_000.0, r.fills[0].pnlKrw, 1e-9)
        assertEquals(listOf("KRW-ETH"), r.skipped.map { it.market })
        assertEquals(100_000.0 - 5_000.0, r.finalCashKrw, 1e-9)
    }

    @Test
    fun `a skipped entry does not disable that market's later trades`() {
        val trades = listOf(
            trade("KRW-BTC", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T08:00:00", +1.0),
            trade("KRW-ETH", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T08:00:00", +1.0), // skip (슬롯 1)
            trade("KRW-ETH", "2026-01-02", "2026-01-02T00:00:00", "2026-01-02", "2026-01-02T08:00:00", +3.0), // 다음 날은 체결
        )
        val r = SharedBalanceSim.run(trades, order, SharedBalanceSim.Sizing.slots(slots = 1, notionalKrw = 100_000.0))

        assertEquals(listOf("KRW-BTC" to "2026-01-01", "KRW-ETH" to "2026-01-02"), r.fills.map { it.trade.market to it.trade.entryDate })
        assertEquals(1, r.skipped.size)
        // skip 된 거래의 손익은 장부에 들어가지 않는다.
        assertEquals(4_000.0, r.pnlKrw, 1e-9)
    }

    @Test
    fun `END trades closing on different last bars are settled in time order`() {
        val trades = listOf(
            trade("KRW-BTC", "2026-01-01", "2026-01-01T00:00:00", "2026-01-03", "2026-01-03T20:00:00", +1.0),
            trade("KRW-ETH", "2026-01-02", "2026-01-02T00:00:00", "2026-01-03", "2026-01-03T16:00:00", +2.0),
            // XRP 는 ETH 의 END 청산 뒤 봉에 진입 — 슬롯 2 라 BTC·ETH 가 차 있다가 ETH 가 빠진 뒤여야 들어간다.
            trade("KRW-XRP", "2026-01-03", "2026-01-03T20:00:00", "2026-01-03", "2026-01-03T20:00:00", +0.5),
        )
        val r = SharedBalanceSim.run(trades, order, SharedBalanceSim.Sizing.slots(slots = 2, notionalKrw = 100_000.0))

        // fills 는 청산(정산) 순서다 — ETH 16:00 → BTC 20:00 → XRP 20:00(진입 봉 자기 청산은 진입 뒤).
        assertEquals(listOf("KRW-ETH", "KRW-BTC", "KRW-XRP"), r.fills.map { it.trade.market })
        assertTrue(r.skipped.isEmpty())
        assertEquals(3_500.0, r.pnlKrw, 1e-9)
    }

    @Test
    fun `independent accounts run each market on its own ledger with the same sizing`() {
        val trades = listOf(
            trade("KRW-BTC", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T08:00:00", +10.0),
            trade("KRW-ETH", "2026-01-01", "2026-01-01T00:00:00", "2026-01-01", "2026-01-01T08:00:00", +10.0),
        )
        val sizing = SharedBalanceSim.Sizing.live(initialKrw = 1_000_000.0, investRatio = 0.1, maxInvestAmount = 100_000.0)
        val shared = SharedBalanceSim.run(trades, order, sizing)
        val independent = SharedBalanceSim.runIndependentAccounts(trades, order, sizing)

        // 공유: 100,000 + 90,000(현금 900,000 × 0.1) → 19,000. 독립 계좌: 100,000 씩 → 20,000. 그 차가 동시성(taper) 효과.
        assertEquals(19_000.0, shared.pnlKrw, 1e-9)
        assertEquals(20_000.0, independent.pnlKrw, 1e-9)
    }
}
