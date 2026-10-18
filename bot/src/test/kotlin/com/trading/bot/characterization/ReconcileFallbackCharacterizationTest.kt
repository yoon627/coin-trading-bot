package com.trading.bot.characterization

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.engine.PositionManager
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * uuid 를 아는 pending 에서 getOrder 가 실패했을 때 실잔고로 넘어가는 규칙을 고정한다.
 * 매수: 증분이 없으면 halt 카운터를 리셋하고, 잔고도 못 읽으면 카운터를 올리고, 증분이 있으면 수수료 미기록(Unrecorded)으로 확정한다.
 * 매도: free + locked 가 남아 있으면 pending 을 두고, 0 이면 주문 시점 수량과 추정 수수료(Estimate)로 확정한다.
 */
class ReconcileFallbackCharacterizationTest {

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val manager = PositionManager(upbit, TradingProperties(), stateService, 1L)

    @BeforeEach
    fun setup() {
        coEvery { upbit.getOrder(any()) } throws RuntimeException("503")
    }

    private fun pendingBuy() = TradingState(
        "KRW-BTC", pendingBuyUuid = "u-1", pendingBuyStrategy = "test", pendingBuyPriorVolume = 0.0, reconcileFailureCount = 3,
    )

    private fun pendingSell() = TradingState(
        "KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.002,
        pendingSellUuid = "s-1", pendingSellReason = SellReason.STOP_LOSS, pendingSellVolume = 0.002,
        pendingSellTriggerPrice = 50_000_000.0,
    )

    @Test
    fun `buy - no balance increase resets the halt counter and keeps the pending`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        val state = pendingBuy()

        assertNull(manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0))

        assertEquals(0, state.reconcileFailureCount)
        assertEquals("u-1", state.pendingBuyUuid)
    }

    @Test
    fun `buy - an unreadable balance raises the halt counter and keeps the pending`() = runTest {
        coEvery { upbit.getAccounts() } throws RuntimeException("accounts down")
        val state = pendingBuy()

        assertNull(manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0))

        assertEquals(4, state.reconcileFailureCount)
        assertEquals("u-1", state.pendingBuyUuid)
    }

    @Test
    fun `buy - a balance increase is confirmed from the balance with an unrecorded fee`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.0011", avg = "51000000"))
        val state = pendingBuy()

        val record = manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertEquals(FeeBasis.Unrecorded, record?.fee)
        assertNull(record?.orderAmount)
        assertEquals(0.0011, record?.volume)
        assertEquals(51_000_000.0, record?.price)
        assertEquals("u-1", record?.exchangeOrderId)
        assertTrue(state.position)
        assertFalse(state.hasPendingBuy())
    }

    @Test
    fun `sell - a balance still held or locked keeps the pending`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0", locked = "0.002"))
        val state = pendingSell()

        assertNull(manager.reconcilePendingSell("KRW-BTC", state, 49_000_000.0))

        assertEquals("s-1", state.pendingSellUuid)
        assertTrue(state.position)
    }

    @Test
    fun `sell - a zero balance is confirmed with the order-time volume and an estimated fee`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        val state = pendingSell()

        val record = manager.reconcilePendingSell("KRW-BTC", state, 49_000_000.0)

        assertEquals(FeeBasis.Estimate, record?.fee)
        assertNull(record?.orderAmount)
        assertEquals(0.002, record?.volume)
        assertEquals(50_000_000.0, record?.price) // 판단 가격
        assertEquals("s-1", record?.exchangeOrderId)
        assertFalse(state.position)
        assertFalse(state.hasPendingSell())
    }
}
