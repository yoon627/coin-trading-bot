package com.trading.bot.api

import com.trading.bot.config.WatchlistProperties
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.MarketCandleRepository
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
    private val marketCandleRepository: MarketCandleRepository,
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

                // 1h 기준점은 **1분봉**에서 얻는다. market_tickers 는 종목별 10 tick 마다만
                // 저장돼 거래가 드문 종목은 1시간 창에 행이 0~1건뿐이라 변화율을 만들 수 없다 —
                // 구 REST 수집(5분 주기)은 거래량과 무관하게 기록했으므로 그대로 두면 회귀다.
                // 캔들은 60초 REST 폴링이라 거래량과 무관하게 채워진다([[marketdata-pipeline]]).
                // 창의 첫 봉(가장 오래된 것)의 종가가 1시간 전 가격이다.
                val baseline = marketCandleRepository
                    .findByTimeRange(EXCHANGE, normalized, BASELINE_INTERVAL_MINUTES, oneHourAgo, now)
                    .next().awaitSingleOrNull()
                val hourChange = baseline?.closePrice
                    ?.takeIf { it > 0 }
                    ?.let { ((latest.price - it) / it) * 100.0 }

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
    )

    private companion object {
        // watchlist 는 Upbit WS 경로 전용이다 — MarketDataIngestionService 가 같은 목록을 구독한다.
        const val EXCHANGE = "UPBIT"

        // 1분봉 — 1시간 창의 첫 봉을 기준가로 쓴다.
        const val BASELINE_INTERVAL_MINUTES = 1
    }
}
