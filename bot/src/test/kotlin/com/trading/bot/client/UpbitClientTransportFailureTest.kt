package com.trading.bot.client

import com.trading.bot.domain.OrderRequest
import com.trading.bot.exchange.OrderFailure
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/** 실제 전송 실패가 내는 예외를 분류기가 어떻게 읽는지 고정한다 — 매도 재전송 여부가 이 분류에 달렸다(#340). */
class UpbitClientTransportFailureTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: UpbitClient

    private val sell = OrderRequest(market = "KRW-BTC", side = "ask", ordType = "market", volume = "0.01", identifier = "ctb-test")

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
        val webClient = WebClient.builder()
            .baseUrl(mockServer.url("/").toString().trimEnd('/'))
            .clientConnector(ReactorClientHttpConnector(HttpClient.create().responseTimeout(Duration.ofSeconds(1))))
            .build()
        client = UpbitClientImpl(webClient, UpbitAuthProvider(accessKey = "test-key", secretKey = "test-secret-key-that-is-long-enough"))
    }

    @AfterEach
    fun teardown() {
        mockServer.shutdown()
    }

    private suspend fun failureOf(response: MockResponse): OrderFailure {
        mockServer.enqueue(response)
        return UpbitOrderErrorClassifier.classify(assertThrows<Exception> { client.placeOrder(sell) })
    }

    @Test
    fun `a request the server never answers is an unknown transient outcome`() = runTest {
        assertEquals(OrderFailure.UNKNOWN_TRANSIENT, failureOf(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)))
    }

    @Test
    fun `a connection closed after the request was read is an unknown transient outcome`() = runTest {
        assertEquals(OrderFailure.UNKNOWN_TRANSIENT, failureOf(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)))
    }

    // 2xx 헤더가 온 뒤라 주문은 접수됐다. 다시 보내지 않고 identifier 로 확정한다.
    @Test
    fun `a response whose body stalls after the headers is not sent again`() = runTest {
        val stalled = MockResponse()
            .setBody("""{"uuid":"s-1"}""")
            .addHeader("Content-Type", "application/json")
            .setBodyDelay(3, TimeUnit.SECONDS)
        assertEquals(OrderFailure.UNKNOWN_OUTCOME, failureOf(stalled))
    }
}
