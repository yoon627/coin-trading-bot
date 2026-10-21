package com.trading.bot.kis.client

import com.trading.bot.kis.config.KisApiProperties
import com.trading.bot.kis.config.KisExitParamRanges
import com.trading.bot.kis.config.KisTradingProperties
import com.trading.bot.kis.config.atLeast
import com.trading.bot.kis.config.within
import io.netty.channel.ChannelOption
import jakarta.annotation.PreDestroy
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/**
 * (환경, 키 지문) 마다 토큰 제공자와 리미터를 하나씩 두고, 같은 키로 만든 [KisApi] 가 그것을 나눠 쓴다.
 * 엔진과 조회 API 가 같은 인스턴스를 받아야 KIS 의 토큰 발급·초당 건수 한도를 함께 지킬 수 있다.
 * 키가 바뀌면 지문이 달라 새 세션을 만든다. 맵 키·로그에는 지문만 쓴다.
 *
 * 설정 오류가 있어도 빈 생성은 실패시키지 않는다 — 앱 기동이 막히면 Upbit 보유 포지션의 손절도 평가되지 않는다.
 * 오류는 생성 때 ERROR 로 한 번 알리고, KIS 클라이언트를 달라는 호출만 [IllegalStateException] 으로 거절한다.
 */
@Component
@ConditionalOnProperty(prefix = "kis.trading", name = ["enabled"], havingValue = "true")
class KisClientRegistry(
    private val trading: KisTradingProperties,
    private val api: KisApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class SessionKey(val environment: KisEnvironment, val fingerprint: String)

    internal class Session(
        val webClient: WebClient,
        val tokens: KisTokenProvider,
        val limiter: KisRateLimiter,
    ) {
        val apis = HashMap<String, KisApi>()
    }

    // 세션 생성·evict·close 를 직렬화해, 닫힌 리미터에 묶인 KisApi 를 새로 내주거나 close 뒤에 세션이 남지 않게 한다.
    private val lock = Any()
    private val sessions = HashMap<SessionKey, Session>()
    private val webClients = HashMap<KisEnvironment, WebClient>()
    private var closed = false

    private val problems: List<String> = configurationProblems(trading, api)

    init {
        if (problems.isNotEmpty()) log.error("KIS 설정 오류 — KIS 클라이언트를 만들지 않는다: {}", problems.joinToString("; "))
    }

    fun api(environment: KisEnvironment, access: KisAccess): KisApi = synchronized(lock) {
        val session = session(environment, access)
        session.apis.getOrPut(access.accountFingerprint) {
            KisApiImpl(environment, access, session.webClient, session.tokens, session.limiter, api)
        }
    }

    /**
     * 키 교체(옛 지문 폐기) 전용이다. 그 세션의 리미터를 닫고 토큰 제공자와 함께 지운다 — 기다리던 요청은 바로 실패한다.
     * evict 전에 받은 [KisApi] 는 닫힌 리미터를 쥐고 있어 계속 실패하므로 [api] 로 다시 받아야 한다.
     * 같은 키로 다시 받으면 토큰을 새로 발급하는데, 직전 발급에서 1분이 지나지 않았으면 KIS 가 막는다.
     * 그래서 사용자 정지·재시작이나 설정 reload 에는 쓰지 않는다. 앱 정지는 [close] 가 맡는다.
     */
    fun evict(environment: KisEnvironment, access: KisAccess) {
        val removed = synchronized(lock) { sessions.remove(SessionKey(environment, access.fingerprint)) } ?: return
        removed.limiter.close()
        log.info("KIS session evicted: env={} fingerprint={}", environment, access.fingerprint)
    }

    @PreDestroy
    fun close() {
        val all = synchronized(lock) {
            closed = true
            sessions.values.toList().also { sessions.clear() }
        }
        all.forEach { it.limiter.close() }
    }

    /** [api] 밖에서는 리미터를 거치지 않는 관측(모의 스모크의 유량 초과 확인)에만 쓴다. */
    internal fun session(environment: KisEnvironment, access: KisAccess): Session = synchronized(lock) {
        check(problems.isEmpty()) { "KIS 설정 오류: ${problems.joinToString("; ")}" }
        check(!closed) { "KisClientRegistry 가 닫혔다" }
        check(environment != KisEnvironment.LIVE || trading.liveEnabled) { "kis.trading.live-enabled=false — 실전 클라이언트를 만들지 않는다" }
        sessions.getOrPut(SessionKey(environment, access.fingerprint)) {
            val webClient = webClients.getOrPut(environment) { buildWebClient(environment.baseUrl(api), api) }
            log.info("KIS session created: env={} fingerprint={}", environment, access.fingerprint)
            Session(
                webClient = webClient,
                tokens = KisTokenProvider(
                    environment, access, webClient,
                    refreshSkew = Duration.ofSeconds(api.tokenRefreshSkewSeconds),
                    minIssueInterval = Duration.ofSeconds(api.tokenMinIssueIntervalSeconds),
                ),
                limiter = KisRateLimiter(
                    minIntervalMs = when (environment) {
                        KisEnvironment.PAPER -> trading.paperMinRequestIntervalMs
                        KisEnvironment.LIVE -> trading.liveMinRequestIntervalMs
                    },
                    shortWaitLimitMs = api.shortWaitLimitMs,
                    longWaitLimitMs = api.longWaitLimitMs,
                ),
            )
        }
    }

    companion object {
        private const val MAX_IN_MEMORY_BYTES = 1024 * 1024
        private const val MAX_QUERY_ATTEMPTS = 10L
        private const val MAX_INITIAL_BACKOFF_MS = 10_000L

        internal fun buildWebClient(baseUrl: String, api: KisApiProperties): WebClient {
            val httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, api.connectTimeoutMs)
                .responseTimeout(Duration.ofSeconds(api.responseTimeoutSeconds))
            return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(ReactorClientHttpConnector(httpClient))
                .codecs { it.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES) }
                .build()
        }

        internal fun configurationProblems(trading: KisTradingProperties, api: KisApiProperties): List<String> = buildList {
            addAll(KisExitParamRanges.problems(trading))
            if (api.paperBaseUrl.isBlank()) add("kis.api.paper-base-url 이 비었다")
            if (api.liveBaseUrl.isBlank()) add("kis.api.live-base-url 이 비었다")
            if (api.custType.isBlank()) add("kis.api.cust-type 이 비었다")
            atLeast("kis.api.connect-timeout-ms", api.connectTimeoutMs.toLong(), 1)
            atLeast("kis.api.response-timeout-seconds", api.responseTimeoutSeconds, 1)
            atLeast("kis.api.token-refresh-skew-seconds", api.tokenRefreshSkewSeconds, 0)
            // KIS 는 토큰 발급을 1분에 1회까지만 받는다.
            atLeast("kis.api.token-min-issue-interval-seconds", api.tokenMinIssueIntervalSeconds, 60)
            // backoff 를 시프트로 두 배씩 늘리므로 횟수가 크면 대기가 넘친다.
            within("kis.api.query-max-attempts", api.queryMaxAttempts.toLong(), 1, MAX_QUERY_ATTEMPTS)
            within("kis.api.query-retry-initial-backoff-ms", api.queryRetryInitialBackoffMs, 0, MAX_INITIAL_BACKOFF_MS)
            atLeast("kis.api.short-wait-limit-ms", api.shortWaitLimitMs, 1)
            atLeast("kis.api.long-wait-limit-ms", api.longWaitLimitMs, 1)
            atLeast("kis.api.max-continuation-pages", api.maxContinuationPages.toLong(), 1)
        }
    }
}
