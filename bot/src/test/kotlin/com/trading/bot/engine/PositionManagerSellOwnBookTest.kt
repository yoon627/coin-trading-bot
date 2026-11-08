package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** 봇이 보유 중인 종목을 소유자가 앱에서 더 사도, 매도는 봇 장부 수량까지만 나가고 남은 코인을 봇 포지션으로 다시 잡지 않는다. */
class PositionManagerSellOwnBookTest {

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val manager = PositionManager(upbit, TradingProperties(), stateService, 1L)
    private val sent = mutableListOf<OrderRequest>()

    private val logger = LoggerFactory.getLogger(PositionManager::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    private val price = 10_000_000.0

    @BeforeEach
    fun setup() {
        coEvery { upbit.placeOrder(any()) } coAnswers { sent += firstArg<OrderRequest>(); Order(uuid = "s-1") }
        logger.addAppender(logs)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(logs)
    }

    private fun btc(balance: String, locked: String = "0") = Account(currency = "BTC", balance = balance, locked = locked, avgBuyPrice = "10000000")

    private fun balance(btc: String, locked: String = "0") {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "KRW", balance = "1000000"), btc(btc, locked))
    }

    private fun holding(volume: Double) = TradingState("KRW-BTC", position = true, holdVolume = volume, avgBuyPrice = price)

    private fun filled(state: String, executed: String) = Order(
        uuid = "s-1", state = state, executedVolume = executed, paidFee = "0",
        trades = listOf(OrderTrade(price = "10000000", volume = executed, funds = "${executed.toDouble() * price}")),
    )

    private suspend fun stopLoss(state: TradingState) = manager.sell("KRW-BTC", state, price, SellReason.STOP_LOSS)

    // 수량을 잘못 고르면 손절이 빠지거나 줄고 되돌릴 수 없다.
    @Test
    fun `a sellable balance always gets a stop-loss order even when the book is unknown`() = runTest {
        balance("0.06")

        stopLoss(holding(0.0))

        assertEquals(listOf("0.06"), sent.map { it.volume })
    }

    @Test
    fun `coins the owner bought on top of the bot's position are not sold`() = runTest {
        balance("0.06")
        coEvery { upbit.getOrder("s-1") } returns filled("done", "0.01")
        val state = holding(0.01)

        val record = stopLoss(state)

        assertEquals(listOf("0.01"), sent.map { it.volume })
        assertEquals(0.01, record!!.volume, 1e-12)
        assertFalse(state.position)
        assertTrue(logs.list.any { it.level == Level.WARN && "KRW-BTC" in it.formattedMessage && "0.06" in it.formattedMessage })
    }

    @Test
    fun `a leftover below the minimum order is sold with the bot's coins`() = runTest {
        balance("0.0103") // 0.0003 × 1,000만 = 3,000원

        stopLoss(holding(0.01))

        assertEquals(listOf("0.0103"), sent.map { it.volume })
    }

    @Test
    fun `a balance below the book is sold whole`() = runTest {
        balance("0.01")

        stopLoss(holding(0.02))

        assertEquals(listOf("0.01"), sent.map { it.volume })
    }

    @Test
    fun `a book volume is sent as plain decimals rounded down to eight places`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.01"))

        manager.sell("KRW-BTC", holding(0.000012345), 1_000_000_000.0, SellReason.STOP_LOSS)

        assertEquals(listOf("0.00001234"), sent.map { it.volume })
    }

    @Test
    fun `a dust book next to the owner's sellable coins is neither sold nor replaced`() = runTest {
        balance("0.06")
        val state = holding(0.0001)

        stopLoss(state)

        coVerify(exactly = 0) { upbit.placeOrder(any()) }
        assertEquals(0.0001, state.holdVolume)
        assertTrue(state.position)
        // 팔 수 있는 잔고를 두고 손절을 내지 않는 것이라 사람이 확인하도록 한 번 알린다.
        stopLoss(state)
        assertEquals(1, logs.list.count { it.level == Level.ERROR && "0.06" in it.formattedMessage })
    }

    // 앞서 dust 경고가 나간 포지션에서도 청산 보류는 따로 알린다.
    @Test
    fun `a held-back stop-loss is reported even after an earlier dust warning`() = runTest {
        balance("0.06")
        val state = holding(0.0001).apply { dustWarned = true }

        stopLoss(state)

        assertEquals(1, logs.list.count { it.level == Level.ERROR && "0.06" in it.formattedMessage })
    }

    @Test
    fun `a dust book shrinks to the balance when the owner sold part of it`() = runTest {
        balance("0.0002")
        val state = holding(0.0003)

        stopLoss(state)

        coVerify(exactly = 0) { upbit.placeOrder(any()) }
        assertEquals(0.0002, state.holdVolume, 1e-12)
    }

    @Test
    fun `releasing dust does not take over the owner's sellable coins`() = runTest {
        balance("0.06")
        val state = holding(0.0001)

        manager.releaseDust("KRW-BTC", state, price)

        assertFalse(state.position)
        assertEquals(0.0, state.holdVolume)
    }

    @Test
    fun `a capped order rejected as below the minimum keeps the book`() = runTest {
        balance("0.06")
        coEvery { upbit.placeOrder(any()) } throws UpbitApiException(400, "under_min_total_ask", null, "")
        val state = holding(0.01)

        stopLoss(state)

        assertFalse(state.hasPendingSell())
        assertEquals(0.01, state.holdVolume)
        assertTrue(state.dustWarned)
    }

    private fun pendingSell(volume: Double, prior: Double) = holding(volume).apply {
        beginSellOrder("ctb-s", SellReason.STOP_LOSS, Instant.now(), volume, price, prior)
        adoptSellOrder("s-1")
    }

    @Test
    fun `a partially filled order leaves only the bot's unfilled coins in the position`() = runTest {
        val state = pendingSell(volume = 0.01, prior = 0.06)
        coEvery { upbit.getOrder("s-1") } returns filled("cancel", "0.004")
        balance("0.056")

        manager.reconcilePendingSell("KRW-BTC", state, price)

        assertTrue(state.position)
        assertEquals(0.006, state.holdVolume, 1e-12)
    }

    @Test
    fun `a reconciled full fill does not keep the owner's coins as a position`() = runTest {
        val state = pendingSell(volume = 0.01, prior = 0.06)
        coEvery { upbit.getOrder("s-1") } returns filled("done", "0.01")
        balance("0.05")

        manager.reconcilePendingSell("KRW-BTC", state, price)

        assertFalse(state.position)
        assertEquals(0.0, state.holdVolume)
    }
}
