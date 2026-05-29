@file:Suppress("DEPRECATION")

package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.config.WatchlistProperties
import com.trading.bot.persistence.PriceSnapshotRepository
import com.trading.bot.persistence.entity.PriceSnapshotEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class PriceCollector(
    private val upbitClient: UpbitClient,
    private val priceSnapshotRepository: PriceSnapshotRepository,
    private val watchlistProperties: WatchlistProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val kst = ZoneId.of("Asia/Seoul")
    private val scope = CoroutineScope(Dispatchers.IO)

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul") // 매일 KST 새벽 3시 - 7일 이전 스냅샷 정리
    fun cleanupOldSnapshots() {
        scope.launch {
            try {
                val cutoff = LocalDateTime.now(kst).minusDays(7)
                val deleted = priceSnapshotRepository.deleteByCapturedAtBefore(cutoff)
                    .awaitSingleOrNull() ?: 0
                if (deleted > 0) {
                    log.info("Cleaned up {} old price snapshots (before {})", deleted, cutoff)
                }
            } catch (e: Exception) {
                log.warn("Failed to cleanup old snapshots: {}", e.message)
            }
        }
    }

    @Scheduled(fixedRate = 300_000, initialDelay = 10_000)
    fun collectPrices() {
        val tickers = watchlistProperties.tickerList()
        if (tickers.isEmpty()) return

        scope.launch {
            try {
                val markets = tickers.joinToString(",")
                val tickerData = upbitClient.getTicker(markets)
                val now = LocalDateTime.now(kst)

                for (t in tickerData) {
                    val entity = PriceSnapshotEntity(
                        ticker = t.market,
                        price = t.tradePrice,
                        highPrice = t.highPrice,
                        lowPrice = t.lowPrice,
                        tradeVolume = t.accTradeVolume24h,
                        accTradePrice24h = t.accTradePrice24h,
                        signedChangeRate = t.signedChangeRate,
                        capturedAt = now,
                    )
                    priceSnapshotRepository.save(entity).subscribe()
                }
                log.debug("Collected price snapshots for {} tickers", tickerData.size)
            } catch (e: Exception) {
                log.warn("Failed to collect prices: {}", e.message)
            }
        }
    }
}
