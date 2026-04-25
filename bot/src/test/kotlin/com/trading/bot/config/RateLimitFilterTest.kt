package com.trading.bot.config

import io.mockk.every
import io.mockk.mockk
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
}
