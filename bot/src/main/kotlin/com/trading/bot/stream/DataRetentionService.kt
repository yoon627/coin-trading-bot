package com.trading.bot.stream

import com.trading.bot.persistence.MarketCandleRepository
import com.trading.bot.persistence.MarketTickerRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class DataRetentionService(
    private val marketTickerRepository: MarketTickerRepository,
    private val marketCandleRepository: MarketCandleRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val TICKER_RETENTION_DAYS = 7L
        // 1분봉은 고카디널리티 → 무한 증가의 주원인. 상위 타임프레임은 행이 적어 길게 보존.
        private const val MINUTE_CANDLE_RETENTION_DAYS = 30L
        private const val MINUTE_INTERVAL = 1
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    fun cleanupOldData() {
        val tickerCutoff = Instant.now().minus(TICKER_RETENTION_DAYS, ChronoUnit.DAYS)
        log.info("Cleaning up tickers older than {}", tickerCutoff)
        marketTickerRepository.deleteOlderThan(tickerCutoff)
            .subscribe(
                { count -> log.info("Deleted {} old ticker records", count) },
                { e -> log.warn("Ticker retention cleanup failed: {}", e.message) },
            )

        val candleCutoff = Instant.now().minus(MINUTE_CANDLE_RETENTION_DAYS, ChronoUnit.DAYS)
        log.info("Cleaning up 1m candles older than {}", candleCutoff)
        marketCandleRepository.deleteByIntervalOlderThan(MINUTE_INTERVAL, candleCutoff)
            .subscribe(
                { count -> log.info("Deleted {} old 1m candle records", count) },
                { e -> log.warn("Candle retention cleanup failed: {}", e.message) },
            )
    }
}
