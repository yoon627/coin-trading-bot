package com.trading.collector.exchange.binance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.collector.exchange.ExchangeClient
import com.trading.common.domain.AssetType
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.WebsocketClientSpec
import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Component
class BinanceCollectorClient(
    private val binanceWebClient: WebClient,
    private val objectMapper: ObjectMapper,
) : ExchangeClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override val exchange: Exchange = Exchange.BINANCE
    override val assetType: AssetType = AssetType.CRYPTO

    companion object {
        private const val WS_BASE_URL = "wss://stream.binance.com:9443/ws"
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
    }

    override fun tickerFlow(markets: List<String>): Flow<NormalizedTicker> = callbackFlow {
        val connected = AtomicBoolean(false)
        val reconnectAttempts = AtomicInteger(0)
        val running = AtomicBoolean(true)

        val streams = markets.map { MarketPair.toBinanceFormat(it).lowercase() + "@ticker" }
        val wsUrl = "$WS_BASE_URL/${streams.joinToString("/")}"

        fun connect() {
            if (!running.get()) return

            val httpClient = HttpClient.create()
            httpClient
                .websocket(WebsocketClientSpec.builder().maxFramePayloadLength(65536).build())
                .uri(URI.create(wsUrl))
                .handle { inbound, outbound ->
                    connected.set(true)
                    reconnectAttempts.set(0)
                    log.info("Binance WebSocket connected. Markets: {}", markets)

                    inbound.receive()
                        .asString()
                        .doOnNext { message -> parseTickerMessage(message)?.let { trySend(it) } }
                        .doOnError { e -> log.warn("Binance WS receive error: {}", e.message) }
                        .then(outbound.neverComplete())
                }
                .doOnError { e ->
                    log.warn("Binance WS connection error: {}", e.message)
                    connected.set(false)
                }
                .doFinally {
                    connected.set(false)
                    if (running.get()) {
                        val attempt = reconnectAttempts.incrementAndGet()
                        val delay = (BASE_RECONNECT_DELAY_MS * (1L shl minOf(attempt - 1, 5)))
                            .coerceAtMost(MAX_RECONNECT_DELAY_MS)
                        log.info("Binance WS reconnecting in {}ms (attempt {})", delay, attempt)
                        Thread.sleep(delay)
                        connect()
                    }
                }
                .subscribe()
        }

        connect()

        awaitClose {
            running.set(false)
            log.info("Binance ticker flow closed")
        }
    }

    override suspend fun getCandles(market: String, interval: CandleInterval, count: Int): List<NormalizedCandle> {
        val binanceSymbol = MarketPair.toBinanceFormat(market)
        val binanceInterval = toBinanceInterval(interval)

        val response: List<JsonNode> = binanceWebClient.get()
            .uri("/api/v3/klines?symbol={symbol}&interval={interval}&limit={limit}",
                binanceSymbol, binanceInterval, count)
            .retrieve()
            .bodyToMono<List<JsonNode>>()
            .awaitSingle()

        return response.map { node ->
            NormalizedCandle(
                exchange = Exchange.BINANCE,
                market = market,
                openPrice = node[1].asDouble(),
                highPrice = node[2].asDouble(),
                lowPrice = node[3].asDouble(),
                closePrice = node[4].asDouble(),
                volume = node[5].asDouble(),
                quoteVolume = node[7].asDouble(),
                interval = interval,
                openTime = Instant.ofEpochMilli(node[0].asLong()),
                closeTime = Instant.ofEpochMilli(node[6].asLong()),
            )
        }
    }

    private fun parseTickerMessage(message: String): NormalizedTicker? {
        return try {
            val node = objectMapper.readTree(message)
            val eventType = node["e"]?.asText() ?: return null
            if (eventType != "24hrTicker") return null

            val symbol = node["s"].asText()
            NormalizedTicker(
                exchange = Exchange.BINANCE,
                market = MarketPair.normalize(Exchange.BINANCE, symbol),
                price = node["c"].asDouble(),
                bidPrice = node["b"].asDouble(),
                askPrice = node["a"].asDouble(),
                volume24h = node["v"].asDouble(),
                quoteVolume24h = node["q"].asDouble(),
                changeRate24h = node["P"].asDouble() / 100.0,
                highPrice24h = node["h"].asDouble(),
                lowPrice24h = node["l"].asDouble(),
                timestamp = Instant.ofEpochMilli(node["E"].asLong()),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun toBinanceInterval(interval: CandleInterval): String = when (interval) {
        CandleInterval.M1 -> "1m"
        CandleInterval.M5 -> "5m"
        CandleInterval.M15 -> "15m"
        CandleInterval.H1 -> "1h"
        CandleInterval.H4 -> "4h"
        CandleInterval.D1 -> "1d"
        CandleInterval.W1 -> "1w"
        CandleInterval.MO1 -> "1M"
    }
}
