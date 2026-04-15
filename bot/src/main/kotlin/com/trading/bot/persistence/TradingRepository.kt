package com.trading.bot.persistence

import com.trading.bot.persistence.entity.BotConfigEntity
import com.trading.bot.persistence.entity.PositionEntity
import com.trading.bot.persistence.entity.TradeExecutionEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface TradeExecutionRepository : ReactiveCrudRepository<TradeExecutionEntity, Long> {

    @Query("SELECT * FROM trade_executions WHERE user_id = :userId ORDER BY executed_at DESC LIMIT :limit OFFSET :offset")
    fun findByUserId(userId: Long, limit: Int, offset: Int): Flux<TradeExecutionEntity>

    @Query("SELECT COUNT(*) FROM trade_executions WHERE user_id = :userId")
    fun countByUserId(userId: Long): Mono<Long>
}

interface PositionRepository : ReactiveCrudRepository<PositionEntity, Long> {

    @Query("SELECT * FROM positions WHERE user_id = :userId")
    fun findByUserId(userId: Long): Flux<PositionEntity>

    @Query("SELECT * FROM positions WHERE user_id = :userId AND exchange = :exchange AND market = :market")
    fun findByUserAndMarket(userId: Long, exchange: String, market: String): Mono<PositionEntity>

    @Query("DELETE FROM positions WHERE user_id = :userId AND exchange = :exchange AND market = :market")
    fun deleteByUserAndMarket(userId: Long, exchange: String, market: String): Mono<Long>
}

interface BotConfigRepository : ReactiveCrudRepository<BotConfigEntity, Long> {

    @Query("SELECT * FROM bot_configs WHERE user_id = :userId AND enabled = true")
    fun findEnabledByUserId(userId: Long): Flux<BotConfigEntity>

    @Query("SELECT * FROM bot_configs WHERE user_id = :userId")
    fun findByUserId(userId: Long): Flux<BotConfigEntity>

    @Query("DELETE FROM bot_configs WHERE id = :id AND user_id = :userId")
    fun deleteByIdAndUserId(id: Long, userId: Long): Mono<Long>
}
