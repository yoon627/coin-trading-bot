package com.trading.bot.persistence

import com.trading.bot.persistence.entity.MarketCandleEntity
import com.trading.bot.persistence.entity.MarketTickerEntity
import org.springframework.data.r2dbc.repository.Modifying
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

    // 멱등 저장: 같은 (exchange, market, interval, open_time) 재수집 시 INSERT 대신 갱신
    // → 폴링 drift 로 인한 UNIQUE 위반 침묵/미반영 제거.
    @Modifying
    @Query("""
        INSERT INTO market_candles
            (exchange, market, interval_minutes, open_price, high_price, low_price, close_price, volume, quote_volume, open_time, close_time)
        VALUES
            (:exchange, :market, :intervalMinutes, :openPrice, :highPrice, :lowPrice, :closePrice, :volume, :quoteVolume, :openTime, :closeTime)
        ON CONFLICT (exchange, market, interval_minutes, open_time)
        DO UPDATE SET
            high_price = EXCLUDED.high_price,
            low_price = EXCLUDED.low_price,
            close_price = EXCLUDED.close_price,
            volume = EXCLUDED.volume,
            quote_volume = EXCLUDED.quote_volume,
            close_time = EXCLUDED.close_time
    """)
    fun upsert(
        exchange: String, market: String, intervalMinutes: Int,
        openPrice: Double, highPrice: Double, lowPrice: Double, closePrice: Double,
        volume: Double, quoteVolume: Double, openTime: Instant, closeTime: Instant,
    ): Mono<Long>

    @Modifying
    @Query("DELETE FROM market_candles WHERE interval_minutes = :intervalMinutes AND open_time < :before")
    fun deleteByIntervalOlderThan(intervalMinutes: Int, before: Instant): Mono<Long>
}
