package com.trading.bot.config

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Component
class RateLimitFilter(
    private val redisTemplate: ReactiveRedisTemplate<String, String>?,
    private val clock: Clock = Clock.systemUTC(),
    // Redis 가 이 안에 답하지 않으면 in-memory 로 판정하고, 그 뒤 redisRetryDelay 동안은 Redis 를 부르지 않는다(#229).
    private val redisTimeout: Duration = Duration.ofMillis(500),
    private val redisRetryDelay: Duration = Duration.ofSeconds(30),
) : WebFilter {
    private val log = LoggerFactory.getLogger(javaClass)

    // Redis 가 없거나 장애일 때 fail-open 되지 않도록 in-memory fixed-window 로 판정한다 (단일 인스턴스 기준 유효).
    private val memoryHits = ConcurrentHashMap<String, Long>()
    private val memoryWindowMinute = AtomicLong(-1)

    // 0 이면 Redis 로 판정하고, 그 밖이면 이 시각(ms)까지 in-memory 로 판정한다(강등).
    private val redisRetryAtMs = AtomicLong(0)
    // 강등 중 지연이 지나면 한 요청만 Redis 를 다시 시도한다 — 나머지는 그 결과를 기다리지 않고 in-memory 로 판정한다.
    private val probing = AtomicBoolean(false)

    init {
        if (redisTemplate == null) {
            log.warn("Redis 미구성 — in-memory rate limiting 으로 대체. 다중 인스턴스 환경에선 Redis 필수.")
        }
    }

    companion object {
        private const val MAX_REQUESTS_PER_MINUTE = 60
        // Auth flows see natural retries on a mistyped password; 10/min was too tight for legitimate SPA use.
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

        // 키에 넣는 클라이언트 식별자는 client IP 뿐이다 — 요청이 스스로 주장하는 식별자는 넣지 않는다. IP 의 신뢰는 [clientIp] 의 전제
        // (Caddy 뒤 `deploy/*` 판)에 달려 있다: 루트 docker-compose 처럼 8080 을 직접 노출하면 XFF 도 위조된다.
        val minute = clock.millis() / 60000
        // 인증과 일반 API 는 한도가 다르므로 카운터도 따로 둔다 — 하나를 나눠 쓰면 폴링이 인증 한도를 대신 소모한다.
        val bucket = if (isAuthEndpoint) "auth" else "api"
        val key = "$KEY_PREFIX$bucket:$clientIp:$minute"

        val template = redisTemplate
        val degradedUntil = redisRetryAtMs.get()
        val probe = degradedUntil != 0L
        if (template == null || (probe && (clock.millis() < degradedUntil || !probing.compareAndSet(false, true)))) {
            return decide(exchange, chain, isMemoryRateLimited(key, minute, limit), limit)
        }

        // chain.filter 는 timeout·onErrorResume 밖에 둔다 — 하류의 오류·지연이 Redis 장애로 잡히면 요청이 두 번 처리된다.
        return redisCount(template, key)
            .map { count ->
                if (probe) recovered(degradedUntil)
                count > limit
            }
            .onErrorResume { error ->
                redisFailed(error, degradedUntil)
                Mono.just(isMemoryRateLimited(key, minute, limit))
            }
            // 클라이언트가 끊겨 시도가 취소돼도 자리를 돌려준다 — 안 그러면 다시는 Redis 를 시도하지 않는다.
            .doFinally { if (probe) probing.set(false) }
            .flatMap { limited -> decide(exchange, chain, limited, limit) }
    }

    // 공유 연결이 아직 없으면 연결 획득이 구독한 스레드에서 동기로 막힌다(Lettuce connect) — 요청을 처리하는 이벤트 루프를
    // 묶지 않게 worker 에서 구독한다. 연결이 끊긴 동안 Lettuce 는 명령을 쌓아 두고 명령 타임아웃까지 기다리므로 짧게 끊는다.
    // 끊을 때 진행 중인 호출은 취소하지 않는다(suppressCancel) — worker 를 interrupt 하면 Lettuce 가 connect 를 버려 주인 없는
    // 연결이 남는다. 남은 호출은 Lettuce 타임아웃(prod 1s) 안에 스스로 끝난다. 결과는 신호로 싸서(materialize) 넘긴다 —
    // 끊긴 뒤 예외로 끝난 future 는 Reactor 가 onErrorDropped ERROR(스택 포함)로 찍는다.
    private fun redisCount(template: ReactiveRedisTemplate<String, String>, key: String): Mono<Long> {
        val call = template.opsForValue().increment(key)
            .flatMap { count -> if (count == 1L) template.expire(key, WINDOW).thenReturn(count) else Mono.just(count) }
            .subscribeOn(Schedulers.boundedElastic())
            .materialize()
        return Mono.fromFuture({ call.toFuture() }, true)
            .timeout(redisTimeout)
            .dematerialize()
    }

    // 상태는 요청이 관측한 값에서만 바꾼다(compareAndSet). 다시 시도한 요청만 강등을 푼다 — 강등 전에 나간 요청이 늦게
    // 성공해도 장애 중인 Redis 로 되돌리지 않고, 그 사이 다른 시도가 이미 복귀시켰으면 아무것도 하지 않는다.
    private fun recovered(observed: Long) {
        if (redisRetryAtMs.compareAndSet(observed, 0)) log.info("Redis rate limit 복귀 — 다시 Redis 로 판정한다")
    }

    // 정상(0)에서의 실패만 강등하며 WARN 한 줄을 남긴다. 다시 시도의 실패는 지연만 다시 걸고, 강등 전에 나간 요청의 늦은
    // 실패나 이미 바뀐 상태를 본 시도는 아무것도 바꾸지 않는다.
    private fun redisFailed(error: Throwable, observed: Long) {
        val retryAt = clock.millis() + redisRetryDelay.toMillis()
        if (redisRetryAtMs.compareAndSet(observed, retryAt) && observed == 0L) {
            log.warn(
                "Redis rate limit 실패 — {}초 동안 in-memory 카운터로 판정하고 그 뒤 다시 시도한다: {}",
                redisRetryDelay.seconds, error.toString(),
            )
        }
    }

    private fun decide(exchange: ServerWebExchange, chain: WebFilterChain, limited: Boolean, limit: Int): Mono<Void> =
        if (limited) reject(exchange, limit) else chain.filter(exchange)

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
