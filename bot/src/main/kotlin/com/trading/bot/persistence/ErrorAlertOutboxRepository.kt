package com.trading.bot.persistence

import com.trading.bot.persistence.entity.ErrorAlertOutboxEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

interface ErrorAlertOutboxRepository : R2dbcRepository<ErrorAlertOutboxEntity, Long> {

    @Query("SELECT * FROM error_alert_outbox WHERE status = 'PENDING' AND next_attempt_at <= :now ORDER BY id LIMIT :limit")
    fun findDue(now: Instant, limit: Int): Flux<ErrorAlertOutboxEntity>

    @Modifying
    @Query("DELETE FROM error_alert_outbox WHERE status <> 'PENDING' AND created_at < :cutoff")
    fun deleteFinishedBefore(cutoff: Instant): Mono<Long>

    fun countByStatus(status: String): Mono<Long>
}
