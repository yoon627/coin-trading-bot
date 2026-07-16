package com.trading.bot.api

import com.trading.bot.client.UpbitWebSocketClient
import com.trading.bot.config.WatchlistProperties
import com.trading.bot.domain.RealtimePrice
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
    private val webSocketClient: UpbitWebSocketClient,
    private val watchlistProperties: WatchlistProperties,
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

        if (!requested.isNullOrEmpty()) {
            webSocketClient.subscribe(requested)
        }

        return webSocketClient.priceFlow()
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
        // 미인증 공개 — 항상 watchlist 로만 제한(매매용 폴백이 유입시킨 타 티커 노출 차단).
        val all = webSocketClient.allLatestPrices().filterKeys { it in allowedTickers }
        if (tickers.isNullOrEmpty()) return all
        val tickerSet = tickers.map { it.uppercase() }.toSet()
        return all.filterKeys { it in tickerSet }
    }

    @GetMapping("/status")
    fun getConnectionStatus(): Map<String, Any> {
        return mapOf(
            "connected" to webSocketClient.isConnected(),
            "tickers" to webSocketClient.allLatestPrices().keys.filter { it in allowedTickers }.toSet(),
        )
    }
}
