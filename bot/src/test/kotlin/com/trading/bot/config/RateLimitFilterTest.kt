package com.trading.bot.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

// 시계를 고정해 한 분 안에서 센다 — 벽시계를 쓰면 요청 사이에 분이 바뀌어 카운터가 비고 회귀를 놓친다.
class RateLimitFilterTest {

    private class MutableClock(var now: Instant = Instant.parse("2026-09-30T00:00:10Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private class Sent(val exchange: MockServerWebExchange, val chainCalls: Int) {
        val limited: Boolean get() = exchange.response.statusCode == HttpStatus.TOO_MANY_REQUESTS
        fun header(name: String): String? = exchange.response.headers.getFirst(name)
    }

    private val clock = MutableClock()
    private val filter = RateLimitFilter(clock)

    private fun send(
        path: String = "/api/bot/status",
        method: HttpMethod = HttpMethod.GET,
        xff: String? = "203.0.113.7",
        remote: InetSocketAddress? = null,
        userId: String? = null,
    ): Sent {
        val request = MockServerHttpRequest.method(method, path)
        xff?.let { request.header("X-Forwarded-For", it) }
        remote?.let { request.remoteAddress(it) }
        userId?.let { request.header("X-User-Id", it) }
        val exchange = MockServerWebExchange.from(request.build())
        val calls = AtomicInteger()
        filter.filter(exchange) { calls.incrementAndGet(); Mono.empty() }.block()
        return Sent(exchange, calls.get())
    }

    @Test
    fun `일반 API 는 IP 마다 분당 60회까지 통과하고 61번째는 하류에 닿지 않고 429 와 한도 헤더를 받는다`() {
        repeat(60) { assertEquals(1, send().chainCalls, "${it + 1}번째는 한도 안") }

        val rejected = send()

        assertTrue(rejected.limited)
        assertEquals(0, rejected.chainCalls)
        assertEquals("60", rejected.header("X-RateLimit-Limit"))
        assertEquals("60", rejected.header("Retry-After"))
    }

    @Test
    fun `인증 경로는 분당 30회까지다`() {
        repeat(30) { assertEquals(1, send("/api/auth/login", HttpMethod.POST).chainCalls, "${it + 1}번째는 한도 안") }

        val rejected = send("/api/auth/login", HttpMethod.POST)

        assertTrue(rejected.limited)
        assertEquals(0, rejected.chainCalls)
        assertEquals("30", rejected.header("X-RateLimit-Limit"))
    }

    // 인증과 일반 API 가 카운터 하나를 나눠 쓰면, 같은 IP 의 폴링이 1분에 30회를 넘을 때(한 화면은 약 20/min 이라 탭 2개·NAT
    // 공유에서) 같은 분의 로그아웃·재로그인이 자기 한도를 하나도 안 썼는데 429 가 된다.
    @Test
    fun `일반 API 를 다 써도 같은 IP 의 인증 한도는 남는다`() {
        repeat(61) { send() }

        assertEquals(1, send("/api/auth/login", HttpMethod.POST).chainCalls)
    }

    // Caddy 가 X-Forwarded-For 를 자신이 본 peer IP 로 덮어써 넘긴다(deploy/vultr/Caddyfile). 체인이면 첫 항목이 클라이언트다.
    @Test
    fun `클라이언트는 X-Forwarded-For 의 첫 값으로 가른다`() {
        repeat(60) { send(xff = "203.0.113.5") }

        assertTrue(send(xff = "203.0.113.5, 10.0.0.1").limited, "체인의 첫 값이 같으면 같은 클라이언트")
        assertFalse(send(xff = "203.0.113.6").limited, "다른 IP 는 따로 센다")
    }

    @Test
    fun `X-Forwarded-For 가 없거나 비면 접속 주소로, 그것도 없으면 한 버킷으로 가른다`() {
        val a = InetSocketAddress("198.51.100.1", 50000)
        val b = InetSocketAddress("198.51.100.2", 50000)
        repeat(60) { send(xff = null, remote = a) }

        assertTrue(send(xff = " ", remote = a).limited, "공백 XFF 는 접속 주소로 판정")
        assertFalse(send(xff = null, remote = b).limited, "다른 접속 주소는 따로 센다")

        repeat(60) { send(xff = null) }
        assertTrue(send(xff = null).limited, "주소를 모르면 anonymous 한 버킷")
    }

    // 이 헤더를 설정하는 서버 구성요소는 없다 — 클라이언트가 보낸 값을 키로 쓰면 값만 바꿔 버킷을 무한히 새로 받는다.
    @Test
    fun `클라이언트가 보낸 X-User-Id 는 버킷을 가르지 않는다`() {
        repeat(60) { send(userId = "user-$it") }

        assertTrue(send(userId = "someone-else").limited)
    }

    @Test
    fun `다음 분이 되면 다시 통과한다`() {
        repeat(61) { send() }

        clock.now = clock.now.plusSeconds(60)

        assertFalse(send().limited)
    }

    // 정분 경계 직전에 분을 계산한 요청이 늦게 도착하는 경우다.
    @Test
    fun `이전 분을 계산한 늦은 요청이 현재 분 카운터를 비우지 않는다`() {
        repeat(60) { send() }

        clock.now = clock.now.minusSeconds(60)
        send()
        clock.now = clock.now.plusSeconds(60)

        assertTrue(send().limited)
    }

    // 벽시계는 단조 증가가 아니다(NTP step 등). 창이 미래 분에 멈추면 시계가 따라잡을 때까지 같은 IP 가 429 로 잠긴다.
    // 직전 분까지만 늦은 요청으로 보므로, 두 분 이전으로 가는 가장 작은 역행에서 새 창이 열려야 한다.
    @Test
    fun `벽시계가 두 분 이상 이전 분으로 가면 창을 새로 시작해 잠그지 않는다`() {
        repeat(61) { send() }

        clock.now = clock.now.minusSeconds(2 * 60)

        assertFalse(send().limited)
    }

    @Test
    fun `지난 분 카운터는 남기지 않는다`() {
        for (ip in listOf("203.0.113.1", "203.0.113.2", "203.0.113.3")) send(xff = ip)
        clock.now = clock.now.plusSeconds(60)

        send(xff = "203.0.113.4")

        assertEquals(1, filter.trackedKeys())
    }

    @Test
    fun `actuator·정적 자원·SPA 진입 경로는 한도를 다 써도 통과한다`() {
        repeat(61) { send() }

        for (path in listOf("/actuator/health", "/css/app.css", "/js/app.js", "/tide-app/api.js", "/", "/index.html")) {
            assertEquals(1, send(path).chainCalls, path)
        }
    }
}
