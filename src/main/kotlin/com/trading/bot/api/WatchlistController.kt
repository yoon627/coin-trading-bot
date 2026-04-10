package com.trading.bot.api

import com.trading.bot.config.ClaudeProperties
import com.trading.bot.persistence.PriceSnapshotRepository
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import java.time.ZoneId

@RestController
@RequestMapping("/api/watchlist")
class WatchlistController(
    private val priceSnapshotRepository: PriceSnapshotRepository,
    private val claudeProperties: ClaudeProperties,
) {
    private val kst = ZoneId.of("Asia/Seoul")

    @GetMapping
    suspend fun getWatchlist(): Map<String, Any> {
        val tickers = claudeProperties.tickerList()
        val now = LocalDateTime.now(kst)
        val oneHourAgo = now.minusHours(1)

        val items = tickers.mapNotNull { ticker ->
            try {
                val snapshots = priceSnapshotRepository
                    .findByTickerAndCapturedAtBetweenOrderByCapturedAtDesc(ticker, oneHourAgo, now)
                    .collectList()
                    .awaitSingle()

                if (snapshots.isEmpty()) return@mapNotNull null

                val latest = snapshots.first()
                val oldest = snapshots.last()
                val hourChange = if (oldest.price > 0 && snapshots.size > 1) {
                    ((latest.price - oldest.price) / oldest.price) * 100.0
                } else null

                mapOf(
                    "ticker" to ticker,
                    "currency" to ticker.substringAfter("-"),
                    "price" to latest.price,
                    "high_price" to latest.highPrice,
                    "low_price" to latest.lowPrice,
                    "change_24h" to latest.signedChangeRate * 100,
                    "change_1h" to hourChange,
                    "volume_24h" to latest.accTradePrice24h,
                    "updated_at" to latest.capturedAt.toString(),
                )
            } catch (_: Exception) { null }
        }.sortedByDescending { (it["volume_24h"] as? Double) ?: 0.0 }

        return mapOf("coins" to items)
    }
}
