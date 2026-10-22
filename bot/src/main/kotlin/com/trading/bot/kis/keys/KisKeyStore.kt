package com.trading.bot.kis.keys

import com.trading.bot.kis.client.KisAccess
import com.trading.bot.kis.config.KisTradingProperties
import com.trading.bot.persistence.UserBrokerKeyRepository
import com.trading.bot.persistence.entity.UserBrokerKeyEntity
import com.trading.bot.security.SecretsCrypto
import com.trading.common.domain.Exchange
import java.time.Instant
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service

/**
 * 사용자별 KIS 앱키·앱시크릿·계좌번호를 환경(KIS_PAPER 모의, KIS 실전)마다 암호화해 둔다.
 * 원문은 [load] 의 [KisAccess] 로만 나가고, 예외 메시지·로그·[status] 에는 들어가지 않는다.
 */
@Service
@ConditionalOnProperty(prefix = "kis.trading", name = ["enabled"], havingValue = "true")
class KisKeyStore(
    private val repository: UserBrokerKeyRepository,
    private val crypto: SecretsCrypto,
    private val trading: KisTradingProperties,
    private val dbTimeoutMs: Long = DB_TIMEOUT_MS,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun save(userId: Long, exchange: Exchange, appKey: String, appSecret: String, accountNumber: String) {
        requireKisExchange(exchange)
        if (exchange == Exchange.KIS && !trading.liveEnabled) throw KisLiveKeysDisabledException()
        val key = normalizeKeyPart(appKey, "app_key", APP_KEY_MAX_LENGTH)
        val sig = normalizeKeyPart(appSecret, "app_secret", APP_SECRET_MAX_LENGTH)
        val access = try {
            KisAccess.of(key, sig, accountNumber)
        } catch (e: IllegalArgumentException) {
            // KisAccess 의 메시지는 입력값을 담지 않는 고정 문구다.
            throw KisKeyInvalidException(e.message ?: "KIS 키 형식이 올바르지 않다")
        }
        bounded {
            repository.upsert(
                userId = userId,
                exchange = exchange.name,
                appKey = crypto.encrypt(access.appKey),
                appSecret = crypto.encrypt(access.appSecret),
                accountNo = crypto.encrypt("${access.cano}-${access.productCode}"),
            ).awaitSingle()
        }
        log.info("KIS keys saved: userId={} exchange={} fingerprint={}", userId, exchange, access.fingerprint)
    }

    /** 저장된 키가 없으면 null. 복호화하지 못하거나 복호화한 값의 형식이 틀리면 원인 없이 [IllegalStateException] 이다. */
    suspend fun load(userId: Long, exchange: Exchange): KisAccess? {
        requireKisExchange(exchange)
        val row = bounded { repository.findByUserIdAndExchange(userId, exchange.name).awaitSingleOrNull() } ?: return null
        val appKey = decrypt(row, row.appKey)
        val appSecret = decrypt(row, row.appSecret)
        val account = decrypt(row, row.accountNo)
        return try {
            KisAccess.of(appKey, appSecret, account)
        } catch (_: IllegalArgumentException) {
            throw unreadable(row)
        }
    }

    /** [EXCHANGES] 순서로 하나씩. 앱키·시크릿은 어떤 형태로도 담지 않는다. */
    suspend fun status(userId: Long): List<KisKeyStatus> {
        val rows = bounded { repository.findByUserId(userId).collectList().awaitSingle() }.associateBy { it.exchange }
        return EXCHANGES.map { exchange ->
            val row = rows[exchange.name]
                ?: return@map KisKeyStatus(exchange.name, configured = false, account = null, updatedAt = null)
            KisKeyStatus(exchange.name, configured = true, account = maskedAccount(row), updatedAt = row.updatedAt)
        }
    }

    /** 지운 행이 있으면 true. */
    suspend fun delete(userId: Long, exchange: Exchange): Boolean {
        requireKisExchange(exchange)
        val deleted = bounded { repository.deleteByUserIdAndExchange(userId, exchange.name).awaitSingle() } > 0
        if (deleted) log.info("KIS keys deleted: userId={} exchange={}", userId, exchange)
        return deleted
    }

    // 계좌를 읽지 못해도 상태 조회는 막지 않는다. 사용자가 상태를 보고 키를 다시 저장해야 하기 때문이다.
    private fun maskedAccount(row: UserBrokerKeyEntity): String? {
        val account = try {
            decrypt(row, row.accountNo)
        } catch (e: IllegalStateException) {
            log.warn("KIS account unreadable: userId={} exchange={}", row.userId, row.exchange)
            return null
        }
        val match = ACCOUNT_FORMAT.matchEntire(account) ?: return null
        return "****${match.groupValues[1].takeLast(4)}-${match.groupValues[2]}"
    }

    private fun decrypt(row: UserBrokerKeyEntity, value: String): String {
        // 접두가 없으면 decryptIfNeeded 가 값을 그대로 돌려주므로 평문이 키로 쓰이지 않게 먼저 막는다.
        val plain = if (value.startsWith(ENCRYPTED_PREFIX)) {
            try {
                crypto.decryptIfNeeded(value)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return plain ?: throw unreadable(row)
    }

    // 원인 예외에 암호문 조각이 실릴 수 있어 붙이지 않는다.
    private fun unreadable(row: UserBrokerKeyEntity) = IllegalStateException(
        "저장된 KIS 키를 읽지 못했다: userId=${row.userId} exchange=${row.exchange} " +
            "— 암호화 키(APP_ENCRYPTION_SECRET)가 바뀌었거나 값이 손상됐으면 키를 다시 저장한다",
    )

    // withTimeoutOrNull 은 자기 상한만 null 로 바꾸고 바깥 상한의 취소는 그대로 올린다.
    // DB 오류 메시지에는 실패한 행(암호문 포함)이 실릴 수 있어 예외 종류만 남기고 원인은 붙이지 않는다.
    private suspend fun <T> bounded(block: suspend () -> T): T {
        val done = try {
            withTimeoutOrNull(dbTimeoutMs) { Completed(block()) }
        } catch (e: DataAccessException) {
            log.warn("KIS key store failed: {}", e.javaClass.simpleName)
            throw KisKeyStoreUnavailableException()
        }
        return (done ?: throw KisKeyStoreUnavailableException()).value
    }

    // 블록이 null 을 돌려준 것과 상한에 걸린 것을 구분한다.
    private class Completed<T>(val value: T)

    companion object {
        val EXCHANGES = listOf(Exchange.KIS_PAPER, Exchange.KIS)

        // V31 열 길이가 받는 평문 상한(app_key 353, app_secret 737 바이트) 안쪽이다. ASCII 만 받으므로 글자 수가 바이트 수다.
        const val APP_KEY_MAX_LENGTH = 128
        const val APP_SECRET_MAX_LENGTH = 512

        internal const val DB_TIMEOUT_MS = 10_000L
        private const val ENCRYPTED_PREFIX = "enc:"
        private val PRINTABLE_ASCII = Regex("^[\\x21-\\x7E]+$")
        private val ACCOUNT_FORMAT = Regex("(\\d{8})-(\\d{2})")

        private fun requireKisExchange(exchange: Exchange) {
            if (exchange !in EXCHANGES) throw KisKeyInvalidException("exchange 는 KIS_PAPER 또는 KIS 여야 한다")
        }

        // 앞뒤 공백만 걷고, 안쪽 공백·제어문자·비ASCII 는 붙여넣기 실수로 보고 거절한다.
        private fun normalizeKeyPart(value: String, field: String, maxLength: Int): String {
            val normalized = value.trim()
            if (normalized.isEmpty()) throw KisKeyInvalidException("$field 가 비었다")
            if (normalized.length > maxLength) throw KisKeyInvalidException("$field 는 $maxLength 자 이하여야 한다")
            if (!PRINTABLE_ASCII.matches(normalized)) {
                throw KisKeyInvalidException("$field 에 공백·제어문자·ASCII 밖 문자가 있다")
            }
            return normalized
        }
    }
}

data class KisKeyStatus(
    val exchange: String,
    val configured: Boolean,
    val account: String?,
    val updatedAt: Instant?,
)

/** 메시지가 API 응답에 그대로 나가므로 입력값을 넣지 않는다. */
sealed class KisKeyException(message: String) : RuntimeException(message)

class KisKeyInvalidException(message: String) : KisKeyException(message)

class KisLiveKeysDisabledException : KisKeyException("실전(KIS) 키 저장은 kis.trading.live-enabled=true 일 때만 받는다")

class KisKeyStoreUnavailableException :
    KisKeyException("KIS 키 저장소를 지금 쓸 수 없다. 잠시 후 다시 시도한다(저장 요청이었다면 반영됐는지 상태로 확인한다)")
