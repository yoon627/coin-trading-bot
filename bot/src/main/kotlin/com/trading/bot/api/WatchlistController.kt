package com.trading.bot.api

import com.trading.bot.config.WatchlistProperties
import com.trading.bot.persistence.MarketTickerRepository
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneId

@RestController
@RequestMapping("/api/watchlist")
class WatchlistController(
    private val marketTickerRepository: MarketTickerRepository,
    private val watchlistProperties: WatchlistProperties,
) {
    private val kst = ZoneId.of("Asia/Seoul")

    @GetMapping
    suspend fun getWatchlist(): Map<String, Any> {
        val tickers = watchlistProperties.tickerList()
        val now = Instant.now()
        val oneHourAgo = now.minusSeconds(3600)

        val items = tickers.mapNotNull { ticker ->
            try {
                // market_tickers 에는 정규화 형식("BTC/KRW")으로 저장된다 — UpbitMarketFeed 가
                // MarketPair.normalize 를 거쳐 만든다. watchlist 설정은 Upbit 형식("KRW-BTC")이므로
                // 조회 전에 변환해야 한다. 응답의 ticker 는 설정 형식 그대로 돌려준다.
                val recent = marketTickerRepository
                    .findByTimeRange(EXCHANGE, MarketPair.normalize(Exchange.UPBIT, ticker), oneHourAgo, now)
                    .collectList()
                    .awaitSingle()

                if (recent.isEmpty()) return@mapNotNull null

                val latest = recent.first()
                val oldest = recent.last()
                val hourChange = if (oldest.price > 0 && recent.size > 1) {
                    ((latest.price - oldest.price) / oldest.price) * 100.0
                } else null

                mapOf(
                    "ticker" to ticker,
                    "currency" to ticker.substringAfter("-"),
                    "price" to latest.price,
                    // price_snapshots 는 이 값들이 non-null 이었다. market_tickers 는 nullable 이므로
                    // 여기서 메우지 않으면 응답에 null 이 새로 등장해 프론트 계약이 바뀐다.
                    // change_1h 는 원래도 null 을 낼 수 있었으므로 그대로 둔다.
                    "high_price" to (latest.highPrice24h ?: 0.0),
                    "low_price" to (latest.lowPrice24h ?: 0.0),
                    "change_24h" to (latest.changeRate24h ?: 0.0) * 100,
                    "change_1h" to hourChange,
                    "volume_24h" to (latest.quoteVolume24h ?: 0.0),
                    // price_snapshots 는 KST LocalDateTime 을, market_tickers 는 Instant 를 저장한다.
                    // 응답 표현이 바뀌지 않도록 KST 로 변환해 같은 형식으로 넘긴다.
                    "updated_at" to latest.recordedAt.atZone(kst).toLocalDateTime().toString(),
                )
            } catch (_: Exception) { null }
        }.sortedByDescending { (it["volume_24h"] as? Double) ?: 0.0 }

        return mapOf("coins" to items)
    }

    private companion object {
        // watchlist 는 Upbit WS 경로 전용이다 — MarketDataIngestionService 가 같은 목록을 구독한다.
        const val EXCHANGE = "UPBIT"
    }
}
