package com.trading.bot.config

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Component
class RateLimitFilter(
    private val redisTemplate: ReactiveRedisTemplate<String, String>?,
) : WebFilter {
    private val log = LoggerFactory.getLogger(javaClass)

    // Redis 부재 시 fail-open 되지 않도록 in-memory fixed-window 로 대체 (단일 인스턴스 기준 유효).
    private val memoryHits = ConcurrentHashMap<String, Long>()
    private val memoryWindowMinute = AtomicLong(-1)

    init {
        if (redisTemplate == null) {
            log.warn("Redis 미구성 — in-memory rate limiting 으로 대체. 다중 인스턴스 환경에선 Redis 필수.")
        }
    }

    companion object {
        private const val MAX_REQUESTS_PER_MINUTE = 60
        // Auth flows include register+login pairs (2 calls/success) and natural
        // retries on validation failure; 10/min was too tight for legitimate SPA
        // use, especially since all browser traffic shares one client IP behind
        // Docker NAT. Brute-force defense at 30/min is still meaningful.
        private const val MAX_AUTH_REQUESTS_PER_MINUTE = 30
        private const val KEY_PREFIX = "ratelimit:"
        private val WINDOW = Duration.ofMinutes(1)
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        if (isExcluded(path)) return chain.filter(exchange)

        val clientIp = exchange.request.remoteAddress?.address?.hostAddress ?: "anonymous"
        val isAuthEndpoint = path.startsWith("/api/auth")
        val limit = if (isAuthEndpoint) MAX_AUTH_REQUESTS_PER_MINUTE else MAX_REQUESTS_PER_MINUTE

        val userId = if (isAuthEndpoint) clientIp
            else exchange.request.headers.getFirst("X-User-Id") ?: clientIp

        val minute = System.currentTimeMillis() / 60000
        val key = "$KEY_PREFIX$userId:$minute"

        if (redisTemplate == null) {
            return if (isMemoryRateLimited(key, minute, limit)) reject(exchange, limit)
            else chain.filter(exchange)
        }

        return redisTemplate.opsForValue().increment(key)
            .flatMap { count ->
                when {
                    count == 1L -> redisTemplate.expire(key, WINDOW).then(chain.filter(exchange))
                    count <= limit -> chain.filter(exchange)
                    else -> reject(exchange, limit)
                }
            }
    }

    private fun isExcluded(path: String): Boolean =
        path.startsWith("/actuator") ||
            path.startsWith("/css") || path.startsWith("/js") ||
            path.startsWith("/tide-app") || path == "/" ||
            path.endsWith(".html") || path.startsWith("/api/prices")

    private fun isMemoryRateLimited(key: String, minute: Long, limit: Int): Boolean {
        // 분(window)이 바뀌면 카운터 초기화 → 맵 크기를 한 window 분량으로 제한.
        if (memoryWindowMinute.getAndSet(minute) != minute) {
            memoryHits.clear()
        }
        val count = memoryHits.merge(key, 1L) { a, b -> a + b } ?: 1L
        return count > limit
    }

    private fun reject(exchange: ServerWebExchange, limit: Int): Mono<Void> {
        exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        exchange.response.headers.set("X-RateLimit-Limit", limit.toString())
        exchange.response.headers.set("Retry-After", "60")
        return exchange.response.setComplete()
    }
}
