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
        // retries on validation failure; 10/min was too tight for legitimate SPA use.
        // Client IP is now resolved from Caddy's X-Forwarded-For (see clientIp()),
        // so 30/min is a per-client brute-force ceiling rather than a shared bucket.
        private const val MAX_AUTH_REQUESTS_PER_MINUTE = 30
        private const val KEY_PREFIX = "ratelimit:"
        private val WINDOW = Duration.ofMinutes(1)
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        if (isExcluded(path)) return chain.filter(exchange)

        val clientIp = clientIp(exchange)
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

    // Caddy(reverse proxy)가 X-Forwarded-For 를 자신이 본 실제 peer IP 로 덮어써 전달한다
    // (Caddyfile: `header_up X-Forwarded-For {remote_host}` — client 위조 방지). app 은
    // 호스트에 노출되지 않아(expose) 항상 Caddy 를 거치므로 XFF 를 신뢰할 수 있다. 직접
    // 노출되는 로컬 dev(Caddy 없음)에선 헤더가 없으므로 remoteAddress 로 fallback.
    private fun clientIp(exchange: ServerWebExchange): String {
        val forwarded = exchange.request.headers.getFirst("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) return forwarded.substringBefore(',').trim()
        return exchange.request.remoteAddress?.address?.hostAddress ?: "anonymous"
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
