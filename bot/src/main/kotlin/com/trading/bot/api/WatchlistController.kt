package com.trading.bot.api

import com.trading.bot.config.WatchlistProperties
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.MarketTickerRepository
import com.trading.bot.persistence.entity.MarketTickerEntity
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.domain.NormalizedTicker
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

                // 현재값은 메모리 스냅샷을 먼저 본다. DB 는 10 tick 마다만 저장하므로 거래가 드문
                // 종목은 1시간 창에 행이 없을 수 있는데, 그렇다고 목록에서 빠지면 안 된다.
                // 메모리도 비어 있는 경우(재시작 직후 — WS 는 isOnlyRealtime 이라 초기 스냅샷이
                // 없다) DB 의 마지막 기록으로 폴백한다. 구 REST 수집은 5분마다 무조건 기록해
                // 항상 노출됐으므로, 둘 다 없을 때만 제외해야 회귀가 아니다.
                val latest = marketDataStore.getLatestTicker(Exchange.UPBIT, normalized)?.toView()
                    ?: marketTickerRepository.findRecent(EXCHANGE, normalized, 1)
                        .next().awaitSingleOrNull()?.toView()
                    ?: return@mapNotNull null

                // 1h 기준점만 DB 에서 1건 읽는다. 기존 구현은 창에 **2건 이상**일 때만 변화율을
                // 냈으므로(`snapshots.size > 1`), 관측이 하나뿐이면 null 이어야 한다. 기준점이
                // 현재값과 **같은 행**이면(메모리가 비어 DB 폴백을 탄 경우) 비교 대상이 없다.
                // recorded_at 은 ms 라 서로 다른 관측이 같은 시각을 가질 수 있어 행 id 로 판정한다.
                // 메모리에서 온 현재값은 id 가 없으므로 항상 다른 관측으로 본다.
                val oldest = marketTickerRepository
                    .findOldestInRange(EXCHANGE, normalized, oneHourAgo, now)
                    .awaitSingleOrNull()
                val hourChange = oldest
                    ?.takeIf { it.price > 0 && (latest.sourceRowId == null || it.id != latest.sourceRowId) }
                    ?.let { ((latest.price - it.price) / it.price) * 100.0 }

                mapOf(
                    "ticker" to ticker,
                    "currency" to ticker.substringAfter("-"),
                    "price" to latest.price,
                    "high_price" to latest.high,
                    "low_price" to latest.low,
                    "change_24h" to latest.changeRate * 100,
                    "change_1h" to hourChange,
                    "volume_24h" to latest.quoteVolume,
                    // price_snapshots 는 KST LocalDateTime 을 저장했다. 표현이 바뀌지 않도록 KST 로 맞춘다.
                    "updated_at" to latest.at.atZone(kst).toLocalDateTime().toString(),
                )
            } catch (_: Exception) { null }
        }.sortedByDescending { (it["volume_24h"] as? Double) ?: 0.0 }

        return mapOf("coins" to items)
    }

    /** 메모리 스냅샷과 DB 행이 같은 모양이 아니라서, 응답 조립 전에 한 형태로 모은다. */
    private data class TickerView(
        val price: Double,
        val high: Double,
        val low: Double,
        val changeRate: Double,
        val quoteVolume: Double,
        val at: Instant,
        /** DB 행에서 왔을 때의 식별자. 메모리 스냅샷이면 null. */
        val sourceRowId: Long? = null,
    )

    private fun NormalizedTicker.toView() =
        TickerView(price, highPrice24h, lowPrice24h, changeRate24h, quoteVolume24h, timestamp)

    // market_tickers 는 nullable 컬럼이다 — price_snapshots 는 non-null 이었으므로 0.0 으로 메워
    // 응답에 null 이 새로 등장하지 않게 한다.
    private fun MarketTickerEntity.toView() = TickerView(
        price = price,
        high = highPrice24h ?: 0.0,
        low = lowPrice24h ?: 0.0,
        changeRate = changeRate24h ?: 0.0,
        quoteVolume = quoteVolume24h ?: 0.0,
        at = recordedAt,
        sourceRowId = id,
    )

    private companion object {
        // watchlist 는 Upbit WS 경로 전용이다 — MarketDataIngestionService 가 같은 목록을 구독한다.
        const val EXCHANGE = "UPBIT"
    }
}
