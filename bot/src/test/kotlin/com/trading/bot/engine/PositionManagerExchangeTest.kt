package com.trading.bot.engine

import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.exchange.UnknownOrderPolicy
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 거래 기록은 그 상태 행의 거래소로 남고, 확인이 필요한 주문은 halt 와 함께 review 사유를 남긴다. 사유는 pending 이 모두 비면 사라진다. */
class PositionManagerExchangeTest {

    private val client = mockk<ExchangeClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    // client order id 로 미접수를 확정할 수 없는 거래소. 못 찾은 주문은 사람 확인으로 간다.
    private val manager = PositionManager(
        client, TradingProperties(), stateService, 1L,
        unknownOrderPolicy = UnknownOrderPolicy(notPlacedMisses = 2, notPlacedAfter = Duration.ofSeconds(60), supportsClientOrderId = false),
    )

    private fun cash() = Account(currency = "KRW", balance = "1000000")

    @Test
    fun `a buy record carries the state's exchange`() = runTest {
        coEvery { client.getOrder(any()) } throws RuntimeException("503")
        coEvery { client.getAccounts() } returns listOf(cash(), Account(currency = "005930", balance = "3", avgBuyPrice = "70000"))
        val state = TradingState("KRX-005930", exchange = Exchange.KIS_PAPER, pendingBuyUuid = "o-1", pendingBuyStrategy = "test", pendingBuyPriorVolume = 0.0)

        val record = manager.reconcilePendingBuy("KRX-005930", state, 70_000.0)

        assertEquals(Exchange.KIS_PAPER, record?.exchange)
    }

    @Test
    fun `a sell record carries the state's exchange`() = runTest {
        coEvery { client.getOrder(any()) } throws RuntimeException("503")
        coEvery { client.getAccounts() } returns listOf(cash())
        val state = TradingState(
            "KRX-005930", exchange = Exchange.KIS_PAPER, position = true, avgBuyPrice = 70_000.0, holdVolume = 3.0,
            pendingSellUuid = "o-2", pendingSellReason = SellReason.STOP_LOSS, pendingSellVolume = 3.0, pendingSellTriggerPrice = 68_000.0,
        )

        val record = manager.reconcilePendingSell("KRX-005930", state, 68_000.0)

        assertEquals(Exchange.KIS_PAPER, record?.exchange)
    }

    @Test
    fun `an order that needs review sets the review reason and keeps the halt`() {
        val state = TradingState("KRX-005930", exchange = Exchange.KIS_PAPER).apply { beginBuyOrder("b-1", "test", priorVolume = 0.0) }
        assertNull(state.reviewReason)

        manager.markNeedsReview("KRX-005930", state, TradeSide.BUY, "체결 후보 2건")

        assertTrue(state.halted)
        assertEquals(state.haltReason, state.reviewReason)
        assertTrue("체결 후보 2건" in state.reviewReason.orEmpty(), state.reviewReason)
    }

    @Test
    fun `clearing the other side's pending keeps the review`() {
        val state = TradingState("KRX-005930", exchange = Exchange.KIS_PAPER, pendingSellUuid = "s-1")
            .apply { beginBuyOrder("b-1", "test", priorVolume = 0.0) }
        manager.markNeedsReview("KRX-005930", state, TradeSide.BUY, "체결 후보 2건")

        state.releasePending(TradeSide.SELL, LocalDate.of(2026, 10, 21))

        assertEquals(state.haltReason, state.reviewReason)
    }

    @Test
    fun `an order confirmed after a review lifts the review and leaves the halt for halt clear`() = runTest {
        val state = TradingState("KRX-005930", exchange = Exchange.KIS_PAPER).apply { beginBuyOrder("b-1", "test", priorVolume = 0.0) }
        coEvery { client.getOrderByIdentifier("b-1") } returns null
        manager.reconcilePendingBuy("KRX-005930", state, 70_000.0)
        assertTrue(state.halted)
        assertTrue(state.reviewReason != null, "못 찾은 주문이 review 로 가지 않았다")

        coEvery { client.getOrderByIdentifier("b-1") } returns Order(uuid = "o-1", state = "cancel", executedVolume = "0")
        manager.reconcilePendingBuy("KRX-005930", state, 70_000.0)

        assertNull(state.reviewReason)
        assertTrue(state.halted)
    }
}
