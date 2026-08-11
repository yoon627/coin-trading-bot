package com.trading.bot.stream

import com.trading.bot.persistence.MarketCandleRepository
import com.trading.bot.persistence.MarketTickerRepository
import com.trading.bot.persistence.entity.MarketTickerEntity
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Component
class MarketDataPersistenceService(
    private val marketTickerRepository: MarketTickerRepository,
    private val marketCandleRepository: MarketCandleRepository,
    private val candleAggregator: CandleAggregator,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    // 종목별 카운터 — 전역 카운터였을 때는 고활동 종목이 카운트를 독식해, 저활동 종목은
    // 1h 창에 행이 거의 남지 않아 watchlist 의 1h 변화율이 null 이 됐다.
    // 키는 부팅 시 고정된 watchlist 로 한정된다(MarketDataIngestionService.start 가 구독 목록을
    // 한 번 정한다) — 런타임에 임의 market 이 유입되는 경로가 생기면 정리 정책이 필요해진다.
    private val tickerSaveCounts = ConcurrentHashMap<String, AtomicLong>()

    companion object {
        private const val TICKER_SAVE_INTERVAL = 10L
    }

    fun persistTicker(ticker: NormalizedTicker) {
        // 종목마다 매 N번째만 저장해 DB 폭주를 막는다.
        val key = "${ticker.exchange.name}:${ticker.market}"
        val count = tickerSaveCounts.computeIfAbsent(key) { AtomicLong(0) }
        if (count.incrementAndGet() % TICKER_SAVE_INTERVAL != 0L) return

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
