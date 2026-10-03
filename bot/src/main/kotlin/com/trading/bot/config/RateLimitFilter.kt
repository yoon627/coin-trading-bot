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

    // 카운터는 프로세스 메모리에 있다 — 인스턴스마다 따로 세므로 단일 인스턴스 기준이다.
    private class Window(val minute: Long) {
        val hits = ConcurrentHashMap<String, Long>()
    }
    private val window = AtomicReference(Window(-1))

    companion object {
        private const val MAX_REQUESTS_PER_MINUTE = 60
        // 인증 경로는 비밀번호 오타로 재시도가 잦아 정상 사용에도 여유가 필요하다. [clientIp] 로 클라이언트마다 따로 세므로
        // 이 값은 공유 버킷이 아니라 클라이언트별 무차별 대입 상한이다.
        private const val MAX_AUTH_REQUESTS_PER_MINUTE = 30
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        if (isExcluded(path)) return chain.filter(exchange)

        val isAuthEndpoint = path.startsWith("/api/auth")
        val limit = if (isAuthEndpoint) MAX_AUTH_REQUESTS_PER_MINUTE else MAX_REQUESTS_PER_MINUTE

        // 키에 넣는 클라이언트 식별자는 client IP 뿐이다 — 요청이 스스로 주장하는 식별자는 넣지 않는다. IP 의 신뢰는 [clientIp] 의 전제
        // (Caddy 뒤 `deploy/*` 판)에 달려 있다: 루트 docker-compose 처럼 8080 을 직접 노출하면 XFF 도 위조된다.
        // 인증과 일반 API 는 한도가 다르므로 카운터도 따로 둔다 — 하나를 나눠 쓰면 폴링이 인증 한도를 대신 소모한다.
        val bucket = if (isAuthEndpoint) "auth" else "api"
        return if (isRateLimited("$bucket:${clientIp(exchange)}", limit)) reject(exchange, limit) else chain.filter(exchange)
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
            path.endsWith(".html")

    // 1분 고정 창(UTC 정분 버킷). 지금 창의 분이거나 그 직전 분(정분 직전에 분을 계산한 늦은 요청)이면 지금 창에 센다 — 창을
    // 되돌리면 지금 분 카운터가 지워진다. 그 밖(다음 분, 또는 벽시계가 뒤로 가 두 분 이상 이전인 분)은 새 창으로 바꾼다 — 창이
    // 미래 분에 멈추면 시계가 따라잡을 때까지 잠긴다. 지난 창은 통째로 버려지므로 카운터는 한 창 분량만 남는다.
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
