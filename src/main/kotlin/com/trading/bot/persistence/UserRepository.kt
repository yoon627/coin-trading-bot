package com.trading.bot.persistence

import com.trading.bot.persistence.entity.UserEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface UserRepository : R2dbcRepository<UserEntity, Long> {
    fun findByUsername(username: String): Mono<UserEntity>
    fun findByPublicProfileTrue(): Flux<UserEntity>
    fun findByAdminTrue(): Flux<UserEntity>
}
