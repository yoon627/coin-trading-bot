package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.AccumulateLadder
import com.trading.common.strategy.LadderAction
import com.trading.common.strategy.LadderParams
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 적립 경로(단 매수·부분 매도)의 상태 전이. 스윙 경로 회귀는 PositionManagerExtendedTest 가 지킨다. */
class PositionManagerAccumulateTest {

    private lateinit var upbitClient: UpbitClient
    private lateinit var manager: PositionManager
    private val params = LadderParams(budgetKrw = 100_000.0, maxRungs = 5, stepDownPct = 3.0, stepUpPct = 3.0)

    @BeforeEach
    fun setup() {
        upbitClient = mockk(relaxed = true)
        manager = PositionManager(upbitClient, TradingProperties(), mockk(relaxed = true), 1L)
    }

    private fun krw(balance: String) = Account(currency = "KRW", balance = balance)
    private fun btc(balance: String, avg: String) = Account(currency = "BTC", balance = balance, avgBuyPrice = avg)

    // --- buyRung ---

    @Test
    fun `buyRung fills, counts a rung and records the trigger price`() = runTest {
        coEvery { upbitClient.getAccounts() } returnsMany listOf(
            listOf(krw("200000")),
            listOf(btc("0.0004", "50000000")),
        )
        val orderSlot = slot<OrderRequest>()
        coEvery { upbitClient.placeOrder(capture(orderSlot)) } returns Order(uuid = "rung-1")
        coEvery { upbitClient.getOrder("rung-1") } returns Order(uuid = "rung-1", state = "done", executedVolume = "0.0004", price = "20000")

        val state = TradingState("KRW-BTC")
        val record = manager.buyRung("KRW-BTC", state, 50_000_000.0, LadderAction.Buy(20_000.0, 49_000_000.0), params)

        assertNotNull(record)
        assertEquals("20000", orderSlot.captured.price)
        assertEquals(AccumulateLadder.STRATEGY_NAME, record!!.strategy)
        assertTrue(state.position)
        assertEquals(1, state.rungsFilled)
        assertEquals(49_000_000.0, state.lastActionPrice)
        assertEquals(0.0004, state.holdVolume)
    }

    @Test
    fun `buyRung adds to an existing position`() = runTest {
        coEvery { upbitClient.getAccounts() } returnsMany listOf(
            listOf(krw("200000"), btc("0.0004", "50000000")),
            listOf(btc("0.0008", "49500000")),
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "rung-2")
        coEvery { upbitClient.getOrder("rung-2") } returns Order(uuid = "rung-2", state = "done", executedVolume = "0.0004", price = "20000")

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.0004, rungsFilled = 1, lastActionPrice = 50_000_000.0)
        val record = manager.buyRung("KRW-BTC", state, 48_500_000.0, LadderAction.Buy(20_000.0, 48_500_000.0), params)

        assertNotNull(record)
        assertEquals(2, state.rungsFilled)
        assertEquals(0.0008, state.holdVolume)
        assertEquals(49_500_000.0, state.avgBuyPrice)
        assertEquals(48_500_000.0, state.lastActionPrice)
    }

    @Test
    fun `buyRung fill without a post-fill account read keeps the prior holding`() = runTest {
        // 체결 조회는 됐는데 계좌 조회가 코인을 안 돌려주면, 체결분만으로 replace 하면 기존 1단이 장부에서 사라진다.
        coEvery { upbitClient.getAccounts() } returnsMany listOf(
            listOf(krw("200000"), btc("0.0004", "50000000")),
            listOf(krw("180000")),
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "rung-nf")
        coEvery { upbitClient.getOrder("rung-nf") } returns Order(uuid = "rung-nf", state = "done", executedVolume = "0.0004", price = "20000")

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.0004, rungsFilled = 1, lastActionPrice = 50_000_000.0)
        manager.buyRung("KRW-BTC", state, 48_500_000.0, LadderAction.Buy(20_000.0, 48_500_000.0), params)

        assertEquals(0.0008, state.holdVolume, 1e-12)
        assertEquals(2, state.rungsFilled)
    }

    @Test
    fun `buyRung re-measures the budget on the exchange before ordering`() = runTest {
        // 장부는 1단이지만 거래소엔 90,000 원어치가 있다(수동 매수) — 다음 단 20,000 은 예산을 넘는다.
        coEvery { upbitClient.getAccounts() } returns listOf(krw("500000"), btc("0.002", "45000000"))

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 45_000_000.0, holdVolume = 0.0004, rungsFilled = 1, lastActionPrice = 46_000_000.0)
        val record = manager.buyRung("KRW-BTC", state, 44_000_000.0, LadderAction.Buy(20_000.0, 44_000_000.0), params)

        assertNull(record)
        coVerify(exactly = 0) { upbitClient.placeOrder(any()) }
        assertEquals(1, state.rungsFilled)
        assertTrue(state.accumulateSkipReason!!.contains("budget"))
    }

    @Test
    fun `buyRung counts locked coins toward the budget`() = runTest {
        // free 0.0004(20,000) 만 보면 여유가 있지만, 사용자가 지정가로 잠근 0.0016(80,000)도 이 예산으로 산 코인이다.
        coEvery { upbitClient.getAccounts() } returns listOf(
            krw("500000"),
            Account(currency = "BTC", balance = "0.0004", locked = "0.0016", avgBuyPrice = "50000000"),
        )

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.0004, rungsFilled = 1, lastActionPrice = 50_000_000.0)
        val record = manager.buyRung("KRW-BTC", state, 48_000_000.0, LadderAction.Buy(20_000.0, 48_000_000.0), params)

        assertNull(record)
        coVerify(exactly = 0) { upbitClient.placeOrder(any()) }
        assertTrue(state.accumulateSkipReason!!.contains("budget"))
    }

    @Test
    fun `an unfilled rung order keeps the entry metadata of the existing position`() = runTest {
        // 추가 단이 cancel+0 으로 끝나도 buyDate·entryStrategy 는 살아야 한다 — 프로파일을 끄면 보유상한 청산이 그 날짜를 본다.
        coEvery { upbitClient.getAccounts() } returns listOf(krw("200000"), btc("0.0004", "50000000"))
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "rung-x")
        coEvery { upbitClient.getOrder("rung-x") } returns Order(uuid = "rung-x", state = "cancel", executedVolume = "0")

        val state = TradingState(
            "KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.0004, rungsFilled = 1, lastActionPrice = 50_000_000.0,
            buyDate = java.time.LocalDate.of(2026, 9, 1), entryStrategy = "combined",
        )
        assertNull(manager.buyRung("KRW-BTC", state, 48_500_000.0, LadderAction.Buy(20_000.0, 48_500_000.0), params))

        assertEquals(java.time.LocalDate.of(2026, 9, 1), state.buyDate)
        assertEquals("combined", state.entryStrategy)
        assertEquals(1, state.rungsFilled)
        assertNull(state.pendingBuyUuid)
    }

    @Test
    fun `buyRung skips when krw is short and says so`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(krw("10000"))

        val state = TradingState("KRW-BTC")
        val record = manager.buyRung("KRW-BTC", state, 50_000_000.0, LadderAction.Buy(20_000.0, 49_000_000.0), params)

        assertNull(record)
        coVerify(exactly = 0) { upbitClient.placeOrder(any()) }
        assertTrue(state.accumulateSkipReason!!.contains("KRW"))
    }

    @Test
    fun `buyRung keeps the ordinary entry guards`() = runTest {
        val state = TradingState("KRW-BTC", unsynced = true)
        assertNull(manager.buyRung("KRW-BTC", state, 50_000_000.0, LadderAction.Buy(20_000.0, 49_000_000.0), params))
        coVerify(exactly = 0) { upbitClient.placeOrder(any()) }
    }

    @Test
    fun `buyRung counts a rung on any fill so the ledger never disagrees with the balance`() = runTest {
        coEvery { upbitClient.getAccounts() } returnsMany listOf(
            listOf(krw("200000")),
            listOf(btc("0.0001", "50000000")),
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "rung-p")
        // 요청 20,000 중 0.0001 × 50,000,000 = 5,000 만 체결(25%) — 그래도 한 단. 총 투입은 예산 게이트가 막는다.
        coEvery { upbitClient.getOrder("rung-p") } returns Order(uuid = "rung-p", state = "cancel", executedVolume = "0.0001", price = "20000")

        val state = TradingState("KRW-BTC")
        val record = manager.buyRung("KRW-BTC", state, 50_000_000.0, LadderAction.Buy(20_000.0, 49_000_000.0), params)

        assertNotNull(record)
        assertTrue(state.position)
        assertEquals(1, state.rungsFilled)
        assertEquals(49_000_000.0, state.lastActionPrice)
    }

    @Test
    fun `balance recovery of a rung buy only counts the increase over the pre-order holding`() = runTest {
        // 2단째 주문 뒤 getOrder 가 죽었다. 잔고 0.0004 는 주문 전부터 있던 것 — 증분이 없으니 체결로 보지 않는다.
        coEvery { upbitClient.getAccounts() } returns listOf(krw("200000"), btc("0.0004", "50000000"))
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "rung-2")
        coEvery { upbitClient.getOrder("rung-2") } throws RuntimeException("getOrder down")

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.0004, rungsFilled = 1, lastActionPrice = 50_000_000.0)
        assertNull(manager.buyRung("KRW-BTC", state, 48_500_000.0, LadderAction.Buy(20_000.0, 48_500_000.0), params))
        assertEquals("rung-2", state.pendingBuyUuid)
        assertEquals(0.0004, state.pendingBuyPriorVolume)

        assertNull(manager.reconcilePendingBuy("KRW-BTC", state, 48_500_000.0))
        assertEquals(1, state.rungsFilled)
        assertEquals("rung-2", state.pendingBuyUuid)

        // 잔고가 늘었으면 그 증분만 이 주문의 체결이다.
        coEvery { upbitClient.getAccounts() } returns listOf(krw("180000"), btc("0.0008", "49500000"))
        val record = manager.reconcilePendingBuy("KRW-BTC", state, 48_500_000.0)

        assertNotNull(record)
        assertEquals(2, state.rungsFilled)
        assertEquals(48_500_000.0, state.lastActionPrice)
        assertEquals(0.0008, state.holdVolume)
        assertNull(state.pendingBuyUuid)
    }

    @Test
    fun `syncPosition with clearWhenEmpty drops a position the exchange no longer holds`() = runTest {
        // 수동 전량 매도 뒤 — 장부가 "보유"로 남으면 다음 하락에 추가 단을 사 청산을 되돌린다. 스윙 기본 경로는 그대로.
        coEvery { upbitClient.getAccounts() } returns listOf(krw("300000"))
        val ladder = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.001, rungsFilled = 3)
        manager.syncPosition("KRW-BTC", ladder, clearWhenEmpty = true)
        assertFalse(ladder.position)
        assertEquals(0.0, ladder.holdVolume)

        val swing = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.001)
        manager.syncPosition("KRW-BTC", swing)
        assertTrue(swing.position)
    }

    // --- sellVolume ---

    private fun holding4() = TradingState(
        "KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.001, rungsFilled = 4, lastActionPrice = 40_000_000.0,
    )

    @Test
    fun `sellVolume sells one rung, keeps the position and records the trigger`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(btc("0.001", "50000000"))
        val orderSlot = slot<OrderRequest>()
        coEvery { upbitClient.placeOrder(capture(orderSlot)) } returns Order(uuid = "step-1")
        coEvery { upbitClient.getOrder("step-1") } returns Order(uuid = "step-1", state = "done", executedVolume = "0.00025")

        val state = holding4()
        val record = manager.sellVolume("KRW-BTC", state, 52_000_000.0, LadderAction.Sell(0.00025, 52_000_000.0, isFinal = false))

        assertNotNull(record)
        assertEquals("0.00025", orderSlot.captured.volume)
        assertEquals(TradeSide.SELL, record!!.side)
        assertEquals(SellReason.ACCUMULATE_STEP.name, record.reason)
        assertEquals(AccumulateLadder.STRATEGY_NAME, record.strategy)
        assertEquals(0.00025, record.volume)
        assertTrue(record.pnlPercent!! > 0)
        assertTrue(state.position)
        assertEquals(3, state.rungsFilled)
        assertEquals(0.00075, state.holdVolume, 1e-12)
        assertEquals(50_000_000.0, state.avgBuyPrice)
        assertEquals(52_000_000.0, state.lastActionPrice)
        assertNull(state.pendingSellUuid)
    }

    @Test
    fun `sellVolume formats the quantity as a plain decimal`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(btc("0.0002", "50000000"))
        val orderSlot = slot<OrderRequest>()
        coEvery { upbitClient.placeOrder(capture(orderSlot)) } returns Order(uuid = "fmt")
        coEvery { upbitClient.getOrder("fmt") } returns Order(uuid = "fmt", state = "done", executedVolume = "0.00005")

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.0002, rungsFilled = 4, lastActionPrice = 40_000_000.0)
        manager.sellVolume("KRW-BTC", state, 200_000_000.0, LadderAction.Sell(0.00005, 200_000_000.0, isFinal = false))

        assertEquals("0.00005", orderSlot.captured.volume) // "5.0E-5" 는 거래소가 거부한다
    }

    @Test
    fun `sellVolume skips an order that shrinks below the exchange minimum after capping to free balance`() = runTest {
        // 장부 0.001 기준 1단(0.00025 × 52M = 13,000원)은 통과했지만 free 가 0.00005(2,600원)뿐 — 주문하면 매 tick 거부된다.
        coEvery { upbitClient.getAccounts() } returns listOf(btc("0.00005", "50000000"))

        val state = holding4()
        assertNull(manager.sellVolume("KRW-BTC", state, 52_000_000.0, LadderAction.Sell(0.00025, 52_000_000.0, isFinal = false)))

        coVerify(exactly = 0) { upbitClient.placeOrder(any()) }
        assertEquals(4, state.rungsFilled)
    }

    @Test
    fun `sellVolume does not lose a decimal place to binary rounding`() = runTest {
        // BigDecimal(0.0003) 은 0.000299999… 라 8자리 내림이 0.00029999 가 된다 — 한 자리 모자란 주문이 나가고 잔량 1e-8 이 남는다.
        coEvery { upbitClient.getAccounts() } returns listOf(btc("0.001", "50000000"))
        val orderSlot = slot<OrderRequest>()
        coEvery { upbitClient.placeOrder(capture(orderSlot)) } returns Order(uuid = "bin")
        coEvery { upbitClient.getOrder("bin") } returns Order(uuid = "bin", state = "done", executedVolume = "0.0003")

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.001, rungsFilled = 4, lastActionPrice = 40_000_000.0)
        manager.sellVolume("KRW-BTC", state, 52_000_000.0, LadderAction.Sell(0.0003, 52_000_000.0, isFinal = false))

        assertEquals("0.0003", orderSlot.captured.volume)
    }

    @Test
    fun `final rung sells the exchange balance string and resets the ladder`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(btc("0.00025", "50000000"))
        val orderSlot = slot<OrderRequest>()
        coEvery { upbitClient.placeOrder(capture(orderSlot)) } returns Order(uuid = "final")
        coEvery { upbitClient.getOrder("final") } returns Order(uuid = "final", state = "done", executedVolume = "0.00025")

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.00025, rungsFilled = 1, lastActionPrice = 51_000_000.0)
        val record = manager.sellVolume("KRW-BTC", state, 53_000_000.0, LadderAction.Sell(0.00025, 53_000_000.0, isFinal = true))

        assertNotNull(record)
        assertEquals("0.00025", orderSlot.captured.volume)
        assertFalse(state.position)
        assertEquals(0, state.rungsFilled)
        assertEquals(53_000_000.0, state.flatPeak)
    }

    @Test
    fun `final rung partially filled via reconcile keeps one rung for the remainder`() = runTest {
        // rungs=1 전량 매도가 95% 만 체결되면 잔량이 남는다. rung 이 0 이 되면 decide 가 장부·잔고 불일치로
        // 영구 Hold 에 빠지고 적립엔 다른 청산 게이트가 없다 — 잔량은 1단으로 남아 다음 상승에 팔려야 한다.
        coEvery { upbitClient.getAccounts() } returns listOf(btc("0.001", "50000000"))
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "final-wait")
        coEvery { upbitClient.getOrder("final-wait") } returns Order(uuid = "final-wait", state = "wait", executedVolume = "0")

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.001, rungsFilled = 1, lastActionPrice = 45_000_000.0)
        assertNull(manager.sellVolume("KRW-BTC", state, 52_000_000.0, LadderAction.Sell(0.001, 52_000_000.0, isFinal = true)))

        coEvery { upbitClient.getOrder("final-wait") } returns Order(uuid = "final-wait", state = "cancel", executedVolume = "0.00095")
        coEvery { upbitClient.getAccounts() } returns listOf(btc("0.00005", "50000000"))
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertTrue(state.position)
        assertEquals(0.00005, state.holdVolume, 1e-12)
        assertEquals(1, state.rungsFilled)
        assertEquals(52_000_000.0, state.lastActionPrice)
    }

    @Test
    fun `sellVolume caps a rung at the free balance and closes the ledger when nothing free remains`() = runTest {
        // 장부 0.001 중 0.0007 이 봇 밖에서 잠겨 free 가 0.0003 뿐. 잠긴 몫은 우리 포지션이 아니므로(heldVolume 규약)
        // free 를 다 팔면 장부는 청산이고, 사다리는 여기서부터의 눌림을 기다린다.
        coEvery { upbitClient.getAccounts() } returns listOf(btc("0.0003", "50000000"))
        val orderSlot = slot<OrderRequest>()
        coEvery { upbitClient.placeOrder(capture(orderSlot)) } returns Order(uuid = "cap")
        coEvery { upbitClient.getOrder("cap") } returns Order(uuid = "cap", state = "done", executedVolume = "0.0003")

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.001, rungsFilled = 2, lastActionPrice = 40_000_000.0)
        val record = manager.sellVolume("KRW-BTC", state, 52_000_000.0, LadderAction.Sell(0.0005, 52_000_000.0, isFinal = false))

        assertEquals("0.0003", orderSlot.captured.volume)
        assertEquals(0.0003, record!!.volume)
        assertFalse(state.position)
        assertEquals(0, state.rungsFilled)
        assertEquals(52_000_000.0, state.flatPeak)
    }

    @Test
    fun `reconciled fill at or above ninety percent consumes the rung`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(btc("0.001", "50000000"))
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "wait-1")
        coEvery { upbitClient.getOrder("wait-1") } returns Order(uuid = "wait-1", state = "wait", executedVolume = "0")

        val state = holding4()
        assertNull(manager.sellVolume("KRW-BTC", state, 52_000_000.0, LadderAction.Sell(0.00025, 52_000_000.0, isFinal = false)))
        assertEquals("wait-1", state.pendingSellUuid)
        assertEquals(4, state.rungsFilled)

        coEvery { upbitClient.getOrder("wait-1") } returns Order(uuid = "wait-1", state = "cancel", executedVolume = "0.00024")
        coEvery { upbitClient.getAccounts() } returns listOf(btc("0.00076", "50000000"))
        val record = manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertNotNull(record)
        assertEquals(3, state.rungsFilled)
        assertEquals(52_000_000.0, state.lastActionPrice)
        assertEquals(0.00076, state.holdVolume)
        assertTrue(state.position)
        assertNull(state.pendingSellUuid)
    }

    @Test
    fun `reconciled partial sell does not shrink the holding when the unlock lags`() = runTest {
        // 1개 중 0.25 주문, 0.1 체결 후 취소. 거래소가 free 0.75 + locked 0.15 로 답하면 heldVolume 은 0.75 — 실제는 0.9.
        coEvery { upbitClient.getAccounts() } returns listOf(btc("1.0", "50000000"))
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "lag")
        coEvery { upbitClient.getOrder("lag") } returns Order(uuid = "lag", state = "wait", executedVolume = "0")

        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 1.0, rungsFilled = 4, lastActionPrice = 40_000_000.0)
        manager.sellVolume("KRW-BTC", state, 52_000_000.0, LadderAction.Sell(0.25, 52_000_000.0, isFinal = false))

        coEvery { upbitClient.getOrder("lag") } returns Order(uuid = "lag", state = "cancel", executedVolume = "0.1")
        coEvery { upbitClient.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.75", locked = "0.15", avgBuyPrice = "50000000"))
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertEquals(0.9, state.holdVolume, 1e-12)
        assertEquals(4, state.rungsFilled) // 0.1/0.25 = 40% 체결 — rung 유지
    }

    @Test
    fun `reconciled fill below ninety percent keeps the rung for the next tick`() = runTest {
        coEvery { upbitClient.getAccounts() } returns listOf(btc("0.001", "50000000"))
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "wait-2")
        coEvery { upbitClient.getOrder("wait-2") } returns Order(uuid = "wait-2", state = "wait", executedVolume = "0")

        val state = holding4()
        manager.sellVolume("KRW-BTC", state, 52_000_000.0, LadderAction.Sell(0.00025, 52_000_000.0, isFinal = false))

        coEvery { upbitClient.getOrder("wait-2") } returns Order(uuid = "wait-2", state = "cancel", executedVolume = "0.0001")
        coEvery { upbitClient.getAccounts() } returns listOf(btc("0.0009", "50000000"))
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertEquals(4, state.rungsFilled)
        assertEquals(40_000_000.0, state.lastActionPrice)
        assertEquals(0.0009, state.holdVolume)
        assertNull(state.pendingSellUuid)
    }

    // --- swing sizing with reserved krw ---

    @Test
    fun `swing buy sizes from the balance net of reserved krw`() = runTest {
        coEvery { upbitClient.getAccounts() } returnsMany listOf(
            listOf(krw("200000")),
            listOf(Account(currency = "ETH", balance = "0.002", avgBuyPrice = "2500000")),
        )
        val orderSlot = slot<OrderRequest>()
        coEvery { upbitClient.placeOrder(capture(orderSlot)) } returns Order(uuid = "swing")
        coEvery { upbitClient.getOrder("swing") } returns Order(uuid = "swing", state = "done", executedVolume = "0.002")

        val state = TradingState("KRW-ETH")
        manager.buy("KRW-ETH", state, 2_500_000.0, "combined", reservedKrw = 150_000.0)

        assertEquals("5000", orderSlot.captured.price) // (200,000 − 150,000) × 0.1
    }
}
