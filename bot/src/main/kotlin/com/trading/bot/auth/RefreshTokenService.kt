package com.trading.bot.auth

import com.trading.bot.persistence.RefreshTokenRepository
import com.trading.bot.persistence.entity.RefreshTokenEntity
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator

/**
 * 로그인 유지용 리프레시 토큰. 원문은 쿠키에만 있고 DB 에는 SHA-256 hex 만 둔다. 쓸 때마다 같은 계열의 새 토큰으로 바꾸고,
 * 이미 바꾼 토큰이 [CONCURRENT_GRACE] 를 넘겨 다시 오면 탈취로 보고 계열 전체를 끊는다(ERROR → Discord).
 * 유지면 마지막 사용부터 [REMEMBER_FOR], 유지 안 함이면 첫 로그인부터 [SESSION_FOR] 로 끝난다.
 */
@Service
class RefreshTokenService(
    private val repository: RefreshTokenRepository,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    /** [lifetime] 은 이 서비스의 시계로 잰 남은 수명 — 쿠키 Max-Age 로 그대로 쓴다. */
    data class Issued(val token: String, val lifetime: Duration)

    sealed interface Refresh<out U> {
        data class Rotated<U>(val user: U, val token: String, val lifetime: Duration) : Refresh<U>

        /** 같은 토큰으로 다른 탭이 방금 바꿨다. 쿠키를 건드리지 않는다 — 그 탭이 받은 새 쿠키가 이미 저장소에 있다. */
        data object Concurrent : Refresh<Nothing>

        data object Rejected : Refresh<Nothing>
    }

    // 바꾼 뒤 사용자를 못 찾으면 트랜잭션을 되돌려 옛 토큰을 그대로 둔다.
    private class MissingUser : RuntimeException()

    companion object {
        val REMEMBER_FOR: Duration = Duration.ofDays(30)
        val SESSION_FOR: Duration = Duration.ofHours(24)
        val CONCURRENT_GRACE: Duration = Duration.ofSeconds(10)
        private const val TOKEN_BYTES = 32
        private const val REVOKE_PASSES = 5

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    suspend fun issue(userId: Long, remember: Boolean): Issued {
        val now = clock.instant()
        repository.deleteExpired(now).awaitSingle()
        val token = newToken()
        val lifetime = if (remember) REMEMBER_FOR else SESSION_FOR
        repository.save(
            RefreshTokenEntity(userId = userId, familyId = UUID.randomUUID(), tokenHash = hash(token), remember = remember, expiresAt = now.plus(lifetime)),
        ).awaitSingle()
        return Issued(token, lifetime)
    }

    /**
     * 토큰을 바꾸고 [loadUser] 로 그 사용자를 읽는다. 바꾸기·새 토큰 넣기·사용자 읽기가 한 트랜잭션이라 어느 하나가 실패하면
     * 옛 토큰이 그대로 남는다 — 응답을 못 보내 새 토큰을 잃고 옛 토큰은 '바뀐 것' 이 되어 재사용으로 끊기는 일을 막는다.
     */
    suspend fun <U : Any> refresh(token: String, loadUser: suspend (Long) -> U?): Refresh<U> {
        val now = clock.instant()
        repository.deleteExpired(now).awaitSingle()
        val tokenHash = hash(token)
        val rotated = try {
            transactionalOperator.transactional(
                mono {
                    val old = repository.markReplaced(tokenHash, now).awaitSingleOrNull() ?: return@mono null
                    val next = newToken()
                    // 유지 안 함은 첫 로그인부터 24시간 — 갱신해도 끝이 밀리지 않는다.
                    val expiresAt = if (old.remember) now.plus(REMEMBER_FOR) else old.expiresAt
                    repository.save(
                        RefreshTokenEntity(userId = old.userId, familyId = old.familyId, tokenHash = hash(next), remember = old.remember, expiresAt = expiresAt),
                    ).awaitSingle()
                    val user = loadUser(old.userId) ?: throw MissingUser()
                    Refresh.Rotated(user, next, Duration.between(now, expiresAt))
                },
            ).awaitSingleOrNull()
        } catch (_: MissingUser) {
            return Refresh.Rejected
        }
        if (rotated != null) return rotated

        val row = repository.findByTokenHash(tokenHash).awaitSingleOrNull() ?: return Refresh.Rejected
        val replacedAt = row.replacedAt
        if (row.revokedAt != null || !row.expiresAt.isAfter(now) || replacedAt == null) return Refresh.Rejected
        if (!Duration.between(replacedAt, now).minus(CONCURRENT_GRACE).isPositive) return Refresh.Concurrent
        revokeFamily(row.familyId, now)
        // ERROR 로그는 DiscordErrorLogAppender 가 알림으로 보낸다. 응답을 잃은 정상 사용자일 수도 있다 — 그때는 다시 로그인하면 된다.
        log.error("[AUTH] 이미 바꾼 로그인 유지 토큰이 다시 왔다 — 탈취로 보고 그 로그인을 끊었다(다시 로그인해야 한다): userId={}, family={}", row.userId, row.familyId)
        return Refresh.Rejected
    }

    /** 로그아웃·다시 로그인: 그 토큰의 계열 전체를 끊는다. 모르는 토큰이면 할 일이 없다. */
    suspend fun revoke(token: String) {
        val row = repository.findByTokenHash(hash(token)).awaitSingleOrNull() ?: return
        revokeFamily(row.familyId, clock.instant())
    }

    // 한 문장은 시작 때 보이는 행만 바꾼다 — 그사이 진행 중이던 갱신이 넣은 새 토큰은 다음 문장이 잡는다. 더 바꿀 것이 없을 때까지.
    private suspend fun revokeFamily(familyId: UUID, now: Instant) {
        repeat(REVOKE_PASSES) {
            if (repository.revokeFamily(familyId, now).awaitSingle() == 0) return
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
