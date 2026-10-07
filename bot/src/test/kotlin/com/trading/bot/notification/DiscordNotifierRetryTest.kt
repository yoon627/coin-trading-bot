package com.trading.bot.notification

import com.trading.bot.api.RequestValidators
import com.trading.bot.config.DiscordProperties
import io.mockk.every
import io.mockk.mockk
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 실제 WebClient 로 보낸다 — 재시도는 구독을 다시 해 요청을 새로 보내는 동작이라 mock 체인으로는 볼 수 없다. */
class DiscordNotifierRetryTest {

    private lateinit var server: MockWebServer
    private lateinit var notifier: DiscordNotifier

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        // 검증기는 Discord HTTPS 주소만 통과시킨다 — 로컬 서버로 보내려고 검증 결과만 바꾼다.
        val validators = mockk<RequestValidators>()
        every { validators.normalizeDiscordWebhookUrl(any()) } returns server.url("/api/webhooks/1/token").toString()
        notifier = DiscordNotifier(
            WebClient.create(),
            DiscordProperties(webhookUrl = "https://discord.com/api/webhooks/1/token"),
            validators,
            retryBackoff = Duration.ofMillis(10),
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun sendAlert() =
        notifier.sendErrorAlert("logger", "boom", null, 0, "https://discord.com/api/webhooks/1/token")

    private fun awaitRequest(timeoutMs: Long = 2_000) = server.takeRequest(timeoutMs, TimeUnit.MILLISECONDS)

    @Test
    fun `the bean is created with the Discord client among two WebClients`() {
        val discordClient = WebClient.create()
        ApplicationContextRunner()
            .withBean("upbitWebClient", WebClient::class.java, { WebClient.create() })
            .withBean("discordWebClient", WebClient::class.java, { discordClient })
            .withBean(DiscordProperties::class.java, { DiscordProperties() })
            .withBean(RequestValidators::class.java)
            .withBean(DiscordNotifier::class.java)
            .run { context ->
                assertNull(context.startupFailure)
                val notifier = context.getBean(DiscordNotifier::class.java)
                val field = DiscordNotifier::class.java.getDeclaredField("discordWebClient").apply { isAccessible = true }
                assertSame(discordClient, field.get(notifier))
            }
    }

    @Test
    fun `a rate-limited alert is sent again after Retry-After`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(204))

        sendAlert()

        assertNotNull(awaitRequest())
        assertNotNull(awaitRequest(), "429 뒤에 다시 보내야 한다")
    }

    @Test
    fun `server errors are retried a bounded number of times`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }

        sendAlert()

        repeat(3) { assertNotNull(awaitRequest(), "시도 ${it + 1}") }
        assertNull(awaitRequest(500), "최대 3회까지만 보낸다")
    }

    @Test
    fun `a rejected payload is not sent again`() {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setResponseCode(204))

        sendAlert()

        assertNotNull(awaitRequest())
        assertNull(awaitRequest(500), "400 은 다시 보내도 같다")
    }

    private fun status(code: Int, retryAfter: String? = null): WebClientResponseException {
        val headers = HttpHeaders().apply { retryAfter?.let { set("Retry-After", it) } }
        return WebClientResponseException.create(code, "status", headers, ByteArray(0), null)
    }

    // 이 테스트들의 backoff 는 10ms 다.
    @Test
    fun `a 429 waits for Retry-After and gives up beyond the cap`() {
        assertEquals(Duration.ofMillis(1500), notifier.retryDelay(status(429, "1.5"), 0))
        assertEquals(Duration.ofSeconds(30), notifier.retryDelay(status(429, "30"), 0))
        assertNull(notifier.retryDelay(status(429, "120"), 0))
    }

    @Test
    fun `a 429 without a usable Retry-After falls back to the backoff`() {
        assertEquals(Duration.ofMillis(10), notifier.retryDelay(status(429), 0))
        assertEquals(Duration.ofMillis(10), notifier.retryDelay(status(429, "Wed, 21 Oct 2026 07:28:00 GMT"), 0))
        assertEquals(Duration.ofMillis(10), notifier.retryDelay(status(429, "-1"), 0))
        assertEquals(Duration.ofMillis(10), notifier.retryDelay(status(429, "NaN"), 0))
    }

    @Test
    fun `server and connection failures back off exponentially up to the retry limit`() {
        val connection = WebClientRequestException(RuntimeException("reset"), HttpMethod.POST, URI("https://discord.com"), HttpHeaders())
        assertEquals(Duration.ofMillis(10), notifier.retryDelay(status(503), 0))
        assertEquals(Duration.ofMillis(20), notifier.retryDelay(connection, 1))
        assertNull(notifier.retryDelay(status(503), 2))
    }

    @Test
    fun `other failures are not retried`() {
        assertNull(notifier.retryDelay(status(400), 0))
        assertNull(notifier.retryDelay(status(404), 0))
        assertNull(notifier.retryDelay(IllegalStateException("encode"), 0))
    }
}
