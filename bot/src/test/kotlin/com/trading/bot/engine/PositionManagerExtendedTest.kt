package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.*
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PositionManagerExtendedTest {

    private lateinit var upbitClient: UpbitClient
    private val properties = TradingProperties(
        takeProfitPct = 5.0,
        maxLossPct = 3.0,
        trailingStopPct = 2.0,
        maxInvestAmount = 100000.0,
    )
    private lateinit var manager: PositionManager

    @BeforeEach
    fun setup() {
        upbitClient = mockk(relaxed = true)
        manager = PositionManager(upbitClient, properties)
    }

    // --- syncPosition tests ---

    @Test
    fun `syncPosition updates state when holding exists`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.5", avgBuyPrice = "50000000")
        )
        val state = TradingState("KRW-BTC")

        manager.syncPosition("KRW-BTC", state)

        assertTrue(state.position)
        assertEquals(50000000.0, state.avgBuyPrice)
        assertEquals(0.5, state.holdVolume)
    }

    @Test
    fun `syncPosition does not modify state when no holding`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "1000000")
        )
        val state = TradingState("KRW-BTC")

        manager.syncPosition("KRW-BTC", state)

        assertFalse(state.position)
    }

    @Test
    fun `syncPosition handles API error gracefully`() = runTest {
        coEvery { upbitClient.getAccounts() } throws RuntimeException("API error")
        val state = TradingState("KRW-BTC")

        manager.syncPosition("KRW-BTC", state)

        assertFalse(state.position) // unchanged
    }

    // --- buy tests ---

    @Test
    fun `buy returns null when insufficient funds`() = runTest {
        // investRatio 0.1 * 1000 = 100 < MIN_ORDER(5000)
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "1000")
        )
        val state = TradingState("KRW-BTC")

        val result = manager.buy("KRW-BTC", state, 50000000.0, "test_strategy")
        assertNull(result)
        assertFalse(state.position)
        coVerify(exactly = 0) { upbitClient.placeOrder(any()) }
    }

    @Test
    fun `buy reconciles volume and avg price from exchange and sets boughtToday`() = runTest {
        coEvery { upbitClient.getAccounts() } returnsMany listOf(
            listOf(Account(currency = "KRW", balance = "200000")),                          // invest sizing
            listOf(Account(currency = "BTC", balance = "0.00038", avgBuyPrice = "52000000")), // post-fill truth
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "buy-123")
        coEvery { upbitClient.getOrder("buy-123") } returns
            Order(uuid = "buy-123", state = "done", executedVolume = "0.00038")

        val state = TradingState("KRW-BTC")
        val result = manager.buy("KRW-BTC", state, 50000000.0, "volatility_breakout")

        assertNotNull(result)
        assertEquals(TradeSide.BUY, result!!.side)
        assertEquals(52000000.0, result.price)   // 거래소 평단 (currentPrice 아님)
        assertEquals(0.00038, result.volume)     // 거래소 실잔고 (investAmount/price 아님)
        assertEquals("volatility_breakout", result.strategy)
        assertTrue(state.position)
        assertTrue(state.boughtToday)
        assertEquals(0.00038, state.holdVolume)
        assertEquals(52000000.0, state.avgBuyPrice)
    }

    @Test
    fun `buy applies investRatio and caps at maxInvestAmount`() = runTest {
        // 5,000,000 * 0.1 = 500,000 → capped at maxInvestAmount 100,000
        coEvery { upbitClient.getAccounts() } returnsMany listOf(
            listOf(Account(currency = "KRW", balance = "5000000")),
            listOf(Account(currency = "BTC", balance = "0.0019", avgBuyPrice = "52000000")),
        )
        val orderSlot = slot<OrderRequest>()
        coEvery { upbitClient.placeOrder(capture(orderSlot)) } returns Order(uuid = "buy-456")
        coEvery { upbitClient.getOrder("buy-456") } returns
            Order(uuid = "buy-456", state = "done", executedVolume = "0.0019")

        val state = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", state, 52000000.0, "test")

        assertEquals("100000", orderSlot.captured.price) // 상한 적용된 투자금
    }

    @Test
    fun `buy returns null and keeps state when order not filled`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "200000"))
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "buy-x")
        coEvery { upbitClient.getOrder("buy-x") } returns Order(uuid = "buy-x", state = "cancel", executedVolume = "0")

        val state = TradingState("KRW-BTC")
        val result = manager.buy("KRW-BTC", state, 50000000.0, "test")

        assertNull(result)
        assertFalse(state.position)
        assertFalse(state.boughtToday)
    }

    @Test
    fun `buy is suppressed while already holding a position`() = runTest {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001) // 보유 중

        val result = manager.buy("KRW-BTC", state, 51000000.0, "test")

        assertNull(result)
        coVerify(exactly = 0) { upbitClient.placeOrder(any()) }
    }

    @Test
    fun `buy recognizes partial fill when state cancel but executedVolume positive`() = runTest {
        // C1: Upbit 시장가 매수는 소액잔량 환불 시 state=cancel + executed_volume>0 으로 종료(researcher: faq-order).
        // 실제 코인을 받았으므로 매수로 인정해야 phantom holding(손절·익절 영구 미작동)을 막는다.
        coEvery { upbitClient.getAccounts() } returnsMany listOf(
            listOf(Account(currency = "KRW", balance = "200000")),                          // invest sizing
            listOf(Account(currency = "BTC", balance = "0.0003", avgBuyPrice = "52000000")), // 부분체결 실잔고
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "buy-partial")
        coEvery { upbitClient.getOrder("buy-partial") } returns
            Order(uuid = "buy-partial", state = "cancel", executedVolume = "0.0003")

        val state = TradingState("KRW-BTC")
        val result = manager.buy("KRW-BTC", state, 50000000.0, "test")

        assertNotNull(result)
        assertEquals(0.0003, result!!.volume)
        assertEquals(52000000.0, result.price)
        assertTrue(state.position)
        assertTrue(state.boughtToday)
        assertEquals(0.0003, state.holdVolume)
    }

    @Test
    fun `buy recognizes fill when awaitFill times out with executedVolume`() = runTest {
        // 폴링 소진까지 state=wait 이지만 executed_volume>0 → 실제 체결분 존재. 매수 인정(회귀 보호).
        coEvery { upbitClient.getAccounts() } returnsMany listOf(
            listOf(Account(currency = "KRW", balance = "200000")),
            listOf(Account(currency = "BTC", balance = "0.0003", avgBuyPrice = "52000000")),
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "buy-wait")
        coEvery { upbitClient.getOrder("buy-wait") } returns
            Order(uuid = "buy-wait", state = "wait", executedVolume = "0.0003")

        val state = TradingState("KRW-BTC")
        val result = manager.buy("KRW-BTC", state, 50000000.0, "test")

        assertNotNull(result)
        assertTrue(state.position)
        assertTrue(state.boughtToday)
    }

    // --- sell tests ---

    @Test
    fun `sell returns null when no position`() = runTest {
        val state = TradingState("KRW-BTC", position = false)
        val result = manager.sell("KRW-BTC", state, 50000000.0, SellReason.MANUAL)
        assertNull(result)
    }

    @Test
    fun `sell uses real exchange balance and confirms fill`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.001", avgBuyPrice = "50000000")
        )
        val orderSlot = slot<OrderRequest>()
        coEvery { upbitClient.placeOrder(capture(orderSlot)) } returns Order(uuid = "sell-789")
        coEvery { upbitClient.getOrder("sell-789") } returns Order(uuid = "sell-789", state = "done")

        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)

        val result = manager.sell("KRW-BTC", state, 52000000.0, SellReason.TAKE_PROFIT)

        assertNotNull(result)
        assertEquals(TradeSide.SELL, result!!.side)
        assertEquals("TAKE_PROFIT", result.reason)
        assertTrue(result.pnlPercent!! > 0)
        assertEquals(0.001, result.volume)
        assertEquals("0.001", orderSlot.captured.volume) // 거래소 원본 잔고 문자열
        assertFalse(state.position)
    }

    @Test
    fun `sell submits actual balance not the recorded holdVolume`() = runTest {
        // state 는 1.0 보유로 알지만 거래소 실잔고는 0.5
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.5", avgBuyPrice = "100")
        )
        val orderSlot = slot<OrderRequest>()
        coEvery { upbitClient.placeOrder(capture(orderSlot)) } returns Order(uuid = "s2")
        coEvery { upbitClient.getOrder("s2") } returns Order(uuid = "s2", state = "done")

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 100.0, holdVolume = 1.0)
        val result = manager.sell("KRW-BTC", state, 110.0, SellReason.MANUAL)

        assertEquals("0.5", orderSlot.captured.volume)
        assertEquals(0.5, result!!.volume)
    }

    @Test
    fun `sell clears phantom position when exchange balance is zero`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "1000000"))

        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)

        val result = manager.sell("KRW-BTC", state, 52000000.0, SellReason.STOP_LOSS)

        assertNull(result)
        assertFalse(state.position) // phantom 청산
        coVerify(exactly = 0) { upbitClient.placeOrder(any()) }
    }

    @Test
    fun `sell keeps position when fill is not confirmed`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.001"))
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "s3")
        coEvery { upbitClient.getOrder("s3") } returns Order(uuid = "s3", state = "wait") // 끝까지 미체결

        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)

        val result = manager.sell("KRW-BTC", state, 52000000.0, SellReason.MANUAL)

        assertNull(result)
        assertTrue(state.position) // 재시도 위해 유지
    }

    @Test
    fun `sell handles API error gracefully`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.001"))
        coEvery { upbitClient.placeOrder(any()) } throws RuntimeException("Network error")

        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)

        val result = manager.sell("KRW-BTC", state, 52000000.0, SellReason.MANUAL)
        assertNull(result)
        assertTrue(state.position) // state unchanged on failure
    }

    @Test
    fun `sell keeps position when free balance zero but locked remains`() = runTest {
        // M4: balance=0 이지만 locked>0 (매도 주문 진행 중 잔고가 locked 로 이동) → phantom 아님.
        // markSold 로 상태를 지우면 진행 중 매도가 체결돼도 봇이 추적 불가 → 보류(유지)해야 한다.
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0", locked = "0.001", avgBuyPrice = "50000000")
        )

        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)

        val result = manager.sell("KRW-BTC", state, 52000000.0, SellReason.STOP_LOSS)

        assertNull(result)
        assertTrue(state.position) // locked>0 이면 보류, 상태 유지
        coVerify(exactly = 0) { upbitClient.placeOrder(any()) }
    }

    // --- checkTakeProfit tests ---

    @Test
    fun `checkTakeProfit returns false when no position`() {
        val state = TradingState("KRW-BTC", position = false)
        assertFalse(manager.checkTakeProfit(state, 50000000.0))
    }

    @Test
    fun `checkTakeProfit returns true when PnL exceeds threshold`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        // 5% profit: 50M -> 52.5M
        assertTrue(manager.checkTakeProfit(state, 52500000.0))
    }

    @Test
    fun `checkTakeProfit returns false when PnL below threshold`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        // 2% profit: not enough (threshold is 5%)
        assertFalse(manager.checkTakeProfit(state, 51000000.0))
    }

    // --- checkStopLoss tests ---

    @Test
    fun `checkStopLoss returns true when loss exceeds threshold`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        // -3% loss: 50M -> 48.5M
        assertTrue(manager.checkStopLoss(state, 48500000.0))
    }

    @Test
    fun `checkStopLoss returns false when loss is small`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        // -1% loss
        assertFalse(manager.checkStopLoss(state, 49500000.0))
    }

    // --- checkTrailingStop tests ---

    @Test
    fun `checkTrailingStop returns false when not in profit`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        state.updatePeakPrice(51000000.0)
        // Price dropped but still below buy price
        assertFalse(manager.checkTrailingStop(state, 49000000.0))
    }

    @Test
    fun `checkTrailingStop returns true when drop from peak exceeds threshold`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        state.updatePeakPrice(55000000.0) // peak at 55M (+10%)
        // Drop from peak: (55M - 53.8M) / 55M = 2.18% > 2% threshold
        assertTrue(manager.checkTrailingStop(state, 53800000.0))
    }

    @Test
    fun `checkTrailingStop returns false when drop is small`() {
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        state.updatePeakPrice(55000000.0) // peak at 55M
        // Drop from peak: (55M - 54.5M) / 55M = 0.9% < 2% threshold
        assertFalse(manager.checkTrailingStop(state, 54500000.0))
    }

    // --- H8: pending-reconcile tests ---

    @Test
    fun `buy keeps pending when post-order processing throws`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "200000"))
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "buy-pending")
        coEvery { upbitClient.getOrder("buy-pending") } throws RuntimeException("network")

        val state = TradingState("KRW-BTC")
        val result = manager.buy("KRW-BTC", state, 50000000.0, "test")

        assertNull(result)
        assertEquals("buy-pending", state.pendingBuyUuid) // 주문 uuid 보존 → 다음 tick reconcile
        assertEquals("test", state.pendingBuyStrategy)
        assertFalse(state.position)
        assertFalse(state.boughtToday)
    }

    @Test
    fun `buy is blocked while pending order exists`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "200000")) // 잔고 충분
        val state = TradingState("KRW-BTC", pendingBuyUuid = "prev-order")

        val result = manager.buy("KRW-BTC", state, 50000000.0, "test")

        assertNull(result)
        coVerify(exactly = 0) { upbitClient.placeOrder(any()) } // 미해소 주문 있으면 신규매수 금지
    }

    @Test
    fun `reconcile completes buy when executed positive even while state wait`() = runTest {
        // 강한우려1: executed>0 을 wait 보다 먼저 판정 (부분체결 방치 금지)
        coEvery { upbitClient.getOrder("p1") } returns Order(uuid = "p1", state = "wait", executedVolume = "0.0003")
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.0003", avgBuyPrice = "52000000")
        )
        val state = TradingState("KRW-BTC", pendingBuyUuid = "p1", pendingBuyStrategy = "vb")

        val result = manager.reconcilePendingBuy("KRW-BTC", state, 50000000.0)

        assertNotNull(result)
        assertTrue(state.position)
        assertNull(state.pendingBuyUuid) // 체결 확정 → 해소
        assertEquals(0.0003, state.holdVolume)
        assertEquals("vb", state.entryStrategy)
    }

    @Test
    fun `reconcile clears pending when order cancelled unfilled`() = runTest {
        coEvery { upbitClient.getOrder("p2") } returns Order(uuid = "p2", state = "cancel", executedVolume = "0")
        val state = TradingState("KRW-BTC", pendingBuyUuid = "p2", pendingBuyStrategy = "vb")

        val result = manager.reconcilePendingBuy("KRW-BTC", state, 50000000.0)

        assertNull(result)
        assertNull(state.pendingBuyUuid) // 주문 무산 → 해소
        assertFalse(state.position)
    }

    @Test
    fun `reconcile keeps pending while order still wait and unfilled`() = runTest {
        coEvery { upbitClient.getOrder("p3") } returns Order(uuid = "p3", state = "wait", executedVolume = "0")
        val state = TradingState("KRW-BTC", pendingBuyUuid = "p3", pendingBuyStrategy = "vb")

        val result = manager.reconcilePendingBuy("KRW-BTC", state, 50000000.0)

        assertNull(result)
        assertEquals("p3", state.pendingBuyUuid) // 진행중 → 유지(다음 tick)
        assertFalse(state.position)
    }

    @Test
    fun `reconcile recovers position from balance when getOrder fails`() = runTest {
        // 강한우려3: getOrder 장애 시 getAccounts 잔고로 복원(무방비보유 방지)
        coEvery { upbitClient.getOrder("p4") } throws RuntimeException("order api down")
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.0003", avgBuyPrice = "52000000")
        )
        val state = TradingState("KRW-BTC", pendingBuyUuid = "p4", pendingBuyStrategy = "vb")

        val result = manager.reconcilePendingBuy("KRW-BTC", state, 50000000.0)

        assertNotNull(result)
        assertTrue(state.position)
        assertNull(state.pendingBuyUuid)
        assertEquals(0.0003, state.holdVolume)
    }

    @Test
    fun `reconcile keeps pending when getOrder fails and no balance`() = runTest {
        coEvery { upbitClient.getOrder("p5") } throws RuntimeException("order api down")
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "100000"))
        val state = TradingState("KRW-BTC", pendingBuyUuid = "p5", pendingBuyStrategy = "vb")

        val result = manager.reconcilePendingBuy("KRW-BTC", state, 50000000.0)

        assertNull(result)
        assertEquals("p5", state.pendingBuyUuid) // 복원 실패 → 유지(다음 tick 재시도)
        assertFalse(state.position)
    }

    @Test
    fun `resetDaily keeps pendingBuyUuid`() {
        val state = TradingState("KRW-BTC", boughtToday = true, pendingBuyUuid = "x")
        state.resetDaily()
        assertFalse(state.boughtToday)
        assertEquals("x", state.pendingBuyUuid) // H8: 끄면 재발 → 불변
    }

    @Test
    fun `markSold clears pendingBuyUuid`() {
        val state = TradingState("KRW-BTC", pendingBuyUuid = "x")
        state.markSold()
        assertNull(state.pendingBuyUuid)
    }

    // --- pnl net 기록 (이슈 #27 — 기록 pnlPercent 는 왕복수수료 차감, 백테스트 feeRate×2 와 통일) ---

    @Test
    fun `sell records net pnl after round-trip fee`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.001", avgBuyPrice = "50000000")
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "sell-net")
        coEvery { upbitClient.getOrder("sell-net") } returns Order(uuid = "sell-net", state = "done")

        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)

        // gross +4% (50M → 52M), net = 4.0 − roundTripFeeRate(0.001)×100 = 3.9
        val result = manager.sell("KRW-BTC", state, 52000000.0, SellReason.TAKE_PROFIT)

        assertEquals(3.9, result!!.pnlPercent!!, 1e-9)
    }

    @Test
    fun `exit gates stay gross while record is net`() = runTest {
        // 이 PR 의 핵심 불변식: 청산 게이트는 gross(행동 불변), 기록만 net.
        val mgr = PositionManager(upbitClient, TradingProperties()) // takeProfitPct 2.0
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.001", avgBuyPrice = "100000")
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "sell-edge")
        coEvery { upbitClient.getOrder("sell-edge") } returns Order(uuid = "sell-edge", state = "done")

        val state = TradingState("KRW-BTC")
        state.markBought(100000.0, 0.001)

        assertTrue(mgr.checkTakeProfit(state, 102050.0)) // gross 2.05% ≥ 2.0 — 게이트는 수수료 미차감
        val result = mgr.sell("KRW-BTC", state, 102050.0, SellReason.TAKE_PROFIT)
        assertEquals(1.95, result!!.pnlPercent!!, 1e-9) // 기록은 net
    }
}
