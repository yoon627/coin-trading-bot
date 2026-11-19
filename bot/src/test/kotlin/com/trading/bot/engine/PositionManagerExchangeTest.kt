package com.trading.bot.engine

import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.exchange.UnknownOrderPolicy
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 거래 기록은 그 상태 행의 거래소로 남고, 결과를 모르는 주문은 사람 없이 대사로 확정된다. */
class PositionManagerExchangeTest {

    private val client = mockk<ExchangeClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val manager = PositionManager(
        client, TradingProperties(), stateService, 1L,
        unknownOrderPolicy = UnknownOrderPolicy(notPlacedMisses = 2, notPlacedAfter = Duration.ofSeconds(60), supportsClientOrderId = true, tracedNotPlacedAfter = Duration.ofMinutes(30)),
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
    fun `an order missed once and then found cancelled is settled without a person`() = runTest {
        val state = TradingState("KRX-005930", exchange = Exchange.KIS_PAPER).apply { beginBuyOrder("b-1", "test", priorVolume = 0.0) }
        coEvery { client.getOrderByIdentifier("b-1") } returns null
        manager.reconcilePendingBuy("KRX-005930", state, 70_000.0)
        assertTrue(state.hasPendingBuy())

        coEvery { client.getOrderByIdentifier("b-1") } returns Order(uuid = "o-1", state = "cancel", executedVolume = "0")
        manager.reconcilePendingBuy("KRX-005930", state, 70_000.0)

        assertFalse(state.hasPendingBuy(), "취소로 확정된 매수는 사람 없이 다시 살 수 있다")
    }
}
