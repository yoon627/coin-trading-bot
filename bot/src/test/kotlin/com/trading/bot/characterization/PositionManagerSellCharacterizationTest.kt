package com.trading.bot.characterization

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.OrderTrade
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.engine.PositionManager
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifySequence
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Upbit 시장가 매도의 주문 파라미터(side=ask, ord_type=market, volume=거래소 잔고 문자열)와 저장 순서를 고정한다.
 * pending 은 보내기 전에 저장하되 저장이 실패해도 매도는 나간다. 바로 done 이면 paid_fee 를 실측 수수료로, trades 대금 합을
 * 주문 금액으로 기록한다. reconcile 은 주문 조회 뒤 잔고를 다시 읽어 남은 수량을 실잔고로 정하고, 5,000원 미만 잔고는 보내지 않는다.
 */
class PositionManagerSellCharacterizationTest {

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val manager = PositionManager(upbit, TradingProperties(), stateService, 1L)

    private val saved = mutableListOf<TradingState>()
    private val sent = mutableListOf<OrderRequest>()

    @BeforeEach
    fun setup() {
        coEvery { stateService.upsert(any(), any()) } coAnswers { saved += secondArg<TradingState>().copy() }
    }

    private fun holding() = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.002)

    @Test
    fun `sell sends the exchange balance string as a market ask after recording the identifier`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.00200000"))
        var durableAtSend: TradingState? = null
        coEvery { upbit.placeOrder(any()) } coAnswers {
            sent += firstArg<OrderRequest>()
            durableAtSend = saved.lastOrNull()
            Order(uuid = "s-1", state = "wait")
        }
        coEvery { upbit.getOrder("s-1") } returns Order(
            uuid = "s-1", state = "done", executedVolume = "0.002", paidFee = "50",
            // 체결 대금을 판단 가격 × 수량(100,000)과 다르게 둬 orderAmount 가 trades 합인지 가른다.
            trades = listOf(OrderTrade(price = "49975000", volume = "0.002", funds = "99950")),
        )
        val state = holding()

        val record = manager.sell("KRW-BTC", state, 50_000_000.0, SellReason.STOP_LOSS)

        // 바로 done 이면 잔고를 다시 읽지 않고 주문 전 free 전량을 매도량으로 쓴다.
        coVerifySequence {
            upbit.getAccounts()
            upbit.placeOrder(any())
            upbit.getOrder("s-1")
        }
        val request = sent.single()
        val identifier = request.identifier!!
        assertTrue(identifier.startsWith("ctb-"))
        assertEquals(
            mapOf("market" to "KRW-BTC", "side" to "ask", "ord_type" to "market", "volume" to "0.00200000", "identifier" to identifier),
            request.toParamMap(),
        )
        assertEquals(identifier, durableAtSend?.pendingSellIdentifier)
        assertEquals(SellReason.STOP_LOSS, durableAtSend?.pendingSellReason)
        assertEquals(0.002, durableAtSend?.pendingSellPriorVolume)
        assertEquals(50_000_000.0, durableAtSend?.pendingSellTriggerPrice)

        assertEquals(TradeSide.SELL, record?.side)
        assertEquals(0.002, record?.volume)
        assertEquals(50_000_000.0, record?.price)
        assertEquals("STOP_LOSS", record?.reason)
        assertEquals("s-1", record?.exchangeOrderId)
        assertEquals(FeeBasis.Measured(50.0), record?.fee)
        assertEquals(99_950.0, record?.orderAmount)
        assertEquals(49_975_000.0, record?.executedVwap!!, 1e-3)
        assertFalse(state.position)
        assertFalse(state.hasPendingSell())
    }

    @Test
    fun `a sell is still sent when the pending record cannot be saved`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))
        coEvery { stateService.upsert(any(), any()) } throws RuntimeException("db down")
        coEvery { upbit.placeOrder(any()) } coAnswers { sent += firstArg<OrderRequest>(); throw RuntimeException("ReadTimeout") }
        val state = holding()

        assertNull(manager.sell("KRW-BTC", state, 50_000_000.0, SellReason.STOP_LOSS))

        coVerify(exactly = 1) { stateService.upsert(1L, any()) }
        assertEquals(1, sent.size)
        assertTrue(state.pendingPersistFailed)
        assertEquals(sent.single().identifier, state.pendingSellIdentifier)
    }

    @Test
    fun `reconcile reads the order, then re-reads the balance to decide what is left`() = runTest {
        val state = holding().apply {
            pendingSellUuid = "s-1"
            pendingSellReason = SellReason.TRAILING_STOP
            pendingSellVolume = 0.002
            pendingSellTriggerPrice = 50_000_000.0
        }
        coEvery { upbit.getOrder("s-1") } returns Order(uuid = "s-1", state = "cancel", executedVolume = "0.0015")
        // 남은 잔고를 '주문 수량 0.002 − 체결 0.0015 = 0.0005' 와 다르게 둬 잔고로 잔량을 정하는지 가른다.
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.0007"))

        val record = manager.reconcilePendingSell("KRW-BTC", state, 49_000_000.0)

        coVerifySequence {
            upbit.getOrder("s-1")
            upbit.getAccounts()
        }
        assertEquals(0.0015, record?.volume)
        assertEquals(50_000_000.0, record?.price) // 판단 가격
        assertEquals("TRAILING_STOP", record?.reason)
        assertTrue(state.position)
        assertEquals(0.0007, state.holdVolume)
        assertFalse(state.hasPendingSell())
    }

    @Test
    fun `a holding worth less than 5,000 KRW is not sent, exactly 5,000 KRW is`() = runTest {
        coEvery { upbit.placeOrder(any()) } coAnswers { sent += firstArg<OrderRequest>(); Order(uuid = "", state = "wait") }

        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.00009")) // 4,500원
        val dust = holding()
        assertNull(manager.sell("KRW-BTC", dust, 50_000_000.0, SellReason.STOP_LOSS))
        assertTrue(sent.isEmpty())
        coVerify(exactly = 0) { stateService.upsert(any(), any()) }
        assertEquals(0.00009, dust.holdVolume)
        assertTrue(dust.dustWarned)
        assertFalse(dust.hasPendingSell())

        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.0001")) // 5,000원
        manager.sell("KRW-BTC", holding(), 50_000_000.0, SellReason.STOP_LOSS)
        assertEquals("0.0001", sent.single().volume)
    }
}
