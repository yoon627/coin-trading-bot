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

/** 전략별 성과 집계 (DB 측 GROUP BY 결과). 전략 미상 거래는 strategy=null 그룹으로 온다. */
data class StrategyPerformance(
    val strategy: String? = null,
    val totalTrades: Long = 0,
    val sellTrades: Long = 0,
    val winTrades: Long = 0,
    val totalPnlPct: Double = 0.0,
    val totalPnlAmount: Double = 0.0,
    val totalAmount: Double = 0.0,
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
          AND (strategy IS NULL OR strategy <> 'accumulate')
        GROUP BY user_id
        """
    )
    fun aggregateSellStatsByUser(): Flux<UserTradeStats>

    // 전략별 성과. 거래건수·거래대금은 BUY+SELL, 승률·손익은 SELL 만 센다(매수는 실현 손익이 없다).
    // 페이지를 메모리에 올려 groupBy 하면 원화 손익이 조용히 잘리므로 DB 에서 전 구간을 집계한다.
    // ORDER BY 는 필수 — GROUP BY 는 순서를 보장하지 않는데 SPA 가 상위 N 개만 잘라 그린다.
    // 정렬 키를 total_pnl_pct 로 두는 것은 SPA 가 그 값을 표시하기 때문이다. 금액으로 정렬하면
    // 화면에 보이는 숫자와 순위 기준이 어긋나고, 귀속이 비어 pnl_amount 가 0 인 그룹이 손실 전략보다 위로 온다.
    @Query(
        """
        SELECT strategy,
               COUNT(*) AS total_trades,
               COUNT(*) FILTER (WHERE side = 'SELL' AND pnl_percent IS NOT NULL) AS sell_trades,
               COUNT(*) FILTER (WHERE side = 'SELL' AND pnl_percent > 0) AS win_trades,
               COALESCE(SUM(pnl_percent) FILTER (WHERE side = 'SELL'), 0) AS total_pnl_pct,
               COALESCE(SUM(pnl_amount)  FILTER (WHERE side = 'SELL'), 0) AS total_pnl_amount,
               COALESCE(SUM(total_amount), 0) AS total_amount
        FROM trade_records
        WHERE user_id = :userId
        GROUP BY strategy
        ORDER BY total_pnl_pct DESC, total_trades DESC
        """
    )
    fun aggregateByStrategy(userId: Long): Flux<StrategyPerformance>
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

    suspend fun aggregateByStrategy(userId: Long): List<StrategyPerformance> =
        r2dbcRepository.aggregateByStrategy(userId).collectList().awaitSingle()

    suspend fun aggregateSellStatsByUser(): Map<Long, UserTradeStats> {
        return r2dbcRepository.aggregateSellStatsByUser()
            .collectList()
            .awaitSingle()
            .associateBy { it.userId }
    }
}
