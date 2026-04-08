package com.trading.bot.persistence

import com.trading.bot.domain.TradeRecord
import com.trading.bot.persistence.entity.TradeRecordEntity
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface TradeRecordR2dbcRepository : R2dbcRepository<TradeRecordEntity, Long> {
    fun findByTicker(ticker: String, sort: Sort): Flux<TradeRecordEntity>
    fun findByUserId(userId: Long, sort: Sort): Flux<TradeRecordEntity>
    fun countByUserId(userId: Long): Mono<Long>
}

@Repository
class TradeRecordRepository(
    private val r2dbcRepository: TradeRecordR2dbcRepository,
) {
    suspend fun save(record: TradeRecord): TradeRecordEntity {
        val entity = TradeRecordEntity(
            ticker = record.ticker,
            side = record.side.name,
            price = record.price,
            volume = record.volume,
            totalAmount = record.totalAmount,
            pnlPercent = record.pnlPercent,
            reason = record.reason,
            strategy = record.strategy,
            userId = record.userId,
            createdAt = record.createdAt,
        )
        return r2dbcRepository.save(entity).awaitSingle()
    }

    suspend fun findByUserId(userId: Long, limit: Int = 100): List<TradeRecordEntity> {
        return r2dbcRepository.findByUserId(userId, Sort.by(Sort.Direction.DESC, "createdAt"))
            .take(limit.toLong())
            .collectList()
            .awaitSingle()
    }

    suspend fun countByUserId(userId: Long): Long {
        return r2dbcRepository.countByUserId(userId).awaitSingle()
    }
}
