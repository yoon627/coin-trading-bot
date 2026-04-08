package com.trading.bot.persistence

import com.trading.bot.domain.TradeRecord
import com.trading.bot.persistence.entity.TradeRecordEntity
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface TradeRecordR2dbcRepository : R2dbcRepository<TradeRecordEntity, Long> {
    fun findByTicker(ticker: String, sort: Sort): Flux<TradeRecordEntity>
    fun countByTicker(ticker: String): Mono<Long>
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
            createdAt = record.createdAt,
        )
        return r2dbcRepository.save(entity).awaitSingle()
    }

    suspend fun findAll(limit: Int = 100, offset: Long = 0): List<TradeRecordEntity> {
        return r2dbcRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
            .skip(offset)
            .take(limit.toLong())
            .collectList()
            .awaitSingle()
    }

    suspend fun findByTicker(ticker: String, limit: Int = 100): List<TradeRecordEntity> {
        return r2dbcRepository.findByTicker(ticker, Sort.by(Sort.Direction.DESC, "createdAt"))
            .take(limit.toLong())
            .collectList()
            .awaitSingle()
    }

    suspend fun count(): Long {
        return r2dbcRepository.count().awaitSingle()
    }
}
