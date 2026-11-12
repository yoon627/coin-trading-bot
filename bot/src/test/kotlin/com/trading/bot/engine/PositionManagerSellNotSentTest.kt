package com.trading.bot.engine

import com.trading.bot.client.UpbitApiException
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.mockk
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.WebClientRequestException

/**
 * 연결이 서기 전에 실패한 매도는 업비트에 닿지 않아 같은 identifier 로 바로 다시 보낸다(#340).
 * 닿았을 수 있는 실패는 지금처럼 identifier 로 확정한다 — 그때 다시 보내면 소유자 코인까지 팔 수 있다.
 */
class PositionManagerSellNotSentTest {

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val manager = PositionManager(upbit, TradingProperties(), mockk<TradingStateService>(relaxed = true), 1L)
    private val sent = mutableListOf<OrderRequest>()
    private val price = 10_000_000.0

    @BeforeEach
    fun setup() {
        coEvery { upbit.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "1000000"),
            Account(currency = "BTC", balance = "0.01", locked = "0", avgBuyPrice = "10000000"),
        )
    }

    private val refused = WebClientRequestException(ConnectException("Connection refused"), HttpMethod.POST, URI("https://api.upbit.com/v1/orders"), HttpHeaders())

    private fun answers(vararg outcomes: Any) {
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

    @Test
    fun `a sell refused before connecting is sent again with the same identifier and continues`() = runTest {
        answers(refused, Order(uuid = "s-1"))
        val state = holding()

        val start = currentTime
        stopLoss(state)

        assertEquals(2, sent.size)
        assertEquals(1, sent.map { it.identifier }.distinct().size)
        assertEquals("s-1", state.pendingSellUuid)
        assertTrue(currentTime - start >= 1_000, "다시 보내기 전에 1초를 기다린다")
    }

    @Test
    fun `a sell refused three times falls back to confirming by identifier`() = runTest {
        answers(refused, refused, refused)
        val state = holding()

        val start = currentTime
        stopLoss(state)

        assertEquals(3_000, currentTime - start, "1초·2초 뒤 두 번")
        assertEquals(3, sent.size)
        assertEquals(1, sent.map { it.identifier }.distinct().size)
        assertTrue(state.hasPendingSell(), "결과를 모르는 것과 같이 pending 을 두고 identifier 로 확정한다")
        assertEquals(sent.first().identifier, state.pendingSellIdentifier)
    }

    // 다시 보낸 요청이 연결 뒤에 끊기면 닿았을 수 있어 더 보내지 않는다. 확정 거절이면 지금처럼 pending 을 지운다.
    @Test
    fun `a resend that ends in an unknown outcome stops resending, and a rejected resend is a rejection`() = runTest {
        answers(refused, WebClientRequestException(IOException("Connection reset by peer"), HttpMethod.POST, URI("https://api.upbit.com/v1/orders"), HttpHeaders()))
        val unknown = holding()
        stopLoss(unknown)
        assertEquals(2, sent.size)
        assertTrue(unknown.hasPendingSell())

        sent.clear()
        answers(refused, UpbitApiException(400, "insufficient_funds_ask", null, ""))
        val rejected = holding()
        stopLoss(rejected)
        assertEquals(2, sent.size)
        assertFalse(rejected.hasPendingSell())
    }

    // 정지가 요청되면 기다리던 재전송을 보내지 않는다. 기록한 identifier 로 확정된다.
    @Test
    fun `a stop requested while waiting to resend sends nothing more`() = runTest {
        answers(refused, Order(uuid = "s-1"))
        val state = holding()

        val job = launch { stopLoss(state) }
        advanceTimeBy(500)
        job.cancel()
        advanceUntilIdle()

        assertEquals(1, sent.size)
        assertTrue(state.hasPendingSell())
    }

    // 매수는 닿지 않은 실패도 다시 보내지 않고 identifier 로 확정한다(소유자 결정 범위가 매도뿐).
    @Test
    fun `a buy refused before connecting is not sent again and stays pending`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "KRW", balance = "1000000"))
        answers(refused)
        val state = TradingState("KRW-BTC")

        val start = currentTime
        manager.buy("KRW-BTC", state, price, "test")

        assertEquals(1, sent.size)
        assertTrue(state.hasPendingBuy())
        assertEquals(0, currentTime - start)
    }

    @Test
    fun `a sell whose request may have reached the exchange is not sent again`() = runTest {
        answers(WebClientRequestException(IOException("Connection reset by peer"), HttpMethod.POST, URI("https://api.upbit.com/v1/orders"), HttpHeaders()))
        val state = holding()

        stopLoss(state)

        assertEquals(1, sent.size)
        assertTrue(state.hasPendingSell())
    }
}
