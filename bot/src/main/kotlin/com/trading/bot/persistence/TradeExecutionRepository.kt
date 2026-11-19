package com.trading.bot.persistence

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

    // 재시작 후 같은 주문을 다시 대사해도 이중 기록되지 않게 한다.
    @Query("SELECT COUNT(*) > 0 FROM trade_executions WHERE user_id = :userId AND exchange_order_id = :orderId")
    fun existsByUserIdAndExchangeOrderId(userId: Long, orderId: String): Mono<Boolean>

    /** [orderIds] 중 거래 기록이 있는 주문 번호. 빈 목록은 넘기지 않는다(`IN ()` 은 SQL 오류다). */
    @Query("SELECT exchange_order_id FROM trade_executions WHERE user_id = :userId AND exchange_order_id IN (:orderIds)")
    fun findRecordedOrderIds(userId: Long, orderIds: Collection<String>): Flux<String>
}
