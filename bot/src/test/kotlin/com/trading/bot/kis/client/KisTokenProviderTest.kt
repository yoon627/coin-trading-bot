package com.trading.bot.kis.client

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class KisTokenProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var webClient: WebClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        webClient = WebClient.builder().baseUrl(server.url("/").toString().trimEnd('/')).build()
    }

    @AfterEach
    fun teardown() = server.shutdown()

    private fun enqueueToken(expiresIn: Long, token: String = "tok-1") {
        server.enqueue(
            MockResponse()
                .setBody("""{"access_token":"$token","token_type":"Bearer","expires_in":$expiresIn}""")
                .addHeader("Content-Type", "application/json"),
        )
    }

    @Test
    fun `caches token across calls (single issue)`() = runTest {
        enqueueToken(expiresIn = 86_400)
        val provider = KisTokenProvider(webClient, "ak", "sk", refreshSkewSeconds = 600)

        val t1 = provider.token()
        val t2 = provider.token()

        assertEquals("tok-1", t1)
        assertEquals("tok-1", t2)
        assertEquals(1, server.requestCount) // 두 번째는 캐시
    }

    @Test
    fun `reissues when remaining lifetime within refresh skew`() = runTest {
        // expires_in(100s) < skew(600s) → 항상 만료 임박으로 판단 → 매 호출 재발급.
        enqueueToken(expiresIn = 100, token = "a")
        enqueueToken(expiresIn = 100, token = "b")
        val provider = KisTokenProvider(webClient, "ak", "sk", refreshSkewSeconds = 600)

        provider.token()
        provider.token()

        assertEquals(2, server.requestCount)
    }
}
