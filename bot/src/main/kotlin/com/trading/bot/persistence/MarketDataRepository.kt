package com.trading.bot.persistence

import com.trading.bot.persistence.entity.MarketCandleEntity
import com.trading.bot.persistence.entity.MarketTickerEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

interface MarketTickerRepository : ReactiveCrudRepository<MarketTickerEntity, Long> {

    @Query("SELECT * FROM market_tickers WHERE exchange = :exchange AND market = :market ORDER BY recorded_at DESC LIMIT :limit")
    fun findRecent(exchange: String, market: String, limit: Int): Flux<MarketTickerEntity>

    @Query("DELETE FROM market_tickers WHERE recorded_at < :before")
    fun deleteOlderThan(before: Instant): Mono<Long>
}

interface MarketCandleRepository : ReactiveCrudRepository<MarketCandleEntity, Long> {

    @Query("""
        SELECT * FROM market_candles
        WHERE exchange = :exchange AND market = :market AND interval_minutes = :intervalMinutes
        ORDER BY open_time DESC LIMIT :limit
    """)
    fun findRecent(exchange: String, market: String, intervalMinutes: Int, limit: Int): Flux<MarketCandleEntity>

    @Query("""
        SELECT * FROM market_candles
        WHERE exchange = :exchange AND market = :market AND interval_minutes = :intervalMinutes
        AND open_time BETWEEN :from AND :to
        ORDER BY open_time ASC
    """)
    fun findByTimeRange(exchange: String, market: String, intervalMinutes: Int, from: Instant, to: Instant): Flux<MarketCandleEntity>
}
