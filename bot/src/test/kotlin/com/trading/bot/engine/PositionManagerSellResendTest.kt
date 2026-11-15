package com.trading.bot.engine

import com.trading.bot.client.UpbitApiException
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.OrderTrade
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.OrderGate
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.WebClientRequestException

/**
 * 연결 실패·응답 시한 초과·연결 끊김·5xx·429 로 끝난 매도는 같은 identifier 로 1초·2초 뒤 다시 보낸다(#340).
 * 업비트가 identifier 중복을 거절하므로 주문은 하나다. 한 번이라도 닿았을 수 있으면 그 뒤의 거절을 확정으로 읽지 않는다.
 * 소유자 코인이 남는 매도는 연결 실패만 다시 보낸다 — 중복 방지가 새면 소유자 코인이 팔린다.
 */
class PositionManagerSellResendTest {

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val gate = mockk<OrderGate>(relaxed = true)
    private val manager = PositionManager(upbit, TradingProperties(), mockk<TradingStateService>(relaxed = true), 1L, orderGate = gate)
    private val sent = mutableListOf<OrderRequest>()
    private val price = 10_000_000.0

    @BeforeEach
    fun setup() {
        every { gate.mayPlace(any(), any(), any()) } returns true
        coEvery { upbit.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "1000000"),
            Account(currency = "BTC", balance = "0.01", locked = "0", avgBuyPrice = "10000000"),
        )
    }

    private fun requestFailure(cause: Throwable) = WebClientRequestException(cause, HttpMethod.POST, URI("https://api.upbit.com/v1/orders"), HttpHeaders())

    private val refused = requestFailure(ConnectException("Connection refused"))
    private val reset = requestFailure(IOException("Connection reset by peer"))
    private val insufficient = UpbitApiException(400, "insufficient_funds_ask", null, "")
    private val duplicated = UpbitApiException(400, "duplicated_identifier", null, "")
    private val belowMinimum = UpbitApiException(400, "under_min_total_ask", null, "")

    private fun answers(vararg outcomes: Any) {
        sent.clear()
        val queue = ArrayDeque(outcomes.toList())
        coEvery { upbit.placeOrder(any()) } coAnswers {
            sent += firstArg<OrderRequest>()
            when (val next = queue.removeFirst()) {
                is Throwable -> throw next
                else -> next as Order
            }
        }
    }

    private fun holding() = TradingState("KRW-BTC", position = true, holdVolume = 0.01, avgBuyPrice = price)

    private suspend fun stopLoss(state: TradingState) = manager.sell("KRW-BTC", state, price, SellReason.STOP_LOSS)

    private fun assertOneIdentifier(sends: Int) {
        assertEquals(sends, sent.size)
        assertEquals(1, sent.map { it.identifier }.distinct().size)
    }

    private fun assertNoRejectionRecorded() = verify(exactly = 0) { gate.onDefinitiveRejection(any(), any(), any()) }

    @Test
    fun `a sell refused before connecting is sent again with the same identifier and continues`() = runTest {
        answers(refused, Order(uuid = "s-1"))
        val state = holding()

        val start = currentTime
        stopLoss(state)

        assertOneIdentifier(sends = 2)
        assertEquals("s-1", state.pendingSellUuid)
        assertTrue(currentTime - start >= 1_000, "다시 보내기 전에 1초를 기다린다")
    }

    @Test
    fun `a sell with an unknown transient outcome is sent again with the same identifier and continues`() = runTest {
        listOf(reset, UpbitApiException(500, null, null, ""), UpbitApiException(429, null, null, "")).forEach { transient ->
            answers(transient, Order(uuid = "s-1"))
            val state = holding()

            val start = currentTime
            stopLoss(state)

            assertOneIdentifier(sends = 2)
            assertEquals("s-1", state.pendingSellUuid, transient.toString())
            assertTrue(currentTime - start >= 1_000, "다시 보내기 전에 1초를 기다린다")
        }
    }

    @Test
    fun `a sell that fails three times falls back to confirming by identifier`() = runTest {
        listOf(listOf(refused, refused, refused), listOf(reset, UpbitApiException(502, null, null, ""), reset), listOf(refused, reset, refused)).forEach { failures ->
            answers(*failures.toTypedArray())
            val state = holding()

            val start = currentTime
            stopLoss(state)

            assertEquals(3_000, currentTime - start, "1초·2초 뒤 두 번")
            assertOneIdentifier(sends = 3)
            assertTrue(state.hasPendingSell(), "pending 을 두고 identifier 로 확정한다")
            assertEquals(sent.first().identifier, state.pendingSellIdentifier)
        }
        assertNoRejectionRecorded()
    }

    // 중복 거절은 앞서 보낸 요청이 접수됐다는 뜻이다. 다음 tick 을 기다리지 않고 그 주문을 조회해 이어받는다.
    @Test
    fun `a resend refused as a duplicate adopts the order found by identifier`() = runTest {
        answers(reset, duplicated)
        coEvery { upbit.getOrderByIdentifier(any()) } returns Order(uuid = "s-1")
        coEvery { upbit.getOrder("s-1") } returns Order(
            uuid = "s-1", state = "done", executedVolume = "0.01", paidFee = "50",
            trades = listOf(OrderTrade(price = "10000000", volume = "0.01", funds = "100000")),
        )
        val state = holding()

        stopLoss(state)

        assertOneIdentifier(sends = 2)
        coVerify(exactly = 1) { upbit.getOrderByIdentifier(sent.first().identifier!!) }
        coVerify(exactly = 1) { upbit.getOrder("s-1") }
        assertFalse(state.position, "이어받은 주문의 체결로 매도가 끝난다")
        assertFalse(state.hasPendingSell())
        assertNoRejectionRecorded()
    }

    @Test
    fun `a duplicate whose order cannot be looked up stays pending`() = runTest {
        val lookups: List<suspend () -> Order?> = listOf({ null }, { throw UpbitApiException(429, null, null, "") })
        lookups.forEach { lookup ->
            answers(reset, duplicated)
            coEvery { upbit.getOrderByIdentifier(any()) } coAnswers { lookup() }
            val state = holding()

            stopLoss(state)

            assertOneIdentifier(sends = 2)
            assertTrue(state.hasPendingSell())
            assertNull(state.pendingSellUuid)
        }
        assertNoRejectionRecorded()
    }

    // 닿았을 수 있는 실패 뒤의 거절은 "앞 요청이 접수돼 잔고가 잠겼다"와 구분되지 않는다. pending 을 지우면 체결이 기록되지 않는다.
    @Test
    fun `a rejection after a send that may have reached the exchange keeps the sell pending`() = runTest {
        listOf(listOf(reset, insufficient), listOf(reset, belowMinimum), listOf(refused, reset, insufficient), listOf(reset, refused, insufficient)).forEach { outcomes ->
            answers(*outcomes.toTypedArray())
            val state = holding()

            stopLoss(state)

            assertOneIdentifier(sends = outcomes.size)
            assertTrue(state.hasPendingSell(), outcomes.toString())
        }
        assertNoRejectionRecorded()
    }

    @Test
    fun `a rejection after sends that never connected is a rejection`() = runTest {
        listOf(listOf(refused, insufficient), listOf(refused, refused, insufficient)).forEach { outcomes ->
            answers(*outcomes.toTypedArray())
            val state = holding()

            stopLoss(state)

            assertOneIdentifier(sends = outcomes.size)
            assertFalse(state.hasPendingSell(), outcomes.toString())
        }
        verify(exactly = 2) { gate.onDefinitiveRejection("KRW-BTC", TradeSide.SELL, any()) }
    }

    // 인증 실패나 해석할 수 없는 응답은 다시 보내도 같은 결과이거나 중복 방지에 기댈 근거가 없다.
    @Test
    fun `an unknown outcome that is not transient is not sent again`() = runTest {
        listOf(UpbitApiException(401, "jwt_verification", null, ""), UpbitApiException(418, null, null, ""), RuntimeException("ReadTimeout")).forEach { failure ->
            answers(failure)
            val state = holding()

            val start = currentTime
            stopLoss(state)

            assertEquals(1, sent.size, failure.toString())
            assertTrue(state.hasPendingSell())
            assertEquals(0, currentTime - start)
        }
        answers(refused, UpbitApiException(401, "jwt_verification", null, ""))
        val state = holding()
        stopLoss(state)
        assertEquals(2, sent.size)
        assertTrue(state.hasPendingSell())
    }

    @Test
    fun `a sell that leaves owner coins is sent again only when it never connected`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "1000000"),
            Account(currency = "BTC", balance = "0.03", locked = "0", avgBuyPrice = "10000000"),
        )
        answers(reset)
        val unknown = holding()
        val start = currentTime
        stopLoss(unknown)
        assertEquals(1, sent.size)
        assertEquals("0.01", sent.single().volume)
        assertTrue(unknown.hasPendingSell())
        assertEquals(0, currentTime - start)

        answers(refused, reset)
        val reached = holding()
        stopLoss(reached)
        assertOneIdentifier(sends = 2)
        assertTrue(reached.hasPendingSell())

        answers(refused, Order(uuid = "s-1"))
        val resent = holding()
        stopLoss(resent)
        assertOneIdentifier(sends = 2)
        assertEquals("s-1", resent.pendingSellUuid)
    }

    // 정지가 요청되면 기다리던 재전송을 보내지 않는다. 기록한 identifier 로 확정된다.
    @Test
    fun `a stop requested while waiting to resend sends nothing more`() = runTest {
        listOf(refused, reset).forEach { failure ->
            answers(failure, Order(uuid = "s-1"))
            val state = holding()

            val job = launch { stopLoss(state) }
            advanceTimeBy(500)
            job.cancel()
            advanceUntilIdle()

            assertEquals(1, sent.size)
            assertTrue(state.hasPendingSell())
        }
    }

    // 매수는 어떤 실패도 다시 보내지 않고 identifier 로 확정한다(소유자 결정 범위가 매도뿐).
    @Test
    fun `a failed buy is not sent again and stays pending`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "KRW", balance = "1000000"))
        listOf(refused, reset, UpbitApiException(500, null, null, "")).forEach { failure ->
            answers(failure)
            val state = TradingState("KRW-BTC")

            val start = currentTime
            manager.buy("KRW-BTC", state, price, "test")

            assertEquals(1, sent.size, failure.toString())
            assertTrue(state.hasPendingBuy())
            assertEquals(0, currentTime - start)
        }
    }
}
