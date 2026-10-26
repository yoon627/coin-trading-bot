package com.trading.bot.persistence

import com.trading.bot.domain.TradeRecord
import com.trading.bot.persistence.entity.TradeRecordEntity
import com.trading.common.domain.Exchange
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface TradeRecordR2dbcRepository : R2dbcRepository<TradeRecordEntity, Long> {
    fun findByTicker(ticker: String, sort: Sort): Flux<TradeRecordEntity>
    fun findByUserIdAndExchange(userId: Long, exchange: String, sort: Sort): Flux<TradeRecordEntity>
    fun countByUserIdAndExchange(userId: Long, exchange: String): Mono<Long>
}

@Repository
class TradeRecordRepository(
    private val r2dbcRepository: TradeRecordR2dbcRepository,
) {
    // 수수료·체결 VWAP·주문 ID 는 trade_executions 에 따로 남는다.
    suspend fun save(record: TradeRecord): TradeRecordEntity {
        val entity = TradeRecordEntity(
            ticker = record.ticker,
            side = record.side.name,
            price = record.price,
            volume = record.volume,
            totalAmount = record.totalAmount,
            orderAmount = record.orderAmount,
            pnlPercent = record.pnlPercent,
            pnlAmount = record.pnlAmount,
            reason = record.reason,
            strategy = record.strategy,
            userId = record.userId,
            createdAt = record.createdAt,
            exchange = record.exchange.name,
        )
        return r2dbcRepository.save(entity).awaitSingle()
    }

    suspend fun findByUserIdAndExchange(userId: Long, exchange: Exchange, limit: Int, offset: Int): List<TradeRecordEntity> {
        return r2dbcRepository.findByUserIdAndExchange(userId, exchange.name, Sort.by(Sort.Direction.DESC, "createdAt"))
            .skip(offset.toLong())
            .take(limit.toLong())
            .collectList()
            .awaitSingle()
    }

    /**
     * [exchange] 의 최근 [max] 건을 시간 오름차순으로 돌려준다. BUY/SELL 순서가 중요해 createdAt 이 같으면 id 로 정렬한다.
     * 잘린 앞쪽 때문에 가장 오래된 라운드트립은 매수 기록이 빠질 수 있다.
     */
    suspend fun findRecentAscending(userId: Long, exchange: Exchange, max: Int): List<TradeRecordEntity> {
        return r2dbcRepository.findByUserIdAndExchange(
            userId,
            exchange.name,
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")),
        )
            .take(max.toLong())
            .collectList()
            .awaitSingle()
            .reversed()
    }

    suspend fun countByUserIdAndExchange(userId: Long, exchange: Exchange): Long {
        return r2dbcRepository.countByUserIdAndExchange(userId, exchange.name).awaitSingle()
    }
}
