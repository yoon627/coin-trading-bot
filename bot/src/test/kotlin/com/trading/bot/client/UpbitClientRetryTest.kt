package com.trading.bot.client

import com.trading.bot.domain.OrderRequest
import java.time.Duration
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.client.WebClient

/** 429 는 재시도하고, 재시도가 소진되면 원래 UpbitApiException 을 던지며, placeOrder 는 재시도하지 않는다. */
class UpbitClientRetryTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: UpbitClient

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val authProvider = UpbitAuthProvider(
            accessKey = "test-key",
            secretKey = "test-secret-key-that-is-long-enough",
        )
        val webClient = WebClient.builder()
            .baseUrl(mockServer.url("/").toString().trimEnd('/'))
            .build()
        client = UpbitClientImpl(webClient, authProvider, retryBackoffBase = Duration.ofMillis(1))
    }

    @AfterEach
    fun teardown() {
        mockServer.shutdown()
    }

    @Test
    fun `getAccounts retries on 429 then succeeds`() = runTest {
        mockServer.enqueue(rateLimited())
        mockServer.enqueue(rateLimited())
        mockServer.enqueue(
            MockResponse()
                .setBody("""[{"currency":"KRW","balance":"1000000","locked":"0","avg_buy_price":"0","avg_buy_price_modified":false,"unit_currency":"KRW"}]""")
                .addHeader("Content-Type", "application/json")
        )

        val accounts = client.getAccounts()

        assertEquals(1, accounts.size)
        assertEquals("KRW", accounts[0].currency)
        assertEquals(3, mockServer.requestCount) // 첫 요청 + 재시도 2회
    }

    @Test
    fun `getAccounts propagates original UpbitApiException after retry exhaustion`() = runTest {
        repeat(3) { mockServer.enqueue(rateLimited()) }

        val ex = assertThrows<UpbitApiException> { client.getAccounts() }

        assertEquals(429, ex.statusCode)
        assertEquals(3, mockServer.requestCount)
    }

    @Test
    fun `placeOrder does not retry on 429`() = runTest {
        // 주문 생성은 멱등이 아니라 재시도하면 이중 체결될 수 있다. 429 를 넉넉히 넣어
        // 재시도가 생기면 빈 큐에서 멈추지 않고 아래 요청 수에서 실패하게 한다.
        repeat(3) { mockServer.enqueue(rateLimited()) }

        val ex = assertThrows<UpbitApiException> {
            client.placeOrder(OrderRequest(market = "KRW-BTC", side = "bid", ordType = "price", price = "10000"))
        }

        assertEquals(429, ex.statusCode)
        assertEquals(1, mockServer.requestCount)
    }

    private fun rateLimited() = MockResponse()
        .setResponseCode(429)
        .setBody("""{"error":{"name":"too_many_requests","message":"Too many API requests."}}""")
        .addHeader("Content-Type", "application/json")
}
