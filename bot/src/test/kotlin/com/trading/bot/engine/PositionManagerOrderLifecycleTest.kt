package com.trading.bot.engine

import com.trading.bot.client.UpbitApiException
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.OrderTrade
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 주문 경로가 orders 에 남기는 생애(체결 아닌 상태). 매수는 저장이 끝난 뒤 보내기 전에 대기, 접수되면 접수, 끝나면 그 상태로 남는다.
 * 매도는 손절 앞에 DB 를 기다리지 않게 보내기 전에는 아무것도 남기지 않는다. 생애 쓰기가 실패해도 매매를 막지 않는 것은 [OrderLedgerLifecycleTest] 가 본다.
 */
class PositionManagerOrderLifecycleTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private class Recording : OrderLifecycle {
        val calls = mutableListOf<String>()
        val orders = mutableListOf<LifecycleOrder>()
        override suspend fun pending(order: LifecycleOrder) = record("PENDING", order)
        override suspend fun open(order: LifecycleOrder) = record("OPEN", order)
        override suspend fun closed(order: LifecycleOrder, outcome: OrderLifecycle.Outcome, exchangeState: String?, note: String?) =
            record(outcome.name, order)

        private fun record(what: String, order: LifecycleOrder) {
            calls += "$what ${order.side} ${order.clientOrderId != null} ${order.exchangeOrderId}"
            orders += order
        }
    }

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val sent = mutableListOf<OrderRequest>()
    private val clock = MutableClock(Instant.parse("2026-11-24T00:00:00Z"))

    private fun manager(lifecycle: OrderLifecycle) = PositionManager(
        upbit, TradingProperties(maxInvestAmount = 100_000.0), stateService, 1L,
        clock = clock, lifecycle = lifecycle,
    )

    private fun krw() = Account(currency = "KRW", balance = "1000000")

    private fun btc(balance: String) = Account(currency = "BTC", balance = balance, avgBuyPrice = "50000000")

    private fun done(uuid: String, volume: String) = Order(
        uuid = uuid, state = "done", executedVolume = volume, paidFee = "50", identifier = sent.lastOrNull()?.identifier,
        trades = listOf(OrderTrade(price = "50000000", volume = volume, funds = "100000")),
    )

    private fun rejected(name: String) = UpbitApiException(400, name, null, "")

    @BeforeEach
    fun setup() {
        coEvery { stateService.upsert(any(), any()) } returns 0L
        coEvery { upbit.placeOrder(any()) } coAnswers { firstArg<OrderRequest>().also { sent += it }.let { Order(uuid = "b-1", state = "wait") } }
    }

    @Test
    fun `an accepted buy leaves pending before sending, then open, and the fill goes to the fill record`() = runTest {
        val lifecycle = Recording()
        coEvery { upbit.getAccounts() } returns listOf(krw()) andThen listOf(krw(), btc("0.002"))
        coEvery { upbit.getOrder("b-1") } coAnswers { done("b-1", "0.002") }

        assertNotNull(manager(lifecycle).buy("KRW-BTC", TradingState("KRW-BTC"), 50_000_000.0, "test"))

        assertEquals(listOf("PENDING BUY true null", "OPEN BUY true b-1"), lifecycle.calls)
    }

    @Test
    fun `a rejected buy closes its pending row as rejected`() = runTest {
        val lifecycle = Recording()
        coEvery { upbit.getAccounts() } returns listOf(krw())
        coEvery { upbit.placeOrder(any()) } throws rejected("insufficient_funds_bid")

        assertNull(manager(lifecycle).buy("KRW-BTC", TradingState("KRW-BTC"), 50_000_000.0, "test"))

        assertEquals(listOf("PENDING BUY true null", "REJECTED BUY true null"), lifecycle.calls)
    }

    @Test
    fun `a buy whose outcome is unknown stays pending`() = runTest {
        val lifecycle = Recording()
        coEvery { upbit.getAccounts() } returns listOf(krw())
        coEvery { upbit.placeOrder(any()) } throws RuntimeException("ReadTimeoutException")

        assertNull(manager(lifecycle).buy("KRW-BTC", TradingState("KRW-BTC"), 50_000_000.0, "test"))

        assertEquals(listOf("PENDING BUY true null"), lifecycle.calls)
    }

    // 저장 없이 나간 매수는 확정할 길이 없어 보내지 않는다. 남길 생애도 없다.
    @Test
    fun `a buy that could not be saved first leaves nothing`() = runTest {
        val lifecycle = Recording()
        coEvery { upbit.getAccounts() } returns listOf(krw())
        coEvery { stateService.upsert(any(), any()) } throws RuntimeException("db down")

        assertNull(manager(lifecycle).buy("KRW-BTC", TradingState("KRW-BTC"), 50_000_000.0, "test"))

        assertTrue(lifecycle.calls.isEmpty(), lifecycle.calls.toString())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a buy that ended without a fill is closed as cancelled`() = runTest {
        val lifecycle = Recording()
        coEvery { upbit.getAccounts() } returns listOf(krw())
        coEvery { upbit.getOrder("b-1") } returns Order(uuid = "b-1", state = "cancel", executedVolume = "0")

        assertNull(manager(lifecycle).buy("KRW-BTC", TradingState("KRW-BTC"), 50_000_000.0, "test"))

        assertEquals("CANCELLED BUY false b-1", lifecycle.calls.last())
    }

    // 손절 매도 앞에 DB 를 기다리지 않는다 — 매도의 첫 생애 줄은 접수다.
    @Test
    fun `a sell writes nothing before sending and records open once accepted`() = runTest {
        val lifecycle = Recording()
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.002)
        coEvery { upbit.getAccounts() } returns listOf(btc("0.002"))
        var callsAtSend: List<String>? = null
        coEvery { upbit.placeOrder(any()) } coAnswers { callsAtSend = lifecycle.calls.toList(); Order(uuid = "s-1", state = "wait") }

        manager(lifecycle).sell("KRW-BTC", state, 49_000_000.0, SellReason.STOP_LOSS)

        assertEquals(emptyList<String>(), callsAtSend)
        assertEquals("OPEN SELL true s-1", lifecycle.calls.first())
    }

    @Test
    fun `a rejected sell is recorded as rejected`() = runTest {
        val lifecycle = Recording()
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.002)
        coEvery { upbit.getAccounts() } returns listOf(btc("0.002"))
        coEvery { upbit.placeOrder(any()) } throws rejected("insufficient_funds_ask")

        manager(lifecycle).sell("KRW-BTC", state, 49_000_000.0, SellReason.STOP_LOSS)

        assertEquals(listOf("REJECTED SELL true null"), lifecycle.calls)
    }

    // --- 결과를 모르는 주문을 식별자로 확정하는 길 ---

    private val tracedWindow = Duration.ofMinutes(30)

    private fun advance(seconds: Long) {
        clock.now = clock.now.plusSeconds(seconds)
    }

    private fun held(volume: Double = 0.001) = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = volume, bookKnown = true)

    private suspend fun unknownBuy(manager: PositionManager): Pair<TradingState, String> {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        coEvery { upbit.placeOrder(any()) } throws RuntimeException("connection reset")
        val state = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        return state to state.pendingBuyIdentifier!!
    }

    private suspend fun unknownSell(manager: PositionManager, state: TradingState): String {
        coEvery { upbit.getAccounts() } returns listOf(btc(state.holdVolume.toString()))
        coEvery { upbit.placeOrder(any()) } throws RuntimeException("connection reset")
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        return state.pendingSellIdentifier!!
    }

    @Test
    fun `a buy found by identifier opens its pending row with the exchange uuid`() = runTest {
        val lifecycle = Recording()
        val manager = manager(lifecycle)
        val (state, identifier) = unknownBuy(manager)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns done("u-1", "0.002")
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))

        assertNotNull(manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0))

        assertEquals(listOf("PENDING BUY true null", "OPEN BUY true u-1"), lifecycle.calls)
        assertEquals(identifier, lifecycle.orders.last().clientOrderId)
    }

    @Test
    fun `a buy the exchange never saw is closed as not placed under its identifier`() = runTest {
        val lifecycle = Recording()
        val manager = manager(lifecycle)
        val (state, identifier) = unknownBuy(manager)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(70)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertEquals(listOf("PENDING BUY true null", "NOT_PLACED BUY true null"), lifecycle.calls)
        assertEquals(identifier, lifecycle.orders.last().clientOrderId)
    }

    @Test
    fun `a traced buy settled after thirty minutes is closed as not placed`() = runTest {
        val lifecycle = Recording()
        val manager = manager(lifecycle)
        val (state, identifier) = unknownBuy(manager)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))
        coEvery { upbit.getClosedOrders(any(), any(), any(), any(), any()) } returns emptyList()

        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(tracedWindow.seconds)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertEquals(listOf("PENDING BUY true null", "NOT_PLACED BUY true null"), lifecycle.calls)
        assertEquals(identifier, lifecycle.orders.last().clientOrderId)
    }

    // 결과를 모른 매도는 대기 줄이 없다 — 찾으면 접수로 넣고, 체결 없이 끝났으면 그 주문번호로 끝낸다.
    @Test
    fun `a sell found by identifier that ended without a fill is opened then closed as cancelled`() = runTest {
        val lifecycle = Recording()
        val manager = manager(lifecycle)
        val state = held()
        val identifier = unknownSell(manager, state)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns Order(uuid = "s-1", identifier = identifier, state = "cancel", executedVolume = "0")

        assertNull(manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0))

        assertEquals(listOf("OPEN SELL true s-1", "CANCELLED SELL true s-1"), lifecycle.calls)
        assertEquals(listOf(identifier, identifier), lifecycle.orders.map { it.clientOrderId })
    }

    @Test
    fun `a sell the exchange never saw is recorded as not placed`() = runTest {
        val lifecycle = Recording()
        val manager = manager(lifecycle)
        val state = held()
        val identifier = unknownSell(manager, state)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(70)
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertEquals(listOf("NOT_PLACED SELL true null"), lifecycle.calls)
        assertEquals(identifier, lifecycle.orders.single().clientOrderId)
    }

    @Test
    fun `a traced sell settled by shrinking the book is recorded as not placed`() = runTest {
        val lifecycle = Recording()
        val manager = manager(lifecycle)
        val state = held(0.003)
        val identifier = unknownSell(manager, state)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        coEvery { upbit.getClosedOrders(any(), any(), any(), any(), any()) } returns emptyList()

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(tracedWindow.seconds)
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertEquals(listOf("NOT_PLACED SELL true null"), lifecycle.calls)
        assertEquals(identifier, lifecycle.orders.single().clientOrderId)
    }

    @Test
    fun `a traced sell without its order volume is recorded as not placed`() = runTest {
        val lifecycle = Recording()
        val manager = manager(lifecycle)
        val state = held().apply {
            beginSellOrder("ctb-old", SellReason.STOP_LOSS, since = clock.instant(), volume = 0.001, triggerPrice = 52_000_000.0, priorVolume = 0.001)
            pendingSellVolume = null
        }
        coEvery { upbit.getOrderByIdentifier("ctb-old") } returns null
        coEvery { upbit.getAccounts() } returns listOf(btc("0.0005"))
        coEvery { upbit.getClosedOrders(any(), any(), any(), any(), any()) } returns emptyList()

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(tracedWindow.seconds)
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertEquals(listOf("NOT_PLACED SELL true null"), lifecycle.calls)
        assertEquals("ctb-old", lifecycle.orders.single().clientOrderId)
    }

    @Test
    fun `a traced sell kept pending because the book is unknown records nothing`() = runTest {
        val lifecycle = Recording()
        val manager = manager(lifecycle)
        val state = held(0.003)
        val identifier = unknownSell(manager, state)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        coEvery { upbit.getClosedOrders(any(), any(), any(), any(), any()) } returns emptyList()
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(tracedWindow.seconds)
        state.bookKnown = false
        coEvery { upbit.getAccounts() } returnsMany listOf(listOf(btc("0.001"))) andThenThrows RuntimeException("accounts down")

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertTrue(state.hasPendingSell())
        assertTrue(lifecycle.calls.isEmpty(), lifecycle.calls.toString())
    }
}
