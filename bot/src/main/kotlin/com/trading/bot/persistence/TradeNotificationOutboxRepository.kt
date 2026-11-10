package com.trading.bot.persistence

import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

interface TradeNotificationOutboxRepository : R2dbcRepository<TradeNotificationOutboxEntity, Long> {

    @Query(
        "SELECT * FROM trade_notification_outbox WHERE status = 'PENDING' AND next_attempt_at <= :now " +
            "ORDER BY next_attempt_at, id LIMIT :limit",
    )
    fun findDue(now: Instant, limit: Int): Flux<TradeNotificationOutboxEntity>

    fun countByStatus(status: String): Mono<Long>
}
