package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.*
import com.trading.common.config.TradingProperties
import java.time.LocalDate
import java.time.LocalDateTime
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
        manager = PositionManager(upbitClient, properties, mockk(relaxed = true), 1L)
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

    @Test
    fun `syncPosition marks unsynced on API error`() = runTest {
        coEvery { upbitClient.getAccounts() } throws RuntimeException("API error")
        val state = TradingState("KRW-BTC")

        manager.syncPosition("KRW-BTC", state)

        assertTrue(state.unsynced) // 동기화 실패 → 매수 차단 플래그
    }

    @Test
    fun `syncPosition clears unsynced on success with holding`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.5", avgBuyPrice = "50000000")
        )
        val state = TradingState("KRW-BTC", unsynced = true)

        manager.syncPosition("KRW-BTC", state)

        assertFalse(state.unsynced) // 성공 → 해소
        assertTrue(state.position)
    }

    @Test
    fun `syncPosition clears unsynced on success with no holding`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "1000000"))
        val state = TradingState("KRW-BTC", unsynced = true)

        manager.syncPosition("KRW-BTC", state)

        assertFalse(state.unsynced) // 잔고 없어도 조회 성공이면 해소
    }

    @Test
    fun `buy is blocked while position unsynced`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "200000"))
        val state = TradingState("KRW-BTC", unsynced = true)

        val result = manager.buy("KRW-BTC", state, 50000000.0, "test")

        assertNull(result)
        coVerify(exactly = 0) { upbitClient.placeOrder(any()) } // 미동기화 시 신규매수 금지(이중포지션 방지)
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

    // --- trailingArmPct (#27 trailing dead 해소 경로) ---

    private fun managerWithArm(armPct: Double) = PositionManager(
        upbitClient,
        TradingProperties(
            takeProfitPct = 5.0,
            maxLossPct = 3.0,
            trailingStopPct = 2.0,
            trailingArmPct = armPct,
            maxInvestAmount = 100000.0,
        ),
        mockk(relaxed = true),
        1L,
    )

    @Test
    fun `checkTrailingStop arm zero preserves current behavior on small profit`() {
        // arm=0: peak +2.5%, pnl +0.3%, drop 2.15% → 발동 (arm>trail 이면 막혔을 입력의 회귀 핀).
        // arm 을 명시한다 — 기본값이 3.0 이 되면서 "디폴트가 0" 전제가 깨졌다.
        val m = managerWithArm(0.0)
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        state.updatePeakPrice(51250000.0) // +2.5%
        assertTrue(m.checkTrailingStop(state, 50150000.0)) // drop 2.15%, pnl +0.3%
    }

    @Test
    fun `checkTrailingStop blocks before peak reaches arm`() {
        // arm(5%) > trail(2%): peak +3% 는 미arm — 현행(arm=0)이면 매도였을 입력을 막는다.
        val m = managerWithArm(5.0)
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        state.updatePeakPrice(51500000.0) // peak +3.0% < arm 5%
        // drop (51.5M-50.35M)/51.5M = 2.23% >= 2%, pnl +0.7% > 0
        assertFalse(m.checkTrailingStop(state, 50350000.0))
    }

    @Test
    fun `checkTrailingStop fires after peak reached arm`() {
        val m = managerWithArm(5.0)
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        state.updatePeakPrice(53000000.0) // peak +6.0% >= arm 5%
        // drop (53M-51.8M)/53M = 2.26% >= 2%, pnl +3.6% > 0
        assertTrue(m.checkTrailingStop(state, 51800000.0))
    }

    @Test
    fun `checkTrailingStop with arm still requires profit`() {
        val m = managerWithArm(5.0)
        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)
        state.updatePeakPrice(53000000.0) // peak +6% >= arm
        // drop (53M-49.5M)/53M = 6.6% >= 2% 이지만 pnl -1% — 기존 pnl>0 게이트 유지
        assertFalse(m.checkTrailingStop(state, 49500000.0))
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
    fun `reconcile after restart does not double-count when syncPosition already applied the fill`() = runTest {
        // #20 Critical: durable pendingBuyUuid 복원 + syncPosition 이 거래소 잔고를 이미 반영(position=true, holdVolume=balance).
        // reconcile 이 completeBuy 로 확정할 때 averaging 하면 holdVolume 이 2배가 된다 — replace 절대세팅으로 방지.
        coEvery { upbitClient.getOrder("r1") } returns Order(uuid = "r1", state = "done", executedVolume = "0.001")
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.001", avgBuyPrice = "50000000")
        )
        val state = TradingState("KRW-BTC", pendingBuyUuid = "r1", pendingBuyStrategy = "macd")
        state.position = true // syncPosition 복원분
        state.avgBuyPrice = 50000000.0
        state.holdVolume = 0.001

        val result = manager.reconcilePendingBuy("KRW-BTC", state, 50000000.0)

        assertNotNull(result)
        assertEquals(0.001, state.holdVolume, 1e-9) // 0.002(2×) 가 아님
        assertEquals(50000000.0, state.avgBuyPrice, 0.01)
        assertNull(state.pendingBuyUuid)
        assertEquals("r1", result!!.exchangeOrderId) // 멱등 dedup 키
    }

    @Test
    fun `halted ticker blocks new buys but still reconciles and sells`() = runTest {
        // #19 halt 를 processTicker 초입에서 걸면 매도·reconcile 까지 막혀 포지션이 청산 못 하고 갇힌다.
        // 차단 대상은 신규 진입뿐이다.
        val stateService = mockk<com.trading.bot.persistence.TradingStateService>(relaxed = true)
        val mgr = PositionManager(upbitClient, TradingProperties(), stateService, 1L)
        val halted = TradingState("KRW-BTC", halted = true, haltReason = "reconcile 실패")

        assertNull(mgr.buy("KRW-BTC", halted, 50000000.0, "vb"))
        coVerify(exactly = 0) { upbitClient.placeOrder(any()) }

        // 매도 경로는 halt 와 무관하게 살아 있어야 한다.
        val holding = TradingState(
            "KRW-BTC",
            position = true,
            avgBuyPrice = 50000000.0,
            holdVolume = 0.001,
            halted = true,
            haltReason = "reconcile 실패",
        )
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.001", avgBuyPrice = "50000000"),
            Account(currency = "KRW", balance = "100000"),
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "s1")
        coEvery { upbitClient.getOrder("s1") } returns Order(uuid = "s1", state = "done", executedVolume = "0.001")

        assertNotNull(mgr.sell("KRW-BTC", holding, 52000000.0, SellReason.TAKE_PROFIT))
    }

    @Test
    fun `pending persist failure is retried so the buy gate can clear`() = runTest {
        // buy() 초입 가드가 재기록까지 막으므로, 별도 재시도 경로가 없으면 그 ticker 는 영구 매수 불가.
        val stateService = mockk<com.trading.bot.persistence.TradingStateService>(relaxed = true)
        val mgr = PositionManager(upbitClient, TradingProperties(), stateService, 1L)
        val state = TradingState("KRW-BTC", pendingBuyUuid = "p1", pendingPersistFailed = true)

        mgr.retryPendingPersistIfNeeded(state)

        assertFalse(state.pendingPersistFailed) // upsert 성공 → 게이트 해제
        coVerify(exactly = 1) { stateService.upsert(1L, state) }
    }

    @Test
    fun `pending persist retry keeps the gate closed while the write still fails`() = runTest {
        val stateService = mockk<com.trading.bot.persistence.TradingStateService>()
        coEvery { stateService.upsert(any(), any()) } throws RuntimeException("db down")
        val mgr = PositionManager(upbitClient, TradingProperties(), stateService, 1L)
        val state = TradingState("KRW-BTC", pendingBuyUuid = "p1", pendingPersistFailed = true)

        mgr.retryPendingPersistIfNeeded(state)

        assertTrue(state.pendingPersistFailed)
    }

    @Test
    fun `peak persist failure marks the state dirty so the next tick retries`() = runTest {
        // 신고점 flush 는 갱신 tick 에만 걸린다. 그 1회가 실패한 뒤 하락장으로 돌아서면 다시
        // 갱신될 일이 없어, 재시작 시 낮은 옛 peak 이 복원되고 트레일링이 안 걸린다(#54).
        val stateService = mockk<com.trading.bot.persistence.TradingStateService>()
        coEvery { stateService.upsert(any(), any()) } throws RuntimeException("db down")
        val mgr = PositionManager(upbitClient, TradingProperties(), stateService, 1L)
        val state = TradingState("KRW-BTC", position = true, peakPrice = 52_000_000.0)

        mgr.persistPeak(state)

        assertTrue(state.peakPersistFailed)
    }

    @Test
    fun `peak persist retry clears the dirty flag once the write lands`() = runTest {
        val stateService = mockk<com.trading.bot.persistence.TradingStateService>(relaxed = true)
        val mgr = PositionManager(upbitClient, TradingProperties(), stateService, 1L)
        val state = TradingState("KRW-BTC", position = true, peakPrice = 52_000_000.0, peakPersistFailed = true)

        mgr.persistPeak(state)

        assertFalse(state.peakPersistFailed)
        coVerify(exactly = 1) { stateService.upsert(1L, state) }
    }

    @Test
    fun `peak persist failure does not block new entries`() = runTest {
        // pendingPersistFailed 와 달리 peak 은 진입 차단 사유가 아니다 — 고점 유실은 청산 정확도
        // 문제이지 주문 유실 위험이 아니다. 게이트가 잘못 추가되면 buy() 가 막히므로 실제로 사본다.
        val stateService = mockk<com.trading.bot.persistence.TradingStateService>(relaxed = true)
        val mgr = PositionManager(upbitClient, TradingProperties(), stateService, 1L)
        val state = TradingState("KRW-BTC", peakPersistFailed = true)
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "1000000"))
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "b1")
        coEvery { upbitClient.getOrder("b1") } returns
            Order(uuid = "b1", state = "done", executedVolume = "0.001", price = "50000000")

        assertNotNull(mgr.buy("KRW-BTC", state, 50_000_000.0, "macd_cross"))
    }

    @Test
    fun `every successful upsert path clears the peak dirty flag`() = runTest {
        // 개별 호출자마다 해제하면 빠뜨린 경로에서 불필요한 재시도가 매 tick 돈다.
        val stateService = mockk<com.trading.bot.persistence.TradingStateService>(relaxed = true)
        val mgr = PositionManager(upbitClient, TradingProperties(), stateService, 1L)

        val viaOrThrow = TradingState("KRW-BTC", position = true, peakPersistFailed = true)
        mgr.persistStateOrThrow(viaOrThrow)
        assertFalse(viaOrThrow.peakPersistFailed, "persistStateOrThrow 성공도 dirty 를 해소해야 한다")

        val viaPending = TradingState("KRW-BTC", pendingBuyUuid = "p1", pendingPersistFailed = true, peakPersistFailed = true)
        mgr.retryPendingPersistIfNeeded(viaPending)
        assertFalse(viaPending.peakPersistFailed, "pending 재기록 성공도 dirty 를 해소해야 한다")
    }

    @Test
    fun `a fill commit clears the peak dirty flag on the live state`() = runTest {
        // 체결 커밋은 state.copy() 를 저장하므로 원본 flag 가 남는다. 매도 후에는 position=false 라
        // 재시도 경로조차 못 타 dirty 가 영영 남는다.
        val stateService = mockk<com.trading.bot.persistence.TradingStateService>(relaxed = true)
        val mgr = PositionManager(upbitClient, TradingProperties(), stateService, 1L)
        val state = TradingState("KRW-BTC", position = true, peakPrice = 52_000_000.0, peakPersistFailed = true)
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "s1")
        coEvery { upbitClient.getOrder("s1") } returns
            Order(uuid = "s1", state = "done", executedVolume = "0.001")
        state.markBought(50_000_000.0, 0.001, "macd_cross")

        mgr.sell("KRW-BTC", state, 52_000_000.0, SellReason.TAKE_PROFIT)

        assertFalse(state.peakPersistFailed)
    }

    @Test
    fun `a successful generic persist clears the peak dirty flag`() = runTest {
        // 같은 스냅샷이 저장됐으면 peak 도 durable 이다 — dirty 를 남기면 매 tick 불필요한 재시도가 돈다.
        val stateService = mockk<com.trading.bot.persistence.TradingStateService>(relaxed = true)
        val mgr = PositionManager(upbitClient, TradingProperties(), stateService, 1L)
        val state = TradingState("KRW-BTC", position = true, peakPersistFailed = true)

        mgr.persistState(state)

        assertFalse(state.peakPersistFailed)
    }

    @Test
    fun `sell recovery uses the recorded volume instead of the already-synced zero balance`() = runTest {
        // 재시작 후 syncPosition 이 잔고 0 을 반영하면 holdVolume·avgBuyPrice 가 비어 있다.
        // 그 상태로 잔고 0 을 보고 청산을 확정하면 수량 0·손익 없음의 유령 SELL 이 남는다.
        val mgr = PositionManager(upbitClient, TradingProperties(), mockk(relaxed = true), 1L)
        coEvery { upbitClient.getOrder(any()) } throws RuntimeException("order api down")
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "100000"))
        val state = TradingState(
            "KRW-BTC",
            position = true,
            pendingSellUuid = "s9",
            pendingSellReason = SellReason.TAKE_PROFIT,
            pendingSellVolume = 0.002,
            pendingSellAvgPrice = 50_000_000.0,
        )

        val record = mgr.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertNotNull(record)
        assertEquals(0.002, record!!.volume, 1e-9)
        assertNotNull(record.pnlPercent) // 주문 시점 평단으로 손익이 계산돼야 한다
    }

    @Test
    fun `sell recovery keeps pending when there is no recorded volume to justify it`() = runTest {
        val mgr = PositionManager(upbitClient, TradingProperties(), mockk(relaxed = true), 1L)
        coEvery { upbitClient.getOrder(any()) } throws RuntimeException("order api down")
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "100000"))
        val state = TradingState("KRW-BTC", position = true, pendingSellUuid = "s9", pendingSellReason = SellReason.TAKE_PROFIT)

        assertNull(mgr.reconcilePendingSell("KRW-BTC", state, 52_000_000.0))
        assertEquals("s9", state.pendingSellUuid) // 확정하지 않고 보류
    }

    @Test
    fun `stuck sell reconcile escalates to an error alert exactly once`() = runTest {
        // 미해소 pending sell 은 processTicker 에서 매도·매수 평가를 통째로 막는다 — 조용히 방치되면
        // 보유 포지션이 손절도 못 한다. 매수판 halt 와 같은 상한에서 한 번 알린다.
        val mgr = PositionManager(upbitClient, TradingProperties(reconcileHaltThreshold = 3), mockk(relaxed = true), 1L)
        coEvery { upbitClient.getOrder(any()) } throws RuntimeException("order api down")
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.001", avgBuyPrice = "50000000"),
        )
        val state = TradingState(
            "KRW-BTC",
            position = true,
            holdVolume = 0.001,
            avgBuyPrice = 50_000_000.0,
            pendingSellUuid = "stuck",
            pendingSellReason = SellReason.TAKE_PROFIT,
            pendingSellVolume = 0.001,
        )

        repeat(5) { mgr.reconcilePendingSell("KRW-BTC", state, 52_000_000.0) }

        assertEquals(5, state.sellReconcileFailureCount)
        assertEquals("stuck", state.pendingSellUuid) // 확정하지 않고 유지
    }

    @Test
    fun `syncPosition counts coins locked in an open order as held`() = runTest {
        // 매도 주문이 떠 있는 채로 재시작하면 코인 전량이 locked 라 free 는 0 이다. 이걸 "보유 없음" 으로
        // 동기화하면 손절·익절이 한 번도 평가되지 않고, boughtToday 가 풀리면 그 위에 추가 매수까지 들어간다.
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0", locked = "0.001", avgBuyPrice = "50000000"),
        )
        val state = TradingState("KRW-BTC")

        manager.syncPosition("KRW-BTC", state)

        assertTrue(state.position)
        assertEquals(0.001, state.holdVolume, 1e-9)
        assertEquals(50000000.0, state.avgBuyPrice, 0.01)
    }

    @Test
    fun `restart reconcile does not inherit entry metadata of a position that was already closed`() = runTest {
        // 실제 위험 순서: 신규 매수 주문 → 체결 확인 전 재시작 → runLoop 이 syncPosition 을 먼저 돌려
        // position=true 가 서고 → reconcilePendingBuy 가 확정한다. 이때 markBought 의 "연장" 판정만으로는
        // 옛 진입메타(사용자가 거래소에서 직접 청산해 markSold 를 못 탄 잔재)를 못 막는다.
        val stateService = mockk<com.trading.bot.persistence.TradingStateService>(relaxed = true)
        val mgr = PositionManager(upbitClient, TradingProperties(), stateService, 1L)
        // durable 잔재: 사용자가 거래소에서 직접 청산해 markSold 를 못 탄 옛 진입메타
        val state = TradingState(
            "KRW-BTC",
            buyDate = LocalDate.of(2026, 7, 19),
            peakPrice = 60_000_000.0,
            entryStrategy = "old_strategy",
        )
        coEvery { upbitClient.getAccounts() } returnsMany listOf(
            listOf(Account(currency = "KRW", balance = "200000")), // buy 사이징
            listOf(Account(currency = "BTC", balance = "0.001", avgBuyPrice = "50000000")), // 재시작 후 syncPosition
            listOf(Account(currency = "BTC", balance = "0.001", avgBuyPrice = "50000000")), // reconcile 확정
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "o1")
        coEvery { upbitClient.getOrder("o1") } returns Order(uuid = "o1", state = "wait") // 체결 확인 전

        mgr.buy("KRW-BTC", state, 50_000_000.0, "volatility_breakout")

        // 주문 시점에 끊겨야 한다 — 이 상태가 durable 로 내려가 재시작 시 복원된다.
        assertNull(state.buyDate)
        assertEquals(0.0, state.peakPrice)
        assertNull(state.entryStrategy)

        // 재시작: syncPosition 이 position=true 를 먼저 세우고, 그 뒤 reconcile 이 확정한다.
        coEvery { upbitClient.getOrder("o1") } returns Order(uuid = "o1", state = "done", executedVolume = "0.001")
        mgr.syncPosition("KRW-BTC", state)
        mgr.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertNotEquals(LocalDate.of(2026, 7, 19), state.buyDate)
        assertEquals(50_000_000.0, state.peakPrice) // 옛 고점 60M 상속 금지
        assertEquals("volatility_breakout", state.entryStrategy)
    }

    @Test
    fun `markBought does not inherit entry metadata from a position that no longer exists`() {
        // 봇 정지 중 사용자가 거래소에서 직접 청산하면 markSold 를 못 타 durable 에 옛 진입일·고점이 남는다.
        // 그 잔재를 신규 진입이 물려받으면 진입 즉시 보유일 초과·과거 고점 트레일링으로 청산돼 왕복 비용만 잃는다.
        val stale = TradingState(
            "KRW-BTC",
            position = false,
            buyDate = LocalDate.of(2026, 7, 19),
            peakPrice = 60_000_000.0,
            entryStrategy = "old_strategy",
        )

        stale.markBought(50_000_000.0, 0.001, "volatility_breakout", replace = true, now = LocalDateTime.of(2026, 7, 26, 10, 0))

        assertEquals(LocalDate.of(2026, 7, 26), stale.buyDate)
        assertEquals(50_000_000.0, stale.peakPrice) // 옛 고점 60M 을 물려받지 않는다
        assertEquals("volatility_breakout", stale.entryStrategy)
    }

    @Test
    fun `markSold clears the entry-time exit snapshot`() {
        // 스냅샷은 그 포지션 소유 — 남으면 다음 진입이 이전 포지션의 청산 파라미터를 물려받는다.
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50000000.0, holdVolume = 0.001)
        state.exitParams = ExitParamsSnapshot(
            takeProfitPct = 2.0,
            maxLossPct = 3.0,
            trailingStopPct = 1.0,
            trailingArmPct = 1.5,
            maxHoldDays = 5,
        )

        state.markSold()

        assertNull(state.exitParams)
    }

    @Test
    fun `reconcile does not count an unfilled order as a failure`() = runTest {
        // getOrder 는 죽었지만 잔고조회로 "아직 체결 안 됨" 을 확인한 경우는 장애가 아니다.
        // 이걸 실패로 세면 미체결 주문 하나로 멀쩡한 ticker 가 halt 되어 매수가 정지된다.
        val mgr = PositionManager(upbitClient, TradingProperties(reconcileHaltThreshold = 2), mockk(relaxed = true), 1L)
        coEvery { upbitClient.getOrder(any()) } throws RuntimeException("order api down")
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "100000"))
        val state = TradingState("KRW-BTC", pendingBuyUuid = "n1", pendingBuyStrategy = "vb")

        repeat(5) { mgr.reconcilePendingBuy("KRW-BTC", state, 50000000.0) }

        assertEquals(0, state.reconcileFailureCount)
        assertFalse(state.halted)
        assertEquals("n1", state.pendingBuyUuid) // pending 은 유지(다음 tick 재확인)
    }

    @Test
    fun `reconcile halts ticker after repeated getOrder and balance failures`() = runTest {
        // #19: getOrder·잔고조회가 둘 다 실패해 pending 이 무한 재시도되면 threshold 도달 시 halt.
        val mgr = PositionManager(
            upbitClient,
            TradingProperties(reconcileHaltThreshold = 3),
            mockk(relaxed = true),
            1L,
        )
        coEvery { upbitClient.getOrder(any()) } throws RuntimeException("order api down")
        coEvery { upbitClient.getAccounts() } throws RuntimeException("balance api down")
        val state = TradingState("KRW-BTC", pendingBuyUuid = "h1", pendingBuyStrategy = "vb")

        repeat(2) { mgr.reconcilePendingBuy("KRW-BTC", state, 50000000.0) }
        assertFalse(state.halted) // 아직 미달
        assertEquals(2, state.reconcileFailureCount)

        mgr.reconcilePendingBuy("KRW-BTC", state, 50000000.0) // 3회째 → halt
        assertTrue(state.halted)
        assertEquals(3, state.reconcileFailureCount)
        assertNotNull(state.haltReason)
        assertEquals("h1", state.pendingBuyUuid) // pending 은 여전히 미해소(수동 개입 필요)
    }

    @Test
    fun `reconcile failure count resets once getOrder responds again`() = runTest {
        // getOrder 응답을 받으면(wait 판정) 진전이므로 누적 실패 카운터를 해소한다.
        coEvery { upbitClient.getOrder("h2") } returns Order(uuid = "h2", state = "wait", executedVolume = "0")
        val state = TradingState("KRW-BTC", pendingBuyUuid = "h2", pendingBuyStrategy = "vb")
        state.reconcileFailureCount = 2

        manager.reconcilePendingBuy("KRW-BTC", state, 50000000.0)

        assertEquals(0, state.reconcileFailureCount)
        assertFalse(state.halted)
    }

    @Test
    fun `resetDaily keeps pendingBuyUuid`() {
        val state = TradingState("KRW-BTC", boughtToday = true, pendingBuyUuid = "x")
        state.resetDaily(java.time.LocalDate.of(2026, 6, 11))
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
        val mgr = PositionManager(upbitClient, TradingProperties(takeProfitPct = 2.0), mockk(relaxed = true), 1L)
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

    // --- 매도판 H8: pendingSell reconcile tests ---

    @Test
    fun `sell keeps pendingSell when fill unconfirmed`() = runTest {
        // 매도 placeOrder 성공했으나 awaitFill 이 done 미확정(wait) — uuid·사유 보존해 다음 tick reconcile.
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.001", avgBuyPrice = "50000000")
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "sell-pending")
        coEvery { upbitClient.getOrder("sell-pending") } returns Order(uuid = "sell-pending", state = "wait")

        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)

        val result = manager.sell("KRW-BTC", state, 52000000.0, SellReason.TAKE_PROFIT)

        assertNull(result)
        assertEquals("sell-pending", state.pendingSellUuid) // 보존 → 다음 tick reconcile
        assertEquals(SellReason.TAKE_PROFIT, state.pendingSellReason)
        assertTrue(state.position) // 체결 확정 전 포지션 유지
    }

    @Test
    fun `sell keeps pendingSell when post-order processing throws`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.001", avgBuyPrice = "50000000")
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "sell-throw")
        coEvery { upbitClient.getOrder("sell-throw") } throws RuntimeException("network")

        val state = TradingState("KRW-BTC")
        state.markBought(50000000.0, 0.001)

        val result = manager.sell("KRW-BTC", state, 52000000.0, SellReason.STOP_LOSS)

        assertNull(result)
        assertEquals("sell-throw", state.pendingSellUuid) // 후처리 예외에도 uuid 보존
        assertTrue(state.position)
    }

    @Test
    fun `sell is blocked while pendingSell exists`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.001"))
        val state = TradingState("KRW-BTC", pendingSellUuid = "prev-sell")
        state.markBought(50000000.0, 0.001) // position true 이지만 미해소 매도 존재

        val result = manager.sell("KRW-BTC", state, 52000000.0, SellReason.MANUAL)

        assertNull(result)
        coVerify(exactly = 0) { upbitClient.placeOrder(any()) } // 미해소 매도 있으면 신규매도 금지(이중매도 방지)
    }

    @Test
    fun `reconcilePendingSell records trade and clears position when done`() = runTest {
        coEvery { upbitClient.getOrder("s-done") } returns
            Order(uuid = "s-done", state = "done", executedVolume = "0.001")
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "1000000"))

        val state = TradingState(
            "KRW-BTC", position = true, avgBuyPrice = 50000000.0, holdVolume = 0.001,
            pendingSellUuid = "s-done", pendingSellReason = SellReason.TAKE_PROFIT,
        )

        val result = manager.reconcilePendingSell("KRW-BTC", state, 52000000.0)

        assertNotNull(result)
        assertEquals(TradeSide.SELL, result!!.side)
        assertEquals("TAKE_PROFIT", result.reason)
        assertEquals(0.001, result.volume)
        assertTrue(result.pnlPercent!! > 0) // net pnl (markSold 이전 avgBuyPrice 로 계산)
        assertFalse(state.position) // 전량 청산
        assertNull(state.pendingSellUuid) // 해소
    }

    @Test
    fun `reconcilePendingSell records executed and keeps remaining on partial fill`() = runTest {
        // 부분 체결: state=cancel + executedVolume>0, 잔여 실잔고 있음 → 체결분 기록 + 잔여 포지션 유지 + 잔여분 재매도 대상.
        coEvery { upbitClient.getOrder("s-partial") } returns
            Order(uuid = "s-partial", state = "cancel", executedVolume = "0.0006", remainingVolume = "0.0004")
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.0004", avgBuyPrice = "50000000")
        )

        val state = TradingState(
            "KRW-BTC", position = true, avgBuyPrice = 50000000.0, holdVolume = 0.001,
            pendingSellUuid = "s-partial", pendingSellReason = SellReason.STOP_LOSS,
        )

        val result = manager.reconcilePendingSell("KRW-BTC", state, 52000000.0)

        assertNotNull(result)
        assertEquals(0.0006, result!!.volume) // 체결분만 기록
        assertTrue(state.position) // 잔여 유지
        assertEquals(0.0004, state.holdVolume) // 잔여 실잔고로 갱신
        assertNull(state.pendingSellUuid) // pending 해소(잔여분은 다음 tick 재매도)
    }

    @Test
    fun `reconcilePendingSell clears pending and keeps position when cancelled unfilled`() = runTest {
        // 매도 무산(cancel+0) — 코인 그대로. pending 해소, position 유지 → 다음 tick 재매도 대상.
        coEvery { upbitClient.getOrder("s-cancel") } returns
            Order(uuid = "s-cancel", state = "cancel", executedVolume = "0")

        val state = TradingState(
            "KRW-BTC", position = true, avgBuyPrice = 50000000.0, holdVolume = 0.001,
            pendingSellUuid = "s-cancel", pendingSellReason = SellReason.STOP_LOSS,
        )

        val result = manager.reconcilePendingSell("KRW-BTC", state, 52000000.0)

        assertNull(result)
        assertNull(state.pendingSellUuid) // 무산 → 해소
        assertTrue(state.position) // 코인 그대로 → 유지
    }

    @Test
    fun `reconcilePendingSell keeps pending while order still wait`() = runTest {
        coEvery { upbitClient.getOrder("s-wait") } returns
            Order(uuid = "s-wait", state = "wait", executedVolume = "0")

        val state = TradingState(
            "KRW-BTC", position = true, avgBuyPrice = 50000000.0, holdVolume = 0.001,
            pendingSellUuid = "s-wait", pendingSellReason = SellReason.MANUAL,
        )

        val result = manager.reconcilePendingSell("KRW-BTC", state, 52000000.0)

        assertNull(result)
        assertEquals("s-wait", state.pendingSellUuid) // 진행중 → 유지
        assertTrue(state.position)
    }

    @Test
    fun `reconcilePendingSell keeps pending when wait with partial executed`() = runTest {
        // Upbit 부분체결 진행중(wait+executed>0): 미체결 잔량이 locked 로 묶여 free=0 이어도 terminal 이 아니다.
        // 여기서 확정하면 아직 열린 주문을 청산 오판 → 잔여 체결분 유실. wait 는 executed 무관하게 pending 유지해야 한다.
        coEvery { upbitClient.getOrder("s-wait-partial") } returns
            Order(uuid = "s-wait-partial", state = "wait", executedVolume = "0.0006", remainingVolume = "0.0004")
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0", locked = "0.0004")
        )

        val state = TradingState(
            "KRW-BTC", position = true, avgBuyPrice = 50000000.0, holdVolume = 0.001,
            pendingSellUuid = "s-wait-partial", pendingSellReason = SellReason.STOP_LOSS,
        )

        val result = manager.reconcilePendingSell("KRW-BTC", state, 52000000.0)

        assertNull(result)
        assertEquals("s-wait-partial", state.pendingSellUuid) // 진행중 → 유지(조기 청산 금지)
        assertTrue(state.position)
    }

    @Test
    fun `reconcilePendingSell keeps pending when getOrder fails and balance is locked`() = runTest {
        // getOrder 장애 + free=0 이지만 locked>0(미체결 잔량 묶임) → 미체결로 간주, pending 유지. free 만 보면 청산 오판.
        coEvery { upbitClient.getOrder("s-locked") } throws RuntimeException("order api down")
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0", locked = "0.001")
        )

        val state = TradingState(
            "KRW-BTC", position = true, avgBuyPrice = 50000000.0, holdVolume = 0.001,
            pendingSellUuid = "s-locked", pendingSellReason = SellReason.MANUAL,
        )

        val result = manager.reconcilePendingSell("KRW-BTC", state, 52000000.0)

        assertNull(result)
        assertEquals("s-locked", state.pendingSellUuid) // locked = 미체결 → 유지
        assertTrue(state.position)
    }

    @Test
    fun `reconcilePendingSell recovers from zero balance when getOrder fails`() = runTest {
        // getOrder 장애 + 실잔고 0 → 매도 체결된 것으로 간주(코인 나감). markSold 이전 holdVolume 으로 기록 복원.
        coEvery { upbitClient.getOrder("s-err") } throws RuntimeException("order api down")
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "1000000"))

        val state = TradingState(
            "KRW-BTC", position = true, avgBuyPrice = 50000000.0, holdVolume = 0.001,
            pendingSellUuid = "s-err", pendingSellReason = SellReason.TAKE_PROFIT,
        )

        val result = manager.reconcilePendingSell("KRW-BTC", state, 52000000.0)

        assertNotNull(result)
        assertEquals(0.001, result!!.volume) // markSold 이전 holdVolume 으로 복원
        assertFalse(state.position)
        assertNull(state.pendingSellUuid)
    }

    @Test
    fun `reconcilePendingSell keeps pending when getOrder fails and balance remains`() = runTest {
        // getOrder 장애 + 실잔고 남음 → 미체결로 간주, pending 유지(다음 tick 재시도).
        coEvery { upbitClient.getOrder("s-err2") } throws RuntimeException("order api down")
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.001", avgBuyPrice = "50000000")
        )

        val state = TradingState(
            "KRW-BTC", position = true, avgBuyPrice = 50000000.0, holdVolume = 0.001,
            pendingSellUuid = "s-err2", pendingSellReason = SellReason.MANUAL,
        )

        val result = manager.reconcilePendingSell("KRW-BTC", state, 52000000.0)

        assertNull(result)
        assertEquals("s-err2", state.pendingSellUuid) // 미체결 → 유지
        assertTrue(state.position)
    }

    @Test
    fun `reconcilePendingSell returns null when no pending`() = runTest {
        val state = TradingState("KRW-BTC", position = true, holdVolume = 0.001)
        val result = manager.reconcilePendingSell("KRW-BTC", state, 52000000.0)
        assertNull(result)
    }

    @Test
    fun `markSold clears pendingSell`() {
        val state = TradingState("KRW-BTC", pendingSellUuid = "x", pendingSellReason = SellReason.MANUAL)
        state.markSold()
        assertNull(state.pendingSellUuid)
        assertNull(state.pendingSellReason)
    }

    @Test
    fun `sell records null pnl when avg buy price unknown`() = runTest {
        // 외부 입금분(avg_buy_price=0)을 syncPosition 이 복원한 케이스 — 0%−fee 의 가짜 −0.1% 기록 방지.
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.001", avgBuyPrice = "0")
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "sell-na")
        coEvery { upbitClient.getOrder("sell-na") } returns Order(uuid = "sell-na", state = "done")

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 0.0, holdVolume = 0.001)
        val result = manager.sell("KRW-BTC", state, 52000000.0, SellReason.MANUAL)

        assertNotNull(result)
        assertNull(result!!.pnlPercent)
    }

    // --- 취소 안전성: placeOrder 성공 후 후처리는 NonCancellable 로 원자 완주 ---
    // reload/stop 이 tick 코루틴을 취소해도, 이미 주문이 나간 뒤의 체결확인·상태반영이 중단되면
    // 구 states 에만 남은 pending 이 새 엔진에서 유실돼 이중매수·감사유실로 이어진다. 취소돼도 완주해야 한다.

    @Test
    fun `buy post-order processing completes despite cancellation`() = runTest {
        coEvery { upbitClient.getAccounts() } returnsMany listOf(
            listOf(Account(currency = "KRW", balance = "200000")),
            listOf(Account(currency = "BTC", balance = "0.00038", avgBuyPrice = "52000000")),
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "buy-nc")
        // 체결확인이 진행 중일 때 취소가 끼어드는 창을 delay 로 연다.
        coEvery { upbitClient.getOrder("buy-nc") } coAnswers {
            delay(300)
            Order(uuid = "buy-nc", state = "done", executedVolume = "0.00038")
        }

        val state = TradingState("KRW-BTC")
        val job = launch { manager.buy("KRW-BTC", state, 50000000.0, "test") }
        advanceTimeBy(100) // placeOrder 완료 후 체결확인 진행 중까지 진입
        job.cancel()       // reload/stop 의 취소 시뮬
        advanceUntilIdle()

        // 원자 완주 → 매수 확정·pending 해소 (취소로 중단되면 position=false, pendingBuy 잔존)
        assertTrue(state.position)
        assertNull(state.pendingBuyUuid)
        assertEquals(0.00038, state.holdVolume)
    }

    @Test
    fun `sell post-order processing completes despite cancellation`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.001", avgBuyPrice = "50000000"),
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "sell-nc")
        coEvery { upbitClient.getOrder("sell-nc") } coAnswers {
            delay(300)
            Order(uuid = "sell-nc", state = "done")
        }

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50000000.0, holdVolume = 0.001)
        val job = launch { manager.sell("KRW-BTC", state, 52000000.0, SellReason.TAKE_PROFIT) }
        advanceTimeBy(100)
        job.cancel()
        advanceUntilIdle()

        // 원자 완주 → 청산 확정·pending 해소 (취소로 중단되면 position=true, pendingSell 잔존)
        assertFalse(state.position)
        assertNull(state.pendingSellUuid)
    }

    // --- 취소 전파: suspend API 를 감싼 broad catch 가 CancellationException 을 삼키면 안 된다 ---
    // 특히 pre-order placeOrder catch 가 취소를 'Failed to place order' ERROR 로 로깅하면 DiscordErrorLogAppender 를
    // 통해 매 reload/shutdown 마다 오탐 alert 가 나간다(취소 안전화의 오탐 제거 목표와 정면 충돌).

    @Test
    fun `buy pre-order propagates cancellation instead of logging it as error`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "KRW", balance = "200000"))
        coEvery { upbitClient.placeOrder(any()) } coAnswers { throw CancellationException("cancelled mid-order") }
        val state = TradingState("KRW-BTC")

        assertThrows<CancellationException> { manager.buy("KRW-BTC", state, 50000000.0, "test") }
        assertNull(state.pendingBuyUuid) // 주문 접수 전 취소 — pending 없음
    }

    @Test
    fun `syncPosition propagates cancellation instead of marking unsynced`() = runTest {
        coEvery { upbitClient.getAccounts() } coAnswers { throw CancellationException("cancelled") }
        val state = TradingState("KRW-BTC")

        assertThrows<CancellationException> { manager.syncPosition("KRW-BTC", state) }
    }
}
