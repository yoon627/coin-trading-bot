package com.trading.bot.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Duration

class RateLimitFilterTest {

    @Test
    fun `filter passes through when redis is null`() {
        val filter = RateLimitFilter(null)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/bot/status").build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        // Should have called chain.filter (pass-through)
        io.mockk.verify { chain.filter(exchange) }
    }

    @Test
    fun `filter skips actuator endpoints`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/actuator/health").build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        io.mockk.verify { chain.filter(exchange) }
        // Should NOT call redis
        io.mockk.verify(exactly = 0) { redisTemplate.opsForValue() }
    }

    @Test
    fun `filter applies stricter rate limit to auth endpoints`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.increment(any()) } returns Mono.just(31L) // exceeds auth limit (30)
        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/login").build()
        )
        val chain = mockk<WebFilterChain>()

        filter.filter(exchange, chain).block()

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.response.statusCode)
    }

    @Test
    fun `filter skips price stream endpoints`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/prices/stream").build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        io.mockk.verify { chain.filter(exchange) }
    }

    @Test
    fun `filter skips tide-app static bundle paths`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val filter = RateLimitFilter(redisTemplate)
        for (path in listOf("/tide-app/api.js", "/tide-app/screens.jsx", "/tide-app/tokens.css", "/tide-app/ui.jsx")) {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build())
            val chain = mockk<WebFilterChain>()
            every { chain.filter(exchange) } returns Mono.empty()

            filter.filter(exchange, chain).block()

            io.mockk.verify { chain.filter(exchange) }
        }
    }

    @Test
    fun `filter allows requests within rate limit`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.increment(any()) } returns Mono.just(5L) // 5th request
        every { redisTemplate.expire(any(), any<Duration>()) } returns Mono.just(true)

        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/bot/status").build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        io.mockk.verify { chain.filter(exchange) }
    }

    @Test
    fun `filter returns 429 when rate limit exceeded`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.increment(any()) } returns Mono.just(61L) // exceeded

        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/bot/status").build()
        )
        val chain = mockk<WebFilterChain>()

        filter.filter(exchange, chain).block()

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.response.statusCode)
    }

    @Test
    fun `filter sets expire on first request`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.increment(any()) } returns Mono.just(1L) // first request
        every { redisTemplate.expire(any(), any<Duration>()) } returns Mono.just(true)

        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/trades").build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        io.mockk.verify { redisTemplate.expire(any(), Duration.ofMinutes(1)) }
    }

    @Test
    fun `filter uses X-Forwarded-For client ip for rate limit key`() {
        // Caddy(reverse proxy) 뒤에선 remoteAddress 가 Caddy 컨테이너 IP 하나로 뭉치므로,
        // 실제 client 식별은 Caddy 가 부여한 X-Forwarded-For 로 해야 IP별 rate limit 이 동작한다.
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        val keySlot = slot<String>()
        every { valueOps.increment(capture(keySlot)) } returns Mono.just(1L)
        every { redisTemplate.expire(any(), any<Duration>()) } returns Mono.just(true)

        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/login")
                .header("X-Forwarded-For", "203.0.113.5")
                .build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        assertTrue(
            keySlot.captured.contains("203.0.113.5"),
            "rate limit key 에 XFF client IP 가 반영돼야 함: ${keySlot.captured}",
        )
    }

    @Test
    fun `filter keys non-auth requests by client ip and ignores a client-supplied X-User-Id`() {
        // 이 헤더를 설정하는 서버 구성요소는 없다 — 클라이언트가 보낸 값을 키로 쓰면 값만 바꿔 버킷을 무한히 새로 받는다.
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        val keys = mutableListOf<String>()
        every { valueOps.increment(capture(keys)) } returns Mono.just(1L)
        every { redisTemplate.expire(any(), any<Duration>()) } returns Mono.just(true)
        val filter = RateLimitFilter(redisTemplate)

        for (spoofed in listOf("alice", "bob")) {
            val exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/trades")
                    .header("X-Forwarded-For", "203.0.113.5")
                    .header("X-User-Id", spoofed)
                    .build()
            )
            val chain = mockk<WebFilterChain>()
            every { chain.filter(exchange) } returns Mono.empty()
            filter.filter(exchange, chain).block()
        }

        assertEquals(1, keys.map { it.substringBeforeLast(':') }.distinct().size, "헤더 값이 달라도 같은 버킷이어야 함: $keys")
        assertTrue(keys.all { it.contains("203.0.113.5") }, "버킷 키는 client IP: $keys")
    }

    // 인증(30/min)과 일반 API(60/min)가 카운터 하나를 나눠 쓰면, 같은 IP 의 폴링이 1분에 30회를 넘을 때(한 화면은
    // 약 20/min 이라 탭 2개·NAT 공유에서) 같은 분의 로그아웃·재로그인이 자기 한도를 하나도 안 썼는데 429 가 된다.
    @Test
    fun `api traffic does not consume the auth bucket of the same client`() {
        val filter = RateLimitFilter(null)
        fun send(method: String, path: String): HttpStatus? {
            val request = if (method == "POST") MockServerHttpRequest.post(path) else MockServerHttpRequest.get(path)
            val exchange = MockServerWebExchange.from(request.header("X-Forwarded-For", "203.0.113.9").build())
            val chain = mockk<WebFilterChain>()
            every { chain.filter(exchange) } returns Mono.empty()
            filter.filter(exchange, chain).block()
            return exchange.response.statusCode as HttpStatus?
        }

        repeat(40) { assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, send("GET", "/api/trades"), "일반 API 40회는 60 한도 안") }

        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, send("POST", "/api/auth/login"), "인증 버킷은 일반 API 호출에 소모되지 않는다")
    }

    // 위 재현은 벽시계에 묶여 있다(41요청 사이 분이 바뀌면 카운터가 비어 회귀를 놓친다) — 키 자체를 단정해 시간과 무관하게,
    // 운영이 실제로 타는 Redis 경로에서 분리를 고정한다.
    @Test
    fun `auth and api requests of one client use different redis keys`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        val keys = mutableListOf<String>()
        every { valueOps.increment(capture(keys)) } returns Mono.just(1L)
        every { redisTemplate.expire(any(), any<Duration>()) } returns Mono.just(true)
        val filter = RateLimitFilter(redisTemplate)

        for (request in listOf(MockServerHttpRequest.get("/api/trades"), MockServerHttpRequest.post("/api/auth/login"))) {
            val exchange = MockServerWebExchange.from(request.header("X-Forwarded-For", "203.0.113.9").build())
            val chain = mockk<WebFilterChain>()
            every { chain.filter(exchange) } returns Mono.empty()
            filter.filter(exchange, chain).block()
        }

        assertTrue(keys[0].startsWith("ratelimit:api:203.0.113.9:"), keys.toString())
        assertTrue(keys[1].startsWith("ratelimit:auth:203.0.113.9:"), keys.toString())
    }

    @Test
    fun `filter takes first ip from X-Forwarded-For chain`() {
        // XFF 가 "client, proxy.." 체인일 때 원 client(첫 항목)를 식별자로 사용.
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        val keySlot = slot<String>()
        every { valueOps.increment(capture(keySlot)) } returns Mono.just(1L)
        every { redisTemplate.expire(any(), any<Duration>()) } returns Mono.just(true)

        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/login")
                .header("X-Forwarded-For", "203.0.113.5, 10.0.0.1")
                .build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        assertTrue(
            keySlot.captured.contains("203.0.113.5"),
            "체인의 첫 IP 를 써야 함: ${keySlot.captured}",
        )
    }
}
