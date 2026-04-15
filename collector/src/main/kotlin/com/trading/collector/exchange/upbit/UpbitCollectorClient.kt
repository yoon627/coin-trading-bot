package com.trading.collector.exchange.upbit

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
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Component
class UpbitCollectorClient(
    private val upbitWebClient: WebClient,
    private val objectMapper: ObjectMapper,
) : ExchangeClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override val exchange: Exchange = Exchange.UPBIT
    override val assetType: AssetType = AssetType.CRYPTO

    companion object {
        private const val WS_URL = "wss://api.upbit.com/websocket/v1"
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
    }

    override fun tickerFlow(markets: List<String>): Flow<NormalizedTicker> = callbackFlow {
        val connected = AtomicBoolean(false)
        val reconnectAttempts = AtomicInteger(0)
        val running = AtomicBoolean(true)

        fun connect() {
            if (!running.get()) return

            val httpClient = HttpClient.create()
            httpClient
                .websocket(WebsocketClientSpec.builder().maxFramePayloadLength(65536).build())
                .uri(URI.create(WS_URL))
                .handle { inbound, outbound ->
                    connected.set(true)
                    reconnectAttempts.set(0)
                    log.info("Upbit WebSocket connected. Markets: {}", markets)

                    val subscriptionMessage = buildSubscriptionMessage(markets)
                    val sendMono = outbound.sendString(reactor.core.publisher.Flux.just(subscriptionMessage)).then()

                    val receiveMono = inbound.receive()
                        .asByteArray()
                        .map { bytes -> String(bytes, StandardCharsets.UTF_8) }
                        .doOnNext { message -> parseTickerMessage(message)?.let { trySend(it) } }
                        .doOnError { e -> log.warn("Upbit WS receive error: {}", e.message) }
                        .then()

                    sendMono.then(receiveMono)
                }
                .doOnError { e ->
                    log.warn("Upbit WS connection error: {}", e.message)
                    connected.set(false)
                }
                .doFinally {
                    connected.set(false)
                    if (running.get()) {
                        val attempt = reconnectAttempts.incrementAndGet()
                        val delay = (BASE_RECONNECT_DELAY_MS * (1L shl minOf(attempt - 1, 5)))
                            .coerceAtMost(MAX_RECONNECT_DELAY_MS)
                        log.info("Upbit WS reconnecting in {}ms (attempt {})", delay, attempt)
                        Thread.sleep(delay)
                        connect()
                    }
                }
                .subscribe()
        }

        connect()

        awaitClose {
            running.set(false)
            log.info("Upbit ticker flow closed")
        }
    }

    override suspend fun getCandles(market: String, interval: CandleInterval, count: Int): List<NormalizedCandle> {
        val upbitMarket = MarketPair.toUpbitFormat(market)
        val normalized = market

        val candles: List<JsonNode> = when {
            interval.minutes >= CandleInterval.D1.minutes -> {
                upbitWebClient.get()
                    .uri("/v1/candles/days?market={market}&count={count}", upbitMarket, count)
                    .retrieve()
                    .bodyToMono<List<JsonNode>>()
                    .awaitSingle()
            }
            else -> {
                upbitWebClient.get()
                    .uri("/v1/candles/minutes/{unit}?market={market}&count={count}", interval.minutes, upbitMarket, count)
                    .retrieve()
                    .bodyToMono<List<JsonNode>>()
                    .awaitSingle()
            }
        }

        return candles.map { node ->
            val dateTimeStr = node["candle_date_time_utc"].asText()
            val openTime = LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.of("UTC"))
                .toInstant()

            NormalizedCandle(
                exchange = Exchange.UPBIT,
                market = normalized,
                openPrice = node["opening_price"].asDouble(),
                highPrice = node["high_price"].asDouble(),
                lowPrice = node["low_price"].asDouble(),
                closePrice = node["trade_price"].asDouble(),
                volume = node["candle_acc_trade_volume"].asDouble(),
                quoteVolume = node["candle_acc_trade_price"]?.asDouble() ?: 0.0,
                interval = interval,
                openTime = openTime,
                closeTime = openTime.plusSeconds(interval.minutes * 60L),
            )
        }
    }

    private fun buildSubscriptionMessage(markets: List<String>): String {
        val upbitCodes = markets.map { MarketPair.toUpbitFormat(it) }
        val ticket = mapOf("ticket" to UUID.randomUUID().toString())
        val type = mapOf(
            "type" to "ticker",
            "codes" to upbitCodes,
            "isOnlyRealtime" to true,
        )
        return objectMapper.writeValueAsString(listOf(ticket, type))
    }

    private fun parseTickerMessage(message: String): NormalizedTicker? {
        return try {
            val node = objectMapper.readTree(message)
            if (!node.has("type") || node["type"].asText() != "ticker") return null

            val upbitCode = node["code"].asText()
            NormalizedTicker(
                exchange = Exchange.UPBIT,
                market = MarketPair.normalize(Exchange.UPBIT, upbitCode),
                price = node["trade_price"].asDouble(),
                bidPrice = node["highest_bid_price"]?.asDouble() ?: 0.0,
                askPrice = node["lowest_ask_price"]?.asDouble() ?: 0.0,
                volume24h = node["acc_trade_volume_24h"]?.asDouble() ?: 0.0,
                quoteVolume24h = node["acc_trade_price_24h"]?.asDouble() ?: 0.0,
                changeRate24h = node["signed_change_rate"]?.asDouble() ?: 0.0,
                highPrice24h = node["high_price"]?.asDouble() ?: 0.0,
                lowPrice24h = node["low_price"]?.asDouble() ?: 0.0,
                timestamp = Instant.ofEpochMilli(node["timestamp"]?.asLong() ?: System.currentTimeMillis()),
            )
        } catch (e: Exception) {
            null
        }
    }
}
