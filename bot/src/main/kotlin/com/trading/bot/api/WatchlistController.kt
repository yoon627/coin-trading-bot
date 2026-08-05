package com.trading.bot.api

import com.trading.bot.config.WatchlistProperties
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.MarketTickerRepository
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneId

@RestController
@RequestMapping("/api/watchlist")
class WatchlistController(
    private val marketDataStore: MarketDataStore,
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
                val normalized = MarketPair.normalize(Exchange.UPBIT, ticker)

                // 현재값은 **메모리 스냅샷**에서 읽는다. DB 는 10 tick 마다만 저장하므로 거래가
                // 드문 종목은 1시간 안에 행이 없을 수 있는데, 그렇다고 목록에서 빠지면 안 된다
                // (구 REST 수집은 5분마다 무조건 기록해 항상 노출됐다).
                val latest = marketDataStore.getLatestTicker(Exchange.UPBIT, normalized)
                    ?: return@mapNotNull null

                // 1h 기준점만 DB 에서 1건 읽는다. 없으면 비교 대상이 없으니 변화율은 null —
                // 구현 전환 전에도 창에 1건뿐이면 null 이었다.
                val oldest = marketTickerRepository
                    .findOldestInRange(EXCHANGE, normalized, oneHourAgo, now)
                    .awaitSingleOrNull()
                val hourChange = oldest
                    ?.takeIf { it.price > 0 && it.price != latest.price }
                    ?.let { ((latest.price - it.price) / it.price) * 100.0 }

                mapOf(
                    "ticker" to ticker,
                    "currency" to ticker.substringAfter("-"),
                    "price" to latest.price,
                    "high_price" to latest.highPrice24h,
                    "low_price" to latest.lowPrice24h,
                    "change_24h" to latest.changeRate24h * 100,
                    "change_1h" to hourChange,
                    "volume_24h" to latest.quoteVolume24h,
                    // price_snapshots 는 KST LocalDateTime 을, NormalizedTicker 는 Instant 를 쓴다.
                    // 응답 표현이 바뀌지 않도록 KST 로 변환해 같은 형식으로 넘긴다.
                    "updated_at" to latest.timestamp.atZone(kst).toLocalDateTime().toString(),
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
