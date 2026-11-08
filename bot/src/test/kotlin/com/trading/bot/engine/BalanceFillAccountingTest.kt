package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.SellRecovery
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 주문 조회가 안 될 때 잔고 변화만으로 매도를 확정하는 규칙. 소유자 코인이 남아 있어도 이번 주문만큼 줄었는지를 본다. */
class BalanceFillAccountingTest {

    private val upbit = mockk<UpbitClient>()
    private val accounting = BalanceFillAccounting(upbit)

    private fun balance(free: String, locked: String = "0") {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "BTC", balance = free, locked = locked))
    }

    private fun pendingSell(volume: Double?, prior: Double?) = TradingState("KRW-BTC", position = true, holdVolume = 0.01).apply {
        beginSellOrder("ctb-s", SellReason.STOP_LOSS, Instant.now(), volume ?: 0.0, 10_000_000.0, prior ?: 0.0)
        pendingSellVolume = volume
        pendingSellPriorVolume = prior
    }

    private suspend fun recover(state: TradingState, free: String, locked: String = "0"): SellRecovery {
        balance(free, locked)
        return accounting.recoverSellWithoutOrder("KRW-BTC", state)
    }

    // 체결 직후 잔고가 아직 주문 전 값이면 장부가 체결분을 빠뜨린다. 장부가 손절 수량을 정하므로 손절이 줄거나 빠진다.
    @Test
    fun `a buy fill booked before the balance shows it still counts the fill`() {
        val state = TradingState("KRW-BTC").apply { pendingBuyPriorVolume = 0.0001 }

        val fill = balanceBuyFill(state, Account(currency = "BTC", balance = "0.0001"), executedVolume = 0.01, currentPrice = 10_000_000.0)

        assertEquals(0.0101, fill.volume, 1e-12)
    }

    @Test
    fun `a sell is confirmed once the balance fell by the order and nothing is locked`() = runTest {
        val state = pendingSell(volume = 0.01, prior = 0.06)

        assertEquals(SellRecovery.Cleared, recover(state, "0.05"))
        assertTrue(recover(state, "0.055") is SellRecovery.StillHeld)
        assertTrue(recover(state, "0.06") is SellRecovery.StillHeld)
    }

    // 우리 주문이 아직 잠근 동안 소유자가 자기 코인을 팔면 잔고 합은 줄어도 주문은 열려 있다.
    @Test
    fun `an order still holding coins in locked is not confirmed`() = runTest {
        val state = pendingSell(volume = 0.01, prior = 0.06)

        assertTrue(recover(state, "0.04", locked = "0.01") is SellRecovery.StillHeld)
    }

    @Test
    fun `a sell placed before this rule sold the whole balance`() = runTest {
        val state = pendingSell(volume = 0.06, prior = 0.06)

        assertEquals(SellRecovery.Cleared, recover(state, "0"))
        assertTrue(recover(state, "0.01") is SellRecovery.StillHeld)
    }

    @Test
    fun `a sell without its order volumes keeps the old zero-balance rule`() = runTest {
        val state = pendingSell(volume = null, prior = null)

        assertEquals(SellRecovery.Cleared, recover(state, "0"))
        assertTrue(recover(state, "0", locked = "0.01") is SellRecovery.StillHeld)
        assertTrue(recover(state, "0.01") is SellRecovery.StillHeld)
    }
}
