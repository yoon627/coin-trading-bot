package com.trading.bot.persistence

import com.trading.bot.persistence.entity.PriceSnapshotEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.LocalDateTime

interface PriceSnapshotRepository : R2dbcRepository<PriceSnapshotEntity, Long> {
    fun findByTickerAndCapturedAtBetweenOrderByCapturedAtDesc(
        ticker: String, from: LocalDateTime, to: LocalDateTime,
    ): Flux<PriceSnapshotEntity>

    fun findByTickerOrderByCapturedAtDesc(ticker: String): Flux<PriceSnapshotEntity>

    @Query("DELETE FROM price_snapshots WHERE captured_at < :cutoff")
    fun deleteByCapturedAtBefore(cutoff: LocalDateTime): Mono<Long>
}
