package com.trading.bot.persistence

import com.trading.bot.persistence.entity.StockPositionStateEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface StockPositionStateRepository : R2dbcRepository<StockPositionStateEntity, Long> {
    fun findByUserIdAndExchange(userId: Long, exchange: String): Flux<StockPositionStateEntity>
    fun findByUserIdAndExchangeAndSymbol(userId: Long, exchange: String, symbol: String): Mono<StockPositionStateEntity>
}
