package com.trading.bot.stream

import com.trading.bot.persistence.MarketTickerRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class DataRetentionService(
    private val marketTickerRepository: MarketTickerRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val TICKER_RETENTION_DAYS = 7L
    }

    @Scheduled(cron = "0 0 3 * * *")
    fun cleanupOldTickers() {
        val cutoff = Instant.now().minus(TICKER_RETENTION_DAYS, ChronoUnit.DAYS)
        log.info("Cleaning up tickers older than {}", cutoff)
        marketTickerRepository.deleteOlderThan(cutoff)
            .subscribe { count -> log.info("Deleted {} old ticker records", count) }
    }
}
