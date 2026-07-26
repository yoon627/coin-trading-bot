package com.trading.bot.persistence

import com.trading.bot.persistence.entity.TradingStateEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface TradingStateRepository : R2dbcRepository<TradingStateEntity, Long> {
    fun findByUserId(userId: Long): Flux<TradingStateEntity>
    fun findByUserIdAndTicker(userId: Long, ticker: String): Mono<TradingStateEntity>
}
