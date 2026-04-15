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
        private const val MAX_AUTH_REQUESTS_PER_MINUTE = 10
        private const val KEY_PREFIX = "ratelimit:"
        private val WINDOW = Duration.ofMinutes(1)
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (redisTemplate == null) return chain.filter(exchange)

        val path = exchange.request.path.value()
        // 정적 리소스와 헬스체크만 Rate Limiting 제외
        if (path.startsWith("/actuator") ||
            path.startsWith("/css") || path.startsWith("/js") || path == "/" ||
            path.endsWith(".html") || path.startsWith("/api/prices")
        ) {
            return chain.filter(exchange)
        }

        val clientIp = exchange.request.remoteAddress?.address?.hostAddress ?: "anonymous"
        val isAuthEndpoint = path.startsWith("/api/auth")
        val limit = if (isAuthEndpoint) MAX_AUTH_REQUESTS_PER_MINUTE else MAX_REQUESTS_PER_MINUTE

        val userId = if (isAuthEndpoint) clientIp
            else exchange.request.headers.getFirst("X-User-Id") ?: clientIp

        val key = "$KEY_PREFIX$userId:${System.currentTimeMillis() / 60000}"

        return redisTemplate.opsForValue().increment(key)
            .flatMap { count ->
                if (count == 1L) {
                    redisTemplate.expire(key, WINDOW).then(chain.filter(exchange))
                } else if (count <= limit) {
                    chain.filter(exchange)
                } else {
                    exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                    exchange.response.headers.set("X-RateLimit-Limit", limit.toString())
                    exchange.response.headers.set("Retry-After", "60")
                    exchange.response.setComplete()
                }
            }
    }
}
