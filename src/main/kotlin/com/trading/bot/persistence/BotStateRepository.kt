package com.trading.bot.persistence

import com.trading.bot.persistence.entity.BotStateEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface BotStateRepository : R2dbcRepository<BotStateEntity, Long> {
    fun findByUserId(userId: Long): Mono<BotStateEntity>
    fun findByRunningTrue(): Flux<BotStateEntity>
}
