package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderTrade
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.BuyRecovery
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

    /** 주문할 때 봇 장부가 [book]@[bookAvg] 였고 거래소 잔고는 [prior] 였던 매수. */
    private fun pendingBuy(book: Double, bookAvg: Double, prior: Double) =
        TradingState("KRW-BTC", position = book > 0.0, holdVolume = book, avgBuyPrice = bookAvg).apply {
            beginBuyOrder("ctb-b", "combined", prior)
        }

    private fun filledOrder(volume: String, vwap: Double?) = Order(
        uuid = "b-1", state = "done", executedVolume = volume,
        trades = vwap?.let { listOf(OrderTrade(price = "$it", volume = volume, funds = "${volume.toDouble() * it}")) }.orEmpty(),
    )

    // 봇이 사기 전부터 있던 소유자 코인을 장부에 넣으면 다음 청산에 그 코인까지 팔린다.
    @Test
    fun `a bot buy books only what it bought at the fill price, not the owner's coins`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.06", avgBuyPrice = "30000000"))
        val state = pendingBuy(book = 0.0, bookAvg = 0.0, prior = 0.05)

        val fill = accounting.buyFill("KRW-BTC", state, filledOrder("0.01", vwap = 50_000_000.0), 0.01, currentPrice = 49_000_000.0)

        assertEquals(0.01, fill.volume, 1e-12)
        assertEquals(50_000_000.0, fill.avgPrice, 1e-6)
    }

    @Test
    fun `absorbing the bot's own dust averages the book with the fill`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.0601"))
        val state = pendingBuy(book = 0.0001, bookAvg = 40_000_000.0, prior = 0.0501)

        val fill = accounting.buyFill("KRW-BTC", state, filledOrder("0.01", vwap = 50_000_000.0), 0.01, currentPrice = 49_000_000.0)

        assertEquals(0.0101, fill.volume, 1e-12)
        assertEquals((40_000_000.0 * 0.0001 + 50_000_000.0 * 0.01) / 0.0101, fill.avgPrice, 1e-6)
    }

    // 장부가 손절 수량을 정하므로 체결 직후 잔고가 늦게 반영돼도 체결분을 빠뜨리면 안 된다.
    @Test
    fun `a fill without trade details whose balance has not caught up uses the tick price`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0"))
        val state = pendingBuy(book = 0.0, bookAvg = 0.0, prior = 0.0)

        val fill = accounting.buyFill("KRW-BTC", state, filledOrder("0.01", vwap = null), 0.01, currentPrice = 49_000_000.0)

        assertEquals(0.01, fill.volume, 1e-12)
        assertEquals(49_000_000.0, fill.avgPrice, 1e-6)
    }

    // 주문 전에 아무것도 없었으면 계좌 평단이 곧 이 주문의 체결가다. 재확정 tick 가격을 쓰면 장애 동안의 가격 변동만큼 평단이 어긋난다.
    @Test
    fun `without trade details and with no prior holding the account average is the fill price`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.01", avgBuyPrice = "51000000"))
        val state = pendingBuy(book = 0.0, bookAvg = 0.0, prior = 0.0)

        val fill = accounting.buyFill("KRW-BTC", state, filledOrder("0.01", vwap = null), 0.01, currentPrice = 49_000_000.0)
        val recovery = accounting.recoverBuyWithoutOrder("KRW-BTC", state, currentPrice = 49_000_000.0) as BuyRecovery.Filled

        assertEquals(51_000_000.0, fill.avgPrice, 1e-6)
        assertEquals(51_000_000.0, recovery.fill.avgPrice, 1e-6)
    }

    // 계좌가 아직 이번 체결을 담지 않았으면 남은 평단은 예전 포지션의 것일 수 있다.
    @Test
    fun `an account average from before the fill is not taken as the fill price`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0", avgBuyPrice = "30000000"))
        val state = pendingBuy(book = 0.0, bookAvg = 0.0, prior = 0.0)

        val fill = accounting.buyFill("KRW-BTC", state, filledOrder("0.01", vwap = null), 0.01, currentPrice = 49_000_000.0)

        assertEquals(49_000_000.0, fill.avgPrice, 1e-6)
    }

    // 소유자가 dust 를 옮긴 뒤의 흡수 매수는 장부를 주문 전 잔고보다 크게 잡지 않는다.
    @Test
    fun `a dust book larger than the balance before the order is clamped to it`() {
        val state = TradingState("KRW-BTC", position = true, holdVolume = 0.0003, avgBuyPrice = 40_000_000.0).apply {
            beginBuyOrder("ctb-b", "combined", priorVolume = 0.0001)
        }

        assertEquals(0.0001, state.pendingBuyBookVolume)
    }

    @Test
    fun `an unanswered buy on top of the owner's coins books the balance increase at the tick price`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.06", avgBuyPrice = "30000000"))
        val state = pendingBuy(book = 0.0, bookAvg = 0.0, prior = 0.05)

        val recovery = accounting.recoverBuyWithoutOrder("KRW-BTC", state, currentPrice = 49_000_000.0) as BuyRecovery.Filled

        assertEquals(0.01, recovery.fill.volume, 1e-9)
        assertEquals(49_000_000.0, recovery.fill.avgPrice, 1e-6)
    }

    // 재시작하면 주문 때 장부를 잃는다. 기동 동기화가 잔고 전체를 장부로 잡았으므로 더하면 이중 계산이다.
    @Test
    fun `a buy whose order-time book was lost to a restart keeps the balance-based book`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.06", avgBuyPrice = "30000000"))
        val state = pendingBuy(book = 0.0, bookAvg = 0.0, prior = 0.05).apply {
            pendingBuyBookVolume = null
            pendingBuyBookAvg = null
        }

        val fill = accounting.buyFill("KRW-BTC", state, filledOrder("0.01", vwap = 50_000_000.0), 0.01, currentPrice = 49_000_000.0)

        assertEquals(0.06, fill.volume, 1e-12)
        assertEquals(30_000_000.0, fill.avgPrice, 1e-6)
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
