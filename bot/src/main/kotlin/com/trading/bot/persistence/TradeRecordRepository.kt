package com.trading.bot.persistence

import com.trading.bot.domain.TradeRecord
import com.trading.bot.persistence.entity.TradeRecordEntity
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** 리더보드용 유저별 SELL 집계 (DB 측 GROUP BY 결과). */
data class UserTradeStats(
    val userId: Long = 0,
    val totalTrades: Long = 0,
    val winTrades: Long = 0,
    val totalPnl: Double = 0.0,
)

interface TradeRecordR2dbcRepository : R2dbcRepository<TradeRecordEntity, Long> {
    fun findByTicker(ticker: String, sort: Sort): Flux<TradeRecordEntity>
    fun findByUserId(userId: Long, sort: Sort): Flux<TradeRecordEntity>
    fun countByUserId(userId: Long): Mono<Long>

    // 전 유저 SELL 레코드를 메모리에 로드하지 않고 DB 에서 유저별로 집계.
    @Query(
        """
        SELECT user_id,
               COUNT(*) AS total_trades,
               COUNT(*) FILTER (WHERE pnl_percent > 0) AS win_trades,
               COALESCE(SUM(pnl_percent), 0) AS total_pnl
        FROM trade_records
        WHERE side = 'SELL' AND pnl_percent IS NOT NULL
        GROUP BY user_id
        """
    )
    fun aggregateSellStatsByUser(): Flux<UserTradeStats>
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

    suspend fun findByUserId(userId: Long, limit: Int = 100, offset: Int = 0): List<TradeRecordEntity> {
        return r2dbcRepository.findByUserId(userId, Sort.by(Sort.Direction.DESC, "createdAt"))
            .skip(offset.toLong())
            .take(limit.toLong())
            .collectList()
            .awaitSingle()
    }

    suspend fun countByUserId(userId: Long): Long {
        return r2dbcRepository.countByUserId(userId).awaitSingle()
    }

    suspend fun aggregateSellStatsByUser(): Map<Long, UserTradeStats> {
        return r2dbcRepository.aggregateSellStatsByUser()
            .collectList()
            .awaitSingle()
            .associateBy { it.userId }
    }
}
