package com.trading.bot.stream

import com.trading.bot.persistence.MarketCandleRepository
import com.trading.bot.persistence.MarketTickerRepository
import com.trading.bot.persistence.entity.MarketTickerEntity
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
class MarketDataPersistenceService(
    private val marketTickerRepository: MarketTickerRepository,
    private val marketCandleRepository: MarketCandleRepository,
    private val candleAggregator: CandleAggregator,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tickerSaveCount = AtomicLong(0)

    companion object {
        private const val TICKER_SAVE_INTERVAL = 10L
    }

    fun persistTicker(ticker: NormalizedTicker) {
        // Save every Nth ticker to avoid flooding the DB
        if (tickerSaveCount.incrementAndGet() % TICKER_SAVE_INTERVAL != 0L) return

        val entity = MarketTickerEntity(
            exchange = ticker.exchange.name,
            market = ticker.market,
            price = ticker.price,
            bidPrice = ticker.bidPrice,
            askPrice = ticker.askPrice,
            volume24h = ticker.volume24h,
            quoteVolume24h = ticker.quoteVolume24h,
            changeRate24h = ticker.changeRate24h,
            highPrice24h = ticker.highPrice24h,
            lowPrice24h = ticker.lowPrice24h,
            recordedAt = ticker.timestamp,
        )
        marketTickerRepository.save(entity)
            .subscribe({}, { e -> log.warn("Failed to persist ticker: {}", e.message) })
    }

    fun persistCandle(candle: NormalizedCandle) {
        // 멱등 upsert — 폴링 drift 로 같은 봉이 재전송돼도 UNIQUE 위반 없이 최신값 반영.
        marketCandleRepository.upsert(
            exchange = candle.exchange.name,
            market = candle.market,
            intervalMinutes = candle.interval.minutes,
            openPrice = candle.openPrice,
            highPrice = candle.highPrice,
            lowPrice = candle.lowPrice,
            closePrice = candle.closePrice,
            volume = candle.volume,
            quoteVolume = candle.quoteVolume,
            openTime = candle.openTime,
            closeTime = candle.closeTime,
        ).subscribe({}, { e -> log.warn("Failed to upsert candle: {}", e.message) })

        // Trigger aggregation for higher timeframes
        candleAggregator.onMinuteCandle(candle)
    }
}
