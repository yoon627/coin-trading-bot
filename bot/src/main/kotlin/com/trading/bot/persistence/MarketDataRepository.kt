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

    // 1h 변화율의 기준점 — 창 안에서 **가장 오래된 1건**만 필요하다. 창 전체를 읽으면 활발한
    // 종목에서 수천 행이 매 요청 메모리에 올라오는데, 계산에 쓰이는 건 이 한 건뿐이다.
    // 동일 recorded_at 다건에서 결과가 흔들리지 않도록 id 로 tie-break 한다.
    @Query("""
        SELECT * FROM market_tickers
        WHERE exchange = :exchange AND market = :market
        AND recorded_at BETWEEN :from AND :to
        ORDER BY recorded_at ASC, id ASC
        LIMIT 1
    """)
    fun findOldestInRange(exchange: String, market: String, from: Instant, to: Instant): Mono<MarketTickerEntity>

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
