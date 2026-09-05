package com.trading.bot.persistence

import com.trading.bot.persistence.entity.ShadowExitObservationEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux

interface ShadowExitObservationRepository : R2dbcRepository<ShadowExitObservationEntity, Long> {
    fun findByUserIdOrderByFiredAtDesc(userId: Long): Flux<ShadowExitObservationEntity>
}
