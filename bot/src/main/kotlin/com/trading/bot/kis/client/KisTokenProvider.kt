package com.trading.bot.kis.client

import com.trading.bot.kis.domain.KisTokenResponse
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Clock
import java.time.Instant

/**
 * KIS access_token 발급/캐싱. 토큰은 24h 유효하고 연속 재발급이 차단되므로 캐싱 필수(plan D2).
 * (앱키 1개 = 사용자 1명)별로 인스턴스가 생성된다 — [KisClientFactory] 소유. thread-safe.
 */
class KisTokenProvider(
    private val webClient: WebClient,
    private val appKey: String,
    private val appSecret: String,
    private val refreshSkewSeconds: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mutex = Mutex()

    @Volatile
    private var cached: Cached? = null

    private data class Cached(val token: String, val expiresAt: Instant)

    suspend fun token(): String {
        cached?.let { if (!isExpiring(it)) return it.token }
        return mutex.withLock {
            // double-check: 락 대기 중 다른 코루틴이 발급했을 수 있음.
            cached?.let { if (!isExpiring(it)) return it.token }
            val resp = issue()
            cached = Cached(resp.accessToken, Instant.now(clock).plusSeconds(resp.expiresIn))
            log.info("KIS token issued (expires_in={}s)", resp.expiresIn)
            resp.accessToken
        }
    }

    /** 만료까지 남은 시간이 skew 이하면 갱신 대상. */
    private fun isExpiring(c: Cached): Boolean =
        !Instant.now(clock).plusSeconds(refreshSkewSeconds).isBefore(c.expiresAt)

    private suspend fun issue(): KisTokenResponse {
        val body = mapOf(
            "grant_type" to "client_credentials",
            "appkey" to appKey,
            "appsecret" to appSecret,
        )
        return webClient.post()
            .uri("/oauth2/tokenP")
            .bodyValue(body)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { resp ->
                resp.bodyToMono<String>().defaultIfEmpty("(no body)").map { b ->
                    KisApiException(
                        definitiveReject = false,
                        statusCode = resp.statusCode().value(),
                        rawBody = b,
                        msg = "KIS token issue failed",
                    )
                }
            }
            .bodyToMono<KisTokenResponse>()
            .awaitSingle()
    }
}
