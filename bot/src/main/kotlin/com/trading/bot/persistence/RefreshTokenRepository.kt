package com.trading.bot.persistence

import com.trading.bot.persistence.entity.RefreshTokenEntity
import java.time.Instant
import java.util.UUID
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Mono

interface RefreshTokenRepository : R2dbcRepository<RefreshTokenEntity, Long> {
    fun findByTokenHash(tokenHash: String): Mono<RefreshTokenEntity>

    /**
     * 쓸 수 있는 토큰이면 바뀐 것으로 표시하고 그 행을 돌려준다. 한 문장이라 같은 토큰으로 동시에 와도 하나만 이긴다 —
     * 진 쪽은 비어 끝나고 [findByTokenHash] 로 이유(동시 갱신·재사용·만료)를 가린다.
     */
    @Query(
        """
        UPDATE refresh_tokens SET replaced_at = :now
        WHERE token_hash = :tokenHash AND replaced_at IS NULL AND revoked_at IS NULL AND expires_at > :now
        RETURNING *
        """,
    )
    fun markReplaced(tokenHash: String, now: Instant): Mono<RefreshTokenEntity>

    @Modifying
    @Query("UPDATE refresh_tokens SET revoked_at = :now WHERE family_id = :familyId AND revoked_at IS NULL")
    fun revokeFamily(familyId: UUID, now: Instant): Mono<Int>

    // 바뀐 행도 만료 전에는 남긴다 — 일찍 지우면 훔친 옛 토큰이 '모르는 토큰' 이 되어 계열 끊기가 걸리지 않는다.
    @Modifying
    @Query("DELETE FROM refresh_tokens WHERE expires_at < :now")
    fun deleteExpired(now: Instant): Mono<Int>
}
