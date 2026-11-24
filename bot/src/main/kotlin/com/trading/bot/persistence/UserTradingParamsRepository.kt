package com.trading.bot.persistence

import com.trading.bot.persistence.entity.UserTradingParamsEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Mono

interface UserTradingParamsRepository : R2dbcRepository<UserTradingParamsEntity, Long> {
    fun findByUserIdAndExchange(userId: Long, exchange: String): Mono<UserTradingParamsEntity>
}
