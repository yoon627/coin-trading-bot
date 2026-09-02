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
    fun `buyRung under-filled below ninety percent does not count the rung`() = runTest {
        coEvery { upbitClient.getAccounts() } returnsMany listOf(
            listOf(krw("200000")),
            listOf(btc("0.0001", "50000000")),
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = "rung-p")
        // 요청 20,000 중 0.0001 × 50,000,000 = 5,000 만 체결(25%).
        coEvery { upbitClient.getOrder("rung-p") } returns Order(uuid = "rung-p", state = "cancel", executedVolume = "0.0001", price = "20000")

        val state = TradingState("KRW-BTC")
        val record = manager.buyRung("KRW-BTC", state, 50_000_000.0, LadderAction.Buy(20_000.0, 49_000_000.0), params)

        assertNotNull(record)
        assertTrue(state.position)
        assertEquals(0, state.rungsFilled)
        assertEquals(0.0, state.lastActionPrice)
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
