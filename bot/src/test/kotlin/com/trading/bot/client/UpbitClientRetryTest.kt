package com.trading.bot.client

import com.trading.bot.config.UpbitProperties
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

/**
 * Verifies UpbitClientImpl's rate-limit retry through MockWebServer: a 429 is
 * retried and can recover, retry exhaustion surfaces the original
 * UpbitApiException (not a wrapped one), and placeOrder is never retried. A tiny
 * retryBackoffBase keeps retry timing off the wall clock.
 */
class UpbitClientRetryTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: UpbitClient

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val properties = UpbitProperties(
            accessKey = "test-key",
            secretKey = "test-secret-key-that-is-long-enough",
            baseUrl = mockServer.url("/").toString().trimEnd('/'),
        )
        val authProvider = UpbitAuthProvider(properties)
        val webClient = WebClient.builder()
            .baseUrl(properties.baseUrl)
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
        assertEquals(3, mockServer.requestCount) // 1 initial + 2 retries
    }

    @Test
    fun `getAccounts propagates original UpbitApiException after retry exhaustion`() = runTest {
        repeat(3) { mockServer.enqueue(rateLimited()) }

        val ex = assertThrows<UpbitApiException> { client.getAccounts() }

        assertEquals(429, ex.statusCode)
        assertEquals(3, mockServer.requestCount) // exhausted after 1 initial + 2 retries
    }

    @Test
    fun `placeOrder does not retry on 429`() = runTest {
        // Order creation is non-idempotent: a retry could double-fill. Enqueue
        // extra 429s so that a regression re-adding retry fails on the request
        // count below instead of hanging on an empty MockWebServer queue.
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
