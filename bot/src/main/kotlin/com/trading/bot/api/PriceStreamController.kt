package com.trading.bot.api

import com.trading.bot.config.MarketDataWatchdogProperties
import com.trading.bot.config.WatchlistProperties
import com.trading.bot.domain.RealtimePrice
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.domain.NormalizedTicker
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import java.time.Duration

@RestController
@RequestMapping("/api/prices")
class PriceStreamController(
    private val marketDataStore: MarketDataStore,
    private val watchlistProperties: WatchlistProperties,
    private val watchdogProperties: MarketDataWatchdogProperties,
) {
    companion object {
        private const val MAX_STREAM_TICKERS = 30
    }

    // 미인증 공개 엔드포인트가 전역 WS 구독을 임의로 늘리지 못하도록 watchlist 로만 제한.
    private val allowedTickers: Set<String> by lazy {
        watchlistProperties.tickerList().map { it.uppercase() }.toSet()
    }

    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamPrices(
        @RequestParam(required = false) tickers: List<String>?,
    ): Flux<ServerSentEvent<RealtimePrice>> {
        // 요청 ticker 를 정규화 + allowlist 교집합 + 개수 상한 → 자원 고갈/임의 구독 주입 차단.
        val requested = tickers
            ?.asSequence()
            ?.map { it.trim().uppercase() }
            ?.filter { it in allowedTickers }
            ?.distinct()
            ?.take(MAX_STREAM_TICKERS)
            ?.toList()
        // 요청 미지정 시 watchlist 전체로 제한 — 미인증 공개 스트림이 매매용 폴백 구독을 노출하지 않도록.
        val tickerSet = requested?.toSet() ?: allowedTickers

        // 상시 수집(UpbitMarketFeed→MarketDataStore) 스트림을 구독만 한다 — 별도 WS 연결 없음.
        // 요청 티커는 항상 watchlist ⊆ 상시 수집 대상이라 별도 구독 트리거가 불필요.
        return marketDataStore.tickerStream()
            .filter { it.exchange == Exchange.UPBIT }
            .map { it.toRealtimePrice() }
            .filter { price -> price.market in tickerSet }
            .sample(Duration.ofMillis(500))
            .map { price ->
                ServerSentEvent.builder(price)
                    .event("price")
                    .id(price.market)
                    .build()
            }
    }

    @GetMapping("/latest")
    fun getLatestPrices(
        @RequestParam(required = false) tickers: List<String>?,
    ): Map<String, RealtimePrice> {
        // 미인증 공개 — 항상 watchlist 로만 제한(매매용 티커가 유입돼도 노출 차단).
        val all = allowedSnapshot().associateBy { it.market }
        if (tickers.isNullOrEmpty()) return all
        val tickerSet = tickers.map { it.uppercase() }.toSet()
        return all.filterKeys { it in tickerSet }
    }

    @GetMapping("/status")
    fun getConnectionStatus(): Map<String, Any> {
        // 연결 상태 = 소켓 open 여부가 아니라 store 신선도로 판정: watchlist 티커 중 staleMs 내 갱신이
        // 하나라도 있으면 수집이 살아있다고 본다(단일 수집 경로 UpbitMarketFeed→MarketDataStore).
        val now = System.currentTimeMillis()
        val prices = allowedSnapshot()
        val connected = prices.any { now - it.timestamp < watchdogProperties.staleMs }
        return mapOf(
            "connected" to connected,
            "tickers" to prices.map { it.market }.toSet(),
        )
    }

    // store 스냅샷을 watchlist 로 제한한 RealtimePrice 목록 — /latest·/status 공용.
    private fun allowedSnapshot(): List<RealtimePrice> =
        marketDataStore.getTickersByExchange(Exchange.UPBIT)
            .map { it.toRealtimePrice() }
            .filter { it.market in allowedTickers }

    // 정규화 NormalizedTicker(수집 도메인) → RealtimePrice(SSE 응답 DTO). market 은 Upbit 표기("KRW-BTC")로 복원.
    private fun NormalizedTicker.toRealtimePrice() = RealtimePrice(
        market = MarketPair.toUpbitFormat(market),
        tradePrice = price,
        signedChangeRate = changeRate24h,
        accTradePrice24h = quoteVolume24h,
        highPrice = highPrice24h,
        lowPrice = lowPrice24h,
        timestamp = timestamp.toEpochMilli(),
    )
}
