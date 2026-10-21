package com.trading.bot.kis.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient

/** [KisApiImpl] 이 쓰는 토큰 출처. */
interface KisTokenSource {
    suspend fun token(): String

    /** [rejected] 가 지금 캐시된 토큰일 때만 버린다. 동시에 실패한 요청들이 막 새로 받은 토큰까지 지우지 않게 한다. */
    fun invalidate(rejected: String)
}

/**
 * (환경, 키 지문) 하나의 접근 토큰. 동시 요청은 발급을 한 번만 기다리고, 만료 [refreshSkew] 전에 새로 받는다.
 *
 * 발급 요청이 1분에 1회를 넘으면 KIS 가 막으므로, 마지막 발급 시도에서 [minIssueInterval] 이 지나기 전에는 다시 부르지 않는다.
 * 그 안에서는 아직 만료되지 않은 토큰을 그대로 쓰고, 그것도 없으면 [KisTransientException] 을 던진다.
 * 토큰과 발급 응답 원문은 로그·예외에 넣지 않는다.
 */
class KisTokenProvider(
    private val environment: KisEnvironment,
    private val access: KisAccess,
    private val webClient: WebClient,
    private val refreshSkew: Duration,
    private val minIssueInterval: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : KisTokenSource {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mutex = Mutex()
    private val cached = AtomicReference<Issued?>(null)
    private var lastAttempt: Instant? = null

    private class Issued(val value: String, val expiresAt: Instant)

    override suspend fun token(): String {
        fresh()?.let { return it }
        return mutex.withLock {
            fresh()?.let { return@withLock it }
            val now = clock.instant()
            val last = lastAttempt
            if (last != null && Duration.between(last, now) < minIssueInterval) {
                cached.get()?.takeIf { now.isBefore(it.expiresAt) }?.let { return@withLock it.value }
                throw KisTransientException(ENDPOINT, throttled = false, reason = "토큰 재발급 최소 간격 안")
            }
            lastAttempt = now
            // 첫 호출자가 취소돼도 발급은 끝까지 받아 둔다. 끊으면 mutex 를 기다리던 나머지가 최소 간격 동안 토큰 없이 실패한다.
            // 대기 상한은 WebClient 응답 시간 제한이 맡는다.
            withContext(NonCancellable) {
                val issued = issue(now)
                cached.set(issued)
                log.info("KIS token issued: env={} fingerprint={} expiresAt={}", environment, access.fingerprint, issued.expiresAt)
                issued.value
            }
        }
    }

    override fun invalidate(rejected: String) {
        val current = cached.get() ?: return
        if (current.value == rejected && cached.compareAndSet(current, null)) {
            log.warn("KIS token invalidated: env={} fingerprint={}", environment, access.fingerprint)
        }
    }

    private fun fresh(): String? {
        val current = cached.get() ?: return null
        return current.value.takeIf { clock.instant().plus(refreshSkew).isBefore(current.expiresAt) }
    }

    private suspend fun issue(now: Instant): Issued {
        val response = try {
            webClient.post()
                .uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    mapOf(
                        "grant_type" to "client_credentials",
                        "appkey" to access.appKey,
                        "appsecret" to access.appSecret,
                    ),
                )
                .exchangeToMono { it.toEntity(String::class.java) }
                .awaitSingle()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw KisTransientException(ENDPOINT, throttled = false, reason = "토큰 발급 전송 실패 (${e.javaClass.simpleName})")
        }
        val status = response.statusCode.value()
        val body = response.body?.let { runCatching { MAPPER.readTree(it) }.getOrNull() }
        if (!response.statusCode.is2xxSuccessful) {
            // OAuth 오류 본문은 msg_cd 대신 error_code 로 올 수 있다. error_description 은 원문이라 옮기지 않는다.
            val msgCd = body?.text("msg_cd") ?: body?.text("error_code")
            val msg1 = body?.text("msg1")
            if (msgCd == ISSUE_THROTTLED) {
                throw KisTransientException(ENDPOINT, throttled = true, reason = "토큰 발급 1분 1회 제한", httpStatus = status, msgCd = msgCd, msg1 = msg1)
            }
            if (response.statusCode.is5xxServerError) {
                throw KisTransientException(ENDPOINT, throttled = false, reason = "토큰 발급 실패", httpStatus = status, msgCd = msgCd, msg1 = msg1)
            }
            throw KisRejectedException(ENDPOINT, status, rtCd = null, msgCd = msgCd, msg1 = msg1, reason = "토큰 발급 거절")
        }
        body ?: throw KisMalformedResponseException(ENDPOINT, "본문이 JSON 이 아니다", status)
        val token = body.text("access_token") ?: throw KisMalformedResponseException(ENDPOINT, "access_token 없음", status)
        return Issued(token, expiresAt(body, now, status))
    }

    private fun expiresAt(body: JsonNode, now: Instant, status: Int): Instant {
        body.text("expires_in")?.toLongOrNull()?.takeIf { it > 0 }?.let { return now.plusSeconds(it) }
        val expired = body.text("access_token_token_expired")
            ?: throw KisMalformedResponseException(ENDPOINT, "expires_in·access_token_token_expired 없음", status)
        return try {
            LocalDateTime.parse(expired, EXPIRED_FORMAT).atZone(KST).toInstant()
        } catch (_: DateTimeParseException) {
            throw KisMalformedResponseException(ENDPOINT, "access_token_token_expired 형식", status)
        }
    }

    private fun JsonNode.text(field: String): String? =
        get(field)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }

    private companion object {
        const val PATH = "/oauth2/tokenP"
        const val ENDPOINT = "token"

        // 발급 1분 1회 초과. 오류 응답의 HTTP 상태·필드 이름은 공식 샘플에 없어 모의 스모크로 확인할 때까지 이 한 곳에만 둔다.
        const val ISSUE_THROTTLED = "EGW00133"
        val MAPPER = ObjectMapper()
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val EXPIRED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
