package com.trading.bot.kis.client

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.kis.config.KisApiProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory

@OptIn(ExperimentalCoroutinesApi::class)
class KisTokenProviderTest {

    private class MutableClock(@Volatile var now: Instant = Instant.parse("2026-10-21T00:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
        }
    }

    private lateinit var server: MockWebServer
    private val clock = MutableClock()
    private val logger = LoggerFactory.getLogger(KisTokenProvider::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        logger.addAppender(logs)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(logs)
        server.shutdown()
    }

    private fun provider() = KisTokenProvider(
        KisEnvironment.PAPER, TEST_ACCESS, KisClientRegistry.buildWebClient(server.baseUrl(), KisApiProperties()),
        refreshSkew = Duration.ofSeconds(600), minIssueInterval = Duration.ofSeconds(61), clock = clock,
    )

    private fun enqueueToken(token: String, expiresIn: Long = 86_400) {
        server.enqueue(
            kisJson("""{"access_token":"$token","access_token_token_expired":"2026-10-22 09:00:00","token_type":"Bearer","expires_in":$expiresIn}""")
                .setBodyDelay(100, TimeUnit.MILLISECONDS),
        )
    }

    @Test
    fun `동시 요청 10개는 발급을 한 번만 기다린다`() = runTest {
        enqueueToken("tok-1")
        val provider = provider()

        val tokens = (1..10).map { async { provider.token() } }.awaitAll()

        assertThat(tokens).containsOnly("tok-1")
        assertThat(server.requestCount).isEqualTo(1)
        val request = server.next()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/oauth2/tokenP")
        assertThat(ObjectMapper().readValue(request.body.readUtf8(), Map::class.java)).isEqualTo(
            mapOf("grant_type" to "client_credentials", "appkey" to TEST_ACCESS.appKey, "appsecret" to TEST_ACCESS.appSecret),
        )
    }

    @Test
    fun `발급이 최소 간격보다 오래 걸려도 기다리던 호출자는 그 토큰을 받고 다시 발급하지 않는다`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                clock.advance(62)
                return kisJson("""{"access_token":"tok-${server.requestCount}","expires_in":86400}""").setBodyDelay(100, TimeUnit.MILLISECONDS)
            }
        }
        val provider = provider()

        val first = async { provider.token() }
        runCurrent()
        val second = async { provider.token() }
        runCurrent()

        assertThat(listOf(first.await(), second.await())).containsOnly("tok-1")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `만료 skew 전까지는 캐시를 쓰고 skew 안에 들어오면 새로 받는다`() = runTest {
        enqueueToken("tok-1")
        enqueueToken("tok-2")
        val provider = provider()

        provider.token()
        clock.advance(86_400 - 600 - 1)
        assertThat(provider.token()).isEqualTo("tok-1")
        clock.advance(2)
        assertThat(provider.token()).isEqualTo("tok-2")

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `마지막 발급 시도 61초 안에는 다시 발급하지 않고 Transient 다`() = runTest {
        enqueueToken("tok-1")
        enqueueToken("tok-2")
        val provider = provider()

        provider.token()
        provider.invalidate("tok-1")
        clock.advance(60)
        val error = assertThrows<KisTransientException> { provider.token() }
        clock.advance(1)
        val reissued = provider.token()

        assertThat(error.throttled).isFalse()
        assertThat(reissued).isEqualTo("tok-2")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `최소 간격 안에서는 skew 안이어도 아직 만료되지 않은 토큰을 그대로 쓴다`() = runTest {
        enqueueToken("tok-1", expiresIn = 300)
        val provider = provider()

        provider.token()
        clock.advance(30)

        assertThat(provider.token()).isEqualTo("tok-1")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `다른 토큰을 무효화하라는 요청은 지금 토큰을 지우지 않는다`() = runTest {
        enqueueToken("tok-1")
        val provider = provider()

        provider.token()
        provider.invalidate("tok-old")

        assertThat(provider.token()).isEqualTo("tok-1")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `로그와 예외에는 토큰·키·발급 응답 원문이 없다`() = runTest {
        enqueueToken("tok-raw-value")
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error_description":"body-raw-text","error_code":"TEST0403"}"""))
        server.enqueue(kisJson("""{"token_type":"Bearer","expires_in":86400,"note":"body-raw-text"}"""))
        val provider = provider()

        provider.token()
        provider.invalidate("tok-raw-value")
        clock.advance(61)
        val rejected = assertThrows<KisRejectedException> { provider.token() }
        clock.advance(61)
        val malformed = assertThrows<KisMalformedResponseException> { provider.token() }

        assertThat(rejected.httpStatus).isEqualTo(403)
        assertThat(rejected.msgCd).isEqualTo("TEST0403")
        val texts = logs.list.map { it.formattedMessage } + listOf(rejected.message!!, malformed.message!!)
        assertThat(logs.list).isNotEmpty()
        texts.forEach { text ->
            assertThat(text).doesNotContain("tok-raw-value", "body-raw-text", TEST_ACCESS.appKey, TEST_ACCESS.appSecret)
        }
    }

    @Test
    fun `발급 1분 1회 제한 EGW00133 은 상태와 무관하게 throttled Transient 다`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error_description":"body-raw-text","error_code":"EGW00133"}"""))

        val error = assertThrows<KisTransientException> { provider().token() }

        assertThat(error.throttled).isTrue()
        assertThat(error.httpStatus).isEqualTo(403)
        assertThat(error.msgCd).isEqualTo("EGW00133")
        assertThat(error.message).doesNotContain("body-raw-text")
    }

    @Test
    fun `첫 발급이 실패하면 기다리던 나머지는 다시 보내지 않고 모두 Transient 다`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val provider = provider()

        val results = (1..10).map { async { runCatching { provider.token() } } }.awaitAll()

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(results).allSatisfy { assertThat(it.exceptionOrNull()).isInstanceOf(KisTransientException::class.java) }
    }

    @Test
    fun `발급을 시작한 호출자가 취소돼도 발급은 끝나 기다리던 호출자가 그 토큰을 받는다`() = runTest {
        enqueueToken("tok-1")
        val provider = provider()

        val first = launch { provider.token() }
        runCurrent()
        val second = async { provider.token() }
        runCurrent()
        first.cancel()

        assertThat(second.await()).isEqualTo("tok-1")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `발급 전송 실패는 원인 예외를 잇지 않는다`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val error = assertThrows<KisTransientException> { provider().token() }

        assertThat(error.cause).isNull()
        assertThat(error.suppressed).isEmpty()
    }
}
