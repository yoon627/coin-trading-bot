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
    // 여기 없는 필드(fee·executedVwap·exchangeOrderId)는 trade_executions / shadow 관측으로 간다 — 의도된 투영.
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

    /**
     * 라운드트립 조립용 — 최근 [max] 건을 **시간 오름차순**으로 돌려준다.
     * 그룹 경계가 BUY/SELL 순서에 의존하므로 createdAt 동률을 대비해 id 를 2차 정렬키로 쓴다.
     * 상한을 넘으면 오래된 쪽이 잘려 가장 오래된 라운드트립의 매수 기록이 빌 수 있다(partial).
     */
    suspend fun findRecentAscending(userId: Long, max: Int): List<TradeRecordEntity> {
        return r2dbcRepository.findByUserId(
            userId,
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")),
        )
            .take(max.toLong())
            .collectList()
            .awaitSingle()
            .reversed()
    }

    suspend fun countByUserId(userId: Long): Long {
        return r2dbcRepository.countByUserId(userId).awaitSingle()
    }
}
