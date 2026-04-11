package com.trading.bot.config

import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class RateLimitFilter(
    private val redisTemplate: ReactiveRedisTemplate<String, String>?,
) : WebFilter {

    companion object {
        private const val MAX_REQUESTS_PER_MINUTE = 60
        private const val KEY_PREFIX = "ratelimit:"
        private val WINDOW = Duration.ofMinutes(1)
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        // Skip rate limiting if Redis is not configured
        if (redisTemplate == null) return chain.filter(exchange)

        val path = exchange.request.path.value()
        // Skip rate limiting for health/prometheus/static/auth endpoints
        if (path.startsWith("/actuator") || path.startsWith("/api/auth") ||
            path.startsWith("/css") || path.startsWith("/js") || path == "/" ||
            path.endsWith(".html") || path.startsWith("/api/prices")
        ) {
            return chain.filter(exchange)
        }

        val userId = exchange.request.headers.getFirst("X-User-Id")
            ?: exchange.request.remoteAddress?.address?.hostAddress
            ?: "anonymous"

        val key = "$KEY_PREFIX$userId:${System.currentTimeMillis() / 60000}"

        return redisTemplate.opsForValue().increment(key)
            .flatMap { count ->
                if (count == 1L) {
                    redisTemplate.expire(key, WINDOW).then(chain.filter(exchange))
                } else if (count <= MAX_REQUESTS_PER_MINUTE) {
                    chain.filter(exchange)
                } else {
                    exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                    exchange.response.headers.set("X-RateLimit-Limit", MAX_REQUESTS_PER_MINUTE.toString())
                    exchange.response.headers.set("Retry-After", "60")
                    exchange.response.setComplete()
                }
            }
    }
}
