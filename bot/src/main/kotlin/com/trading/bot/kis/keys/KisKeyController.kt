package com.trading.bot.kis.keys

import com.trading.bot.auth.currentUserId
import com.trading.common.domain.Exchange
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * KIS 키 저장·상태·삭제. 저장·삭제해도 도는 엔진을 다시 읽히지 않는다 — KIS 엔진이 아직 이 키에 연결되지 않았다.
 */
@RestController
@RequestMapping("/api/kis/keys")
@ConditionalOnProperty(prefix = "kis.trading", name = ["enabled"], havingValue = "true")
class KisKeyController(
    private val store: KisKeyStore,
) {

    @GetMapping
    suspend fun status(): List<KisKeyStatus> = translated { store.status(currentUserId()) }

    @PutMapping("/{exchange}")
    suspend fun save(@PathVariable exchange: String, @RequestBody request: KisKeyRequest): Map<String, String> {
        val target = parseExchange(exchange)
        val userId = currentUserId()
        translated {
            store.save(
                userId = userId,
                exchange = target,
                appKey = request.appKey ?: throw missing("app_key"),
                appSecret = request.appSecret ?: throw missing("app_secret"),
                accountNumber = request.accountNumber ?: throw missing("account_number"),
            )
        }
        return mapOf("status" to "saved")
    }

    @DeleteMapping("/{exchange}")
    suspend fun delete(@PathVariable exchange: String): Map<String, String> {
        val target = parseExchange(exchange)
        translated { store.delete(currentUserId(), target) }
        return mapOf("status" to "deleted")
    }

    private fun parseExchange(raw: String): Exchange =
        KisKeyStore.EXCHANGES.firstOrNull { it.name == raw }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "exchange 는 KIS_PAPER 또는 KIS 여야 한다")

    private fun missing(field: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, "$field 가 필요하다")

    // 원인 예외는 붙이지 않는다. 사유 문구는 SafeErrorAttributes 가 응답 message 로 낸다.
    private inline fun <T> translated(block: () -> T): T =
        try {
            block()
        } catch (e: KisKeyException) {
            val status = when (e) {
                is KisKeyInvalidException -> HttpStatus.BAD_REQUEST
                is KisLiveKeysDisabledException -> HttpStatus.CONFLICT
                is KisKeyStoreUnavailableException -> HttpStatus.SERVICE_UNAVAILABLE
            }
            throw ResponseStatusException(status, e.message)
        }
}

/** 필드가 빠졌을 때 Jackson 오류 대신 필드 이름을 담은 400 을 내려고 nullable 로 받는다. */
data class KisKeyRequest(
    val appKey: String? = null,
    val appSecret: String? = null,
    val accountNumber: String? = null,
) {
    // 요청 객체가 로그·예외 메시지에 찍혀도 원문이 나가지 않게 한다.
    override fun toString(): String = "KisKeyRequest(<redacted>)"
}
