package com.trading.bot.characterization

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.OrderTrade
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.bot.engine.PositionManager
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.coVerifySequence
import io.mockk.mockk
import java.time.LocalDateTime
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Upbit 시장가 매수의 호출 순서와 주문 파라미터를 고정한다.
 * 잔고 조회 → identifier 를 담은 pending 저장 → 주문(ord_type=price, 금액 내림, 상한 100,000원) → 체결 폴링(10회 × 300ms,
 * done·cancel 에서 멈춤) → 실잔고 재조회 → commitFill 순서다. 체결 수량·평단은 체결 응답이 아니라 실잔고에서 읽고,
 * 거래일은 KST 09:00 경계로 찍는다. 최소주문 5,000원과 손절 시점 최소주문 미달이면 주문하지 않는다.
 */
class PositionManagerBuyCharacterizationTest {

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val fills = mockk<FillSink>()

    private fun manager(props: TradingProperties = TradingProperties()) = PositionManager(
        upbit, props, stateService, 1L,
        commitFill = { persistState, record -> persistState(); fills.commit(record) },
    )

    private val saved = mutableListOf<TradingState>()
    private val sent = mutableListOf<OrderRequest>()

    @BeforeEach
    fun setup() {
        coEvery { stateService.upsert(any(), any()) } coAnswers { saved += secondArg<TradingState>().copy() }
        coEvery { fills.commit(any()) } returns true
    }

    private fun placeOrderReturns(order: Order) {
        coEvery { upbit.placeOrder(any()) } coAnswers { sent += firstArg<OrderRequest>(); order }
    }

    private fun doneOrder(executed: String = "0.001") = Order(
        uuid = "u-1", state = "done", executedVolume = executed, paidFee = "26",
        trades = listOf(OrderTrade(price = "52345000", volume = executed, funds = "52345")),
    )

    @Test
    fun `buy reads balance, records the identifier, sends a price order, polls the fill and commits in that order`() = runTest {
        coEvery { upbit.getAccounts() } returnsMany listOf(
            listOf(krw("523456.7")),
            // 체결 응답(0.001, 현재가)과 다른 값이라야 수량·평단을 실잔고에서 읽는지 가른다.
            listOf(krw("471111.7"), btc("0.0011", avg = "51000000")),
        )
        var durableAtSend: TradingState? = null
        coEvery { upbit.placeOrder(any()) } coAnswers {
            sent += firstArg<OrderRequest>()
            durableAtSend = saved.lastOrNull()
            Order(uuid = "u-1", state = "wait")
        }
        coEvery { upbit.getOrder("u-1") } returns doneOrder()
        val state = TradingState("KRW-BTC")

        val record = manager().buy("KRW-BTC", state, 52_000_000.0, "test")

        // 앞은 클라이언트 호출의 전체 집합을, 뒤는 그 호출과 저장·커밋 사이의 순서를 고정한다.
        coVerifySequence {
            upbit.getAccounts()
            upbit.placeOrder(any())
            upbit.getOrder("u-1")
            upbit.getAccounts()
        }
        coVerifyOrder {
            upbit.getAccounts()
            stateService.upsert(1L, any())
            upbit.placeOrder(any())
            stateService.upsert(1L, any())
            upbit.getOrder("u-1")
            upbit.getAccounts()
            fills.commit(any())
        }

        val request = sent.single()
        val identifier = request.identifier!!
        assertTrue(identifier.startsWith("ctb-"))
        // 투자금 = min(523,456.7 × 0.1, 100,000) = 52,345.67 → 내림
        assertEquals(
            mapOf("market" to "KRW-BTC", "side" to "bid", "ord_type" to "price", "price" to "52345", "identifier" to identifier),
            request.toParamMap(),
        )

        // 보내기 전에 저장된 행에 identifier 가 있고 uuid 는 없다.
        assertEquals(identifier, durableAtSend?.pendingBuyIdentifier)
        assertNull(durableAtSend?.pendingBuyUuid)
        assertEquals("test", durableAtSend?.pendingBuyStrategy)
        assertEquals(0.0, durableAtSend?.pendingBuyPriorVolume)
        // 주문 응답 뒤에는 uuid 로 바꿔 저장한다.
        assertEquals("u-1", saved[1].pendingBuyUuid)
        assertNull(saved[1].pendingBuyIdentifier)
        assertEquals(3, saved.size)

        assertEquals(TradeSide.BUY, record?.side)
        assertEquals("u-1", record?.exchangeOrderId)
        assertEquals(51_000_000.0, record?.price)
        assertEquals(0.0011, record?.volume)
        assertEquals(FeeBasis.Measured(26.0), record?.fee)
        assertEquals(52_345.0, record?.orderAmount)
        assertEquals("test", record?.strategy)
        assertTrue(state.position)
        assertEquals(51_000_000.0, state.avgBuyPrice)
        assertEquals(0.0011, state.holdVolume)
        assertFalse(state.hasPendingBuy())
    }

    @Test
    fun `a confirmed buy stamps buyDate and boughtDate with the current KST trading day`() = runTest {
        coEvery { upbit.getAccounts() } returnsMany listOf(listOf(krw()), listOf(krw(), btc("0.001")))
        placeOrderReturns(Order(uuid = "u-1", state = "wait"))
        coEvery { upbit.getOrder("u-1") } returns doneOrder()
        val state = TradingState("KRW-BTC")

        val before = TradingDay.of(LocalDateTime.now(TradingDay.KST))
        manager().buy("KRW-BTC", state, 50_000_000.0, "test")
        val after = TradingDay.of(LocalDateTime.now(TradingDay.KST))

        assertTrue(state.boughtToday)
        assertTrue(state.buyDate in setOf(before, after), "buyDate=${state.buyDate}")
        assertEquals(state.buyDate, state.boughtDate)
        // 커밋된 행도 같은 날짜다.
        assertEquals(state.buyDate, saved.last().buyDate)
        assertTrue(saved.last().boughtToday)
    }

    @Test
    fun `fill polling stops after 10 getOrder calls spaced 300 ms apart and keeps the order pending`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderReturns(Order(uuid = "u-1", state = "wait"))
        coEvery { upbit.getOrder("u-1") } returns Order(uuid = "u-1", state = "wait")
        val state = TradingState("KRW-BTC")

        val start = currentTime
        assertNull(manager().buy("KRW-BTC", state, 50_000_000.0, "test"))

        coVerify(exactly = 10) { upbit.getOrder("u-1") }
        assertEquals(9 * 300L, currentTime - start) // 마지막 조회 뒤에는 기다리지 않는다
        assertEquals("u-1", state.pendingBuyUuid)
        coVerify(exactly = 0) { fills.commit(any()) }
    }

    @Test
    fun `fill polling also stops at the first cancel and confirms what was executed`() = runTest {
        coEvery { upbit.getAccounts() } returnsMany listOf(listOf(krw()), listOf(krw(), btc("0.0005")))
        placeOrderReturns(Order(uuid = "u-1", state = "wait"))
        coEvery { upbit.getOrder("u-1") } returns Order(uuid = "u-1", state = "cancel", executedVolume = "0.0005")
        val state = TradingState("KRW-BTC")

        val start = currentTime
        val record = manager().buy("KRW-BTC", state, 50_000_000.0, "test")

        coVerify(exactly = 1) { upbit.getOrder("u-1") }
        assertEquals(0L, currentTime - start)
        assertEquals(0.0005, record?.volume)
        assertTrue(state.position)
    }

    @Test
    fun `the invest amount is capped at 100,000 KRW`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw("5000000")) // × 0.1 = 500,000
        placeOrderReturns(Order(uuid = "", state = "wait"))

        manager().buy("KRW-BTC", TradingState("KRW-BTC"), 50_000_000.0, "test")

        assertEquals("100000", sent.single().price)
    }

    @Test
    fun `an invest amount below 5,000 KRW sends nothing and writes nothing`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw("49990")) // × 0.1 = 4,999
        val state = TradingState("KRW-BTC")
        // 손절 게이트가 4,999 원을 먼저 막지 않도록 손절 폭을 음수로 둬 최소주문 검사만 남긴다.
        val props = TradingProperties(maxLossPct = -10.0)

        assertNull(manager(props).buy("KRW-BTC", state, 50_000_000.0, "test"))

        coVerify(exactly = 0) { upbit.placeOrder(any()) }
        coVerify(exactly = 0) { stateService.upsert(any(), any()) }
        assertFalse(state.hasPendingBuy())
    }

    @Test
    fun `a buy that would fall below 5,000 KRW at the 5 percent stop-loss is skipped, one won more is sent`() = runTest {
        placeOrderReturns(Order(uuid = "", state = "wait"))

        coEvery { upbit.getAccounts() } returns listOf(krw("52630")) // 5,263 × 0.95 < 5,000
        assertNull(manager().buy("KRW-BTC", TradingState("KRW-BTC"), 50_000_000.0, "test"))
        assertTrue(sent.isEmpty())

        coEvery { upbit.getAccounts() } returns listOf(krw("52640")) // 5,264 × 0.95 ≥ 5,000
        manager().buy("KRW-BTC", TradingState("KRW-BTC"), 50_000_000.0, "test")
        assertEquals("5264", sent.single().price)
    }
}
