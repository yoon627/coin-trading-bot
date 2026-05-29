package com.trading.bot.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.domain.RealtimePrice
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.WebsocketClientSpec
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Component
class UpbitWebSocketClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()
    private val sink = Sinks.many().multicast().onBackpressureBuffer<RealtimePrice>(256)
    private val latestPrices = ConcurrentHashMap<String, RealtimePrice>()
    private val connected = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val subscribedTickers = ConcurrentHashMap.newKeySet<String>()

    // subscribe/reconnect/connect 직렬화 — 동시 호출이 다중 연결을 만들지 않도록.
    private val connectionLock = Any()

    @Volatile
    private var disposable: reactor.core.Disposable? = null

    companion object {
        private const val WS_URL = "wss://api.upbit.com/websocket/v1"
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
    }

    @PostConstruct
    fun init() {
        // Default tickers to subscribe on startup
        subscribe(listOf("KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL"))
    }

    @PreDestroy
    fun destroy() {
        // 먼저 shutdown 플래그를 세워 doFinally→scheduleReconnect 가 재연결하지 못하게 한다.
        shuttingDown.set(true)
        connected.set(false)
        disposable?.dispose()
    }

    fun subscribe(tickers: List<String>) {
        if (shuttingDown.get()) return
        val newTickers = tickers.filter { subscribedTickers.add(it) }
        if (newTickers.isNotEmpty()) {
            log.info("Subscribing to tickers: {}", newTickers)
            reconnect()
        }
    }

    fun priceFlow(): Flux<RealtimePrice> = sink.asFlux()

    fun latestPrice(ticker: String): RealtimePrice? = latestPrices[ticker]

    fun allLatestPrices(): Map<String, RealtimePrice> = latestPrices.toMap()

    fun isConnected(): Boolean = connected.get()

    private fun reconnect() {
        synchronized(connectionLock) {
            if (shuttingDown.get()) return
            disposable?.dispose()
            connect()
        }
    }

    private fun connect() {
        if (shuttingDown.get() || subscribedTickers.isEmpty()) return

        val uri = URI.create(WS_URL)
        val httpClient = HttpClient.create()

        disposable = httpClient
            .websocket(WebsocketClientSpec.builder().maxFramePayloadLength(65536).build())
            .uri(uri)
            .handle { inbound, outbound ->
                connected.set(true)
                reconnectAttempts.set(0)
                log.info("WebSocket connected to Upbit. Subscribing to {} tickers.", subscribedTickers.size)

                val subscriptionMessage = buildSubscriptionMessage()
                val sendMono = outbound.sendString(Flux.just(subscriptionMessage)).then()

                val receiveMono = inbound.receive()
                    .asByteArray()
                    .map { bytes -> String(bytes, StandardCharsets.UTF_8) }
                    .doOnNext { message -> processMessage(message) }
                    .doOnError { e -> log.warn("WebSocket receive error: {}", e.message) }
                    .then()

                sendMono.then(receiveMono)
            }
            .doOnError { e ->
                log.warn("WebSocket connection error: {}", e.message)
                connected.set(false)
            }
            .doFinally {
                connected.set(false)
                scheduleReconnect()
            }
            .subscribe()
    }

    private fun buildSubscriptionMessage(): String {
        val ticket = mapOf("ticket" to UUID.randomUUID().toString())
        val type = mapOf(
            "type" to "ticker",
            "codes" to subscribedTickers.toList(),
            "isOnlyRealtime" to true,
        )
        return objectMapper.writeValueAsString(listOf(ticket, type))
    }

    private fun processMessage(message: String) {
        try {
            val node: JsonNode = objectMapper.readTree(message)
            if (!node.has("type") || node["type"].asText() != "ticker") return

            val price = RealtimePrice(
                market = node["code"].asText(),
                tradePrice = node["trade_price"].asDouble(),
                signedChangeRate = node["signed_change_rate"]?.asDouble() ?: 0.0,
                accTradePrice24h = node["acc_trade_price_24h"]?.asDouble() ?: 0.0,
                highPrice = node["high_price"]?.asDouble() ?: 0.0,
                lowPrice = node["low_price"]?.asDouble() ?: 0.0,
                timestamp = node["timestamp"]?.asLong() ?: System.currentTimeMillis(),
            )

            latestPrices[price.market] = price
            val result = sink.tryEmitNext(price)
            if (result.isFailure) {
                log.warn("Dropped realtime price for {} (sink emit {})", price.market, result)
            }
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            // 비-ticker/연결 ACK 등 파싱 불가 프레임 — 흔하므로 debug 로만.
            log.debug("Skipped non-parsable WS frame: {}", e.message)
        } catch (e: Exception) {
            // 스키마 변경·예상치 못한 구조는 가시화해야 디버깅 가능.
            log.warn("Failed to process WS message: {}", e.message)
        }
    }

    private fun scheduleReconnect() {
        // shutdown 중이면 재연결하지 않음 (@PreDestroy 후 좀비 연결 방지).
        if (shuttingDown.get() || connected.get() || subscribedTickers.isEmpty()) return

        val attempt = reconnectAttempts.incrementAndGet()
        val delay = (BASE_RECONNECT_DELAY_MS * (1L shl minOf(attempt - 1, 5)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)
        log.info("Scheduling WebSocket reconnect in {}ms (attempt {})", delay, attempt)

        Thread {
            try {
                Thread.sleep(delay)
                synchronized(connectionLock) {
                    if (!connected.get() && !shuttingDown.get()) {
                        connect()
                    }
                }
            } catch (_: InterruptedException) {
                // shutdown
            }
        }.apply {
            isDaemon = true
            start()
        }
    }
}
