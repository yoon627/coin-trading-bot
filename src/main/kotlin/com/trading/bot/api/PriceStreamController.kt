package com.trading.bot.api

import com.trading.bot.client.UpbitWebSocketClient
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
) {

    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamPrices(
        @RequestParam(required = false) tickers: List<String>?,
    ): Flux<ServerSentEvent<RealtimePrice>> {
        val tickerSet = tickers?.map { it.uppercase() }?.toSet()

        // Ensure these tickers are subscribed in WebSocket
        if (tickerSet != null) {
            webSocketClient.subscribe(tickerSet.toList())
        }

        return webSocketClient.priceFlow()
            .filter { price -> tickerSet == null || price.market in tickerSet }
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
        val all = webSocketClient.allLatestPrices()
        if (tickers.isNullOrEmpty()) return all
        val tickerSet = tickers.map { it.uppercase() }.toSet()
        return all.filterKeys { it in tickerSet }
    }

    @GetMapping("/status")
    fun getConnectionStatus(): Map<String, Any> {
        return mapOf(
            "connected" to webSocketClient.isConnected(),
            "tickers" to webSocketClient.allLatestPrices().keys,
        )
    }
}
