package com.trading.bot.kis.client

import com.trading.bot.kis.config.KisApiProperties
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 조회는 정해진 오류에서만 backoff 로 다시 보내고, 주문은 어떤 경우에도 한 번만 보낸다. */
@OptIn(ExperimentalCoroutinesApi::class)
class KisApiRetryTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() = server.shutdown()

    private val price = """{"rt_cd":"0","output":{"stck_prpr":"70500","stck_oprc":"0","stck_hgpr":"0","stck_lwpr":"0"}}"""
    private val throttled = """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}"""
    private val buy = KisCashOrderRequest("005930", KisSide.BUY, KisOrderType.MARKET, qty = 1)

    @Test
    fun `조회는 EGW00201 두 번 뒤 성공하고 500ms·1s 를 기다린다`() = runTest {
        server.enqueue(kisJson(throttled, status = 500))
        server.enqueue(kisJson(throttled))
        server.enqueue(kisJson(price))
        val sleeps = mutableListOf<Long>()

        val quote = server.kisApi(this, sleeps = sleeps).currentPrice("005930", KisLane.QUOTE)

        assertThat(quote.price).isEqualTo(70_500)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(sleeps).containsExactly(500L, 1_000L)
    }

    @Test
    fun `조회 재시도는 매번 리미터 허가를 다시 받는다`() = runTest {
        repeat(2) { server.enqueue(kisJson(throttled)) }
        server.enqueue(kisJson(price))
        val limiter = KisRateLimiter(minIntervalMs = 1_000, shortWaitLimitMs = 2_000, longWaitLimitMs = 30_000) { testScheduler.currentTime }

        server.kisApi(this, limiter = limiter).currentPrice("005930", KisLane.QUOTE)

        // backoff 는 기록만 하고 기다리지 않으므로, 가상 시간은 세 번의 허가 간격(0·1000·2000ms)만큼만 흐른다.
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(testScheduler.currentTime).isEqualTo(2_000L)
    }

    @Test
    fun `조회 재시도를 소진하면 throttled Transient 다`() = runTest {
        repeat(3) { server.enqueue(kisJson(throttled, status = 500)) }

        val error = assertThrows<KisTransientException> { server.kisApi(this).currentPrice("005930", KisLane.QUOTE) }

        assertThat(error.throttled).isTrue()
        assertThat(error.msgCd).isEqualTo("EGW00201")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `조회의 5xx 는 재시도하고 소진하면 throttled 가 아닌 Transient 다`() = runTest {
        listOf(502, 503, 500).forEach { server.enqueue(MockResponse().setResponseCode(it)) }

        val error = assertThrows<KisTransientException> { server.kisApi(this).balance(KisLane.RECONCILE) }

        assertThat(error.throttled).isFalse()
        assertThat(error.httpStatus).isEqualTo(500)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `조회 전송 실패를 소진해도 원인 예외를 잇지 않는다`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }

        val error = assertThrows<KisTransientException> { server.kisApi(this).balance(KisLane.QUERY) }

        assertThat(error.cause).isNull()
        assertThat(error.suppressed).isEmpty()
    }

    @Test
    fun `조회 응답 시간 초과는 재시도한다`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(kisJson(price))

        val quote = server.kisApi(this, properties = KisApiProperties(responseTimeoutSeconds = 1)).currentPrice("005930", KisLane.QUOTE)

        assertThat(quote.price).isEqualTo(70_500)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `조회의 일반 rt_cd 오류는 재시도 없이 Rejected 다`() = runTest {
        server.enqueue(kisJson("""{"rt_cd":"1","msg_cd":"APBK0919","msg1":"조회할 내용이 없습니다"}"""))
        val sleeps = mutableListOf<Long>()

        val error = assertThrows<KisRejectedException> { server.kisApi(this, sleeps = sleeps).currentPrice("005930", KisLane.QUOTE) }

        assertThat(error.rtCd).isEqualTo("1")
        assertThat(error.msgCd).isEqualTo("APBK0919")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun `조회의 HTTP 4xx 는 유량·토큰 오류가 아니면 재시도 없이 Rejected 다`() = runTest {
        server.enqueue(kisJson("""{"rt_cd":"1","msg_cd":"OPSQ0002","msg1":"없는 서비스 코드 입니다"}""", status = 400))
        val sleeps = mutableListOf<Long>()

        val error = assertThrows<KisRejectedException> { server.kisApi(this, sleeps = sleeps).currentPrice("005930", KisLane.QUOTE) }

        assertThat(error.httpStatus).isEqualTo(400)
        assertThat(error.msgCd).isEqualTo("OPSQ0002")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun `조회에서 토큰 만료를 받으면 그 토큰을 무효화하고 새 토큰으로 한 번만 다시 보낸다`() = runTest {
        server.enqueue(kisJson("""{"rt_cd":"1","msg_cd":"EGW00123","msg1":"기간이 만료된 token 입니다."}""", status = 500))
        server.enqueue(kisJson(price))
        val tokens = FakeTokens("tok-old", "tok-new")

        server.kisApi(this, tokens = tokens).currentPrice("005930", KisLane.QUOTE)

        assertThat(tokens.invalidated).containsExactly("tok-old")
        assertThat(server.next().getHeader("authorization")).isEqualTo("Bearer tok-old")
        assertThat(server.next().getHeader("authorization")).isEqualTo("Bearer tok-new")
    }

    @Test
    fun `새 토큰으로도 토큰 오류면 더 보내지 않고 Rejected 다`() = runTest {
        repeat(2) { server.enqueue(kisJson("""{"rt_cd":"1","msg_cd":"EGW00121","msg1":"유효하지 않은 token 입니다."}""")) }

        assertThrows<KisRejectedException> { server.kisApi(this, tokens = FakeTokens("a", "b", "c")).currentPrice("005930", KisLane.QUOTE) }

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `주문 2xx 에 rt_cd 오류는 Rejected 이고 한 번만 보낸다`() = runTest {
        server.enqueue(kisJson("""{"rt_cd":"1","msg_cd":"APBK0952","msg1":"주문가능금액을 초과 했습니다"}"""))

        val error = assertThrows<KisRejectedException> { server.kisApi(this).placeCashOrder(buy) }

        assertThat(error.httpStatus).isEqualTo(200)
        assertThat(error.msgCd).isEqualTo("APBK0952")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `주문 5xx 는 본문에 msg_cd 가 있어도 OutcomeUnknown 이고 한 번만 보낸다`() = runTest {
        server.enqueue(kisJson("""{"rt_cd":"1","msg_cd":"APBK0952","msg1":"주문가능금액을 초과 했습니다"}""", status = 500))

        val error = assertThrows<KisOutcomeUnknownException> { server.kisApi(this).placeCashOrder(buy) }

        assertThat(error.httpStatus).isEqualTo(500)
        assertThat(error.msgCd).isEqualTo("APBK0952")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `주문 EGW00201 은 HTTP 상태와 무관하게 OutcomeUnknown 이고 다시 보내지 않는다`() = runTest {
        server.enqueue(kisJson(throttled))
        server.enqueue(kisJson(throttled, status = 500))
        val api = server.kisApi(this)

        assertThrows<KisOutcomeUnknownException> { api.placeCashOrder(buy) }
        assertThrows<KisOutcomeUnknownException> { api.placeCashOrder(buy) }

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `주문 응답 시간 초과는 OutcomeUnknown 이고 한 번만 보낸다`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertThrows<KisOutcomeUnknownException> {
            server.kisApi(this, properties = KisApiProperties(responseTimeoutSeconds = 1)).placeCashOrder(buy)
        }

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `주문 rt_cd 0 인데 ODNO 가 없으면 OutcomeUnknown 이다`() = runTest {
        server.enqueue(kisJson("""{"rt_cd":"0","msg_cd":"APBK0013","msg1":"주문 전송 완료","output":{"ODNO":""}}"""))

        assertThrows<KisOutcomeUnknownException> { server.kisApi(this).placeCashOrder(buy) }

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `주문 토큰 오류는 토큰을 무효화만 하고 다시 보내지 않으며 비 2xx 면 결과 불명이다`() = runTest {
        val expired = """{"rt_cd":"1","msg_cd":"EGW00123","msg1":"기간이 만료된 token 입니다."}"""
        server.enqueue(kisJson(expired))
        server.enqueue(kisJson(expired, status = 500))
        val tokens = FakeTokens("tok-old", "tok-new", "tok-newer")
        val api = server.kisApi(this, tokens = tokens)

        assertThrows<KisRejectedException> { api.placeCashOrder(buy) }
        assertThrows<KisOutcomeUnknownException> { api.placeCashOrder(buy) }

        assertThat(tokens.invalidated).containsExactly("tok-old", "tok-new")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `예외 메시지에는 키·계좌·토큰·응답 원문이 없다`() = runTest {
        server.enqueue(kisJson("""{"rt_cd":"1","msg_cd":"APBK0952","msg1":"주문가능금액을 초과 했습니다","echo":"12345678"}""", status = 500))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val api = server.kisApi(this, properties = KisApiProperties(responseTimeoutSeconds = 1))

        val errors = listOf(
            assertThrows<KisOutcomeUnknownException> { api.placeCashOrder(buy) },
            assertThrows<KisOutcomeUnknownException> { api.placeCashOrder(buy) },
        )
        val messages = errors.map { it.message!! }
        assertThat(messages[1]).contains("WebClientRequestException")
        assertThat(errors[1].cause).isNull()
        assertThat(errors[1].suppressed).isEmpty()

        messages.forEach { message ->
            assertThat(message)
                .doesNotContain(TEST_ACCESS.appKey, TEST_ACCESS.appSecret, TEST_ACCESS.cano, "tok-1", "echo", "localhost")
        }
    }
}
