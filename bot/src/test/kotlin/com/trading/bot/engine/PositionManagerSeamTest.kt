package com.trading.bot.engine

import com.trading.bot.characterization.btc
import com.trading.bot.characterization.krw
import com.trading.bot.client.UpbitApiException
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Order
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.BuyRecovery
import com.trading.bot.exchange.FillAccounting
import com.trading.bot.exchange.OrderGate
import com.trading.bot.exchange.SellRecovery
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PositionManager 가 주입된 OrderGate·FillAccounting 을 실제로 거치는지. */
class PositionManagerSeamTest {

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val committed = mutableListOf<TradeRecord>()

    private class RecordingGate(private val open: Boolean) : OrderGate {
        val rejections = mutableListOf<TradeSide>()

        override fun mayPlace(ticker: String, side: TradeSide, now: Instant): Boolean = open

        override fun onDefinitiveRejection(ticker: String, side: TradeSide, now: Instant) {
            rejections += side
        }
    }

    /** 주문 없이는 판정하지 않는 방식. 체결 경로는 이 테스트에서 쓰지 않는다. */
    private object OrderOnlyAccounting : FillAccounting {
        override suspend fun buyFill(ticker: String, state: TradingState, order: Order, executedVolume: Double, currentPrice: Double) =
            error("not used")

        override suspend fun sellRemainder(ticker: String, state: TradingState, order: Order, executedVolume: Double) = error("not used")

        override suspend fun recoverBuyWithoutOrder(ticker: String, state: TradingState, currentPrice: Double) = BuyRecovery.Unsupported

        override suspend fun recoverSellWithoutOrder(ticker: String, state: TradingState) = SellRecovery.Unsupported
    }

    private fun manager(gate: OrderGate = OrderGate.OPEN, fillAccounting: FillAccounting = BalanceFillAccounting(upbit)) =
        PositionManager(upbit, TradingProperties(), stateService, 1L, commitFill = ::commit, orderGate = gate, fillAccounting = fillAccounting)

    private suspend fun commit(persistState: suspend () -> Unit, record: TradeRecord): Boolean {
        persistState()
        committed += record
        return true
    }

    private fun holding() = TradingState("KRW-BTC", position = true, holdVolume = 0.001, avgBuyPrice = 50_000_000.0)

    @Test
    fun `a closed gate stops the buy before any pending is recorded`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        val state = TradingState("KRW-BTC")

        assertNull(manager(RecordingGate(open = false)).buy("KRW-BTC", state, 50_000_000.0, "test"))

        assertFalse(state.hasPendingBuy())
        coVerify(exactly = 0) { stateService.upsert(any(), any()) }
        coVerify(exactly = 0) { upbit.placeOrder(any()) }
    }

    @Test
    fun `a closed gate stops the sell before any pending is recorded`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.001"))
        val state = holding()

        assertNull(manager(RecordingGate(open = false)).sell("KRW-BTC", state, 50_000_000.0, SellReason.STOP_LOSS))

        assertFalse(state.hasPendingSell())
        coVerify(exactly = 0) { stateService.upsert(any(), any()) }
        coVerify(exactly = 0) { upbit.placeOrder(any()) }
    }

    @Test
    fun `a definitive rejection is reported to the gate once`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        coEvery { upbit.placeOrder(any()) } throws UpbitApiException(400, "insufficient_funds_bid", null, "")
        val gate = RecordingGate(open = true)
        val state = TradingState("KRW-BTC")

        manager(gate).buy("KRW-BTC", state, 50_000_000.0, "test")

        assertEquals(listOf(TradeSide.BUY), gate.rejections)
        assertFalse(state.hasPendingBuy())
    }

    @Test
    fun `a buy the accounting cannot recover without the order stays pending and counts toward the halt`() = runTest {
        coEvery { upbit.getOrder("u-1") } throws RuntimeException("getOrder down")
        val state = TradingState("KRW-BTC").apply {
            beginBuyOrder("ctb-1", "test", priorVolume = 0.0)
            adoptBuyOrder("u-1")
        }

        assertNull(manager(fillAccounting = OrderOnlyAccounting).reconcilePendingBuy("KRW-BTC", state, 50_000_000.0))

        assertEquals("u-1", state.pendingBuyUuid)
        assertEquals(1, state.reconcileFailureCount)
        assertTrue(committed.isEmpty())
        coVerify(exactly = 0) { upbit.getAccounts() }
    }

    @Test
    fun `a sell the accounting cannot recover without the order stays pending without a record`() = runTest {
        coEvery { upbit.getOrder("s-1") } throws RuntimeException("getOrder down")
        val state = holding().apply {
            beginSellOrder("ctb-s", SellReason.STOP_LOSS, Instant.now(), 0.001, 49_000_000.0, 0.001)
            adoptSellOrder("s-1")
        }

        assertNull(manager(fillAccounting = OrderOnlyAccounting).reconcilePendingSell("KRW-BTC", state, 49_000_000.0))

        assertEquals("s-1", state.pendingSellUuid)
        assertTrue(state.position)
        assertTrue(committed.isEmpty())
    }
}
