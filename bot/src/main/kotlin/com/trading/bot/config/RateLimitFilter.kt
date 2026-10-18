package com.trading.bot.config

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@Component
class RateLimitFilter(
    private val clock: Clock = Clock.systemUTC(),
) : WebFilter {

    // 카운터가 프로세스 메모리에 있어 단일 인스턴스 기준이다.
    private class Window(val minute: Long) {
        val hits = ConcurrentHashMap<String, Long>()
    }
    private val window = AtomicReference(Window(-1))

    companion object {
        private const val MAX_REQUESTS_PER_MINUTE = 60
        // 클라이언트 IP 별 무차별 대입 상한이다.
        private const val MAX_AUTH_REQUESTS_PER_MINUTE = 30
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        if (isExcluded(path)) return chain.filter(exchange)

        val isAuthEndpoint = path.startsWith("/api/auth")
        val limit = if (isAuthEndpoint) MAX_AUTH_REQUESTS_PER_MINUTE else MAX_REQUESTS_PER_MINUTE

        // 한 카운터를 나눠 쓰면 일반 폴링이 인증 한도를 소모하므로 따로 센다.
        val bucket = if (isAuthEndpoint) "auth" else "api"
        return if (isRateLimited("$bucket:${clientIp(exchange)}", limit)) reject(exchange, limit) else chain.filter(exchange)
    }

    // Caddy 가 X-Forwarded-For 를 실제 peer IP 로 덮어쓰므로 Caddy 뒤에서만 이 헤더를 믿을 수 있다.
    // 앱 포트를 직접 노출하면 클라이언트가 헤더를 위조할 수 있다.
    private fun clientIp(exchange: ServerWebExchange): String {
        val forwarded = exchange.request.headers.getFirst("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) return forwarded.substringBefore(',').trim()
        return exchange.request.remoteAddress?.address?.hostAddress ?: "anonymous"
    }

    private fun isExcluded(path: String): Boolean =
        path.startsWith("/actuator") ||
            path.startsWith("/css") || path.startsWith("/js") ||
            path.startsWith("/tide-app") || path == "/" ||
            path.endsWith(".html")

    // 1분 고정 창. 직전 분의 늦은 요청은 지금 창에 세서 창이 되돌아가지 않게 하고, 시계가 크게 뒤로 가면
    // 창이 미래에 묶이지 않도록 새 창을 연다.
    private fun isRateLimited(key: String, limit: Int): Boolean {
        val minute = clock.millis() / 60000
        val current = window.updateAndGet { if (minute == it.minute || minute == it.minute - 1) it else Window(minute) }
        val count = current.hits.merge(key, 1L) { a, b -> a + b } ?: 1L
        return count > limit
    }

    internal fun trackedKeys(): Int = window.get().hits.size

    private fun reject(exchange: ServerWebExchange, limit: Int): Mono<Void> {
        exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        exchange.response.headers.set("X-RateLimit-Limit", limit.toString())
        exchange.response.headers.set("Retry-After", "60")
        return exchange.response.setComplete()
    }
}
