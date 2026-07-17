package com.trading.bot.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.config.MarketDataWatchdogProperties
import com.trading.bot.config.WatchlistProperties
import com.trading.bot.domain.RealtimePrice
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
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
class UpbitWebSocketClient(
    private val watchlistProperties: WatchlistProperties,
    private val watchdogProperties: MarketDataWatchdogProperties,
    // autoConnect=false 는 테스트 seam — 구독 목록만 갱신하고 실제 WS 연결은 열지 않는다. 운영은 항상 true.
    private val autoConnect: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()
    private val sink = Sinks.many().multicast().onBackpressureBuffer<RealtimePrice>(256)
    private val latestPrices = ConcurrentHashMap<String, RealtimePrice>()
    private val connected = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)

    // 구독 대상 = watchlist(항상 구독) ∪ engine 활성 티커(ref-count). 전역 싱글턴이라 여러 engine 이
    // 같은 티커를 공유할 수 있어 ref-count 로 세고, 마지막 engine 이 stop 할 때만 실제 구독 해제한다.
    private val baselineTickers = ConcurrentHashMap.newKeySet<String>()
    private val refCounts = ConcurrentHashMap<String, Int>()
    private val subscriptionLock = Any()

    // 연결 세대: startConnection 이 새 연결을 열 때마다 증가. 무효화된(낡은) 세대의
    // doFinally/scheduleReconnect 는 상태 변경·재연결을 하지 않아 중복 connect·이중 수신을 막는다.
    private val generation = AtomicInteger(0)

    // 마지막 WS 프레임 수신 시각 — half-open(무수신) 워치독 판정용. (re)connect 시 now 로 리셋해 폭주 방지.
    @Volatile
    private var lastMessageAt = 0L

    // subscribe/reconnect/connect 직렬화 — 동시 호출이 다중 연결을 만들지 않도록.
    private val connectionLock = Any()

    @Volatile
    private var disposable: reactor.core.Disposable? = null

    companion object {
        private const val WS_URL = "wss://api.upbit.com/websocket/v1"
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val BASE_RECONNECT_DELAY_MS = 1_000L

        // 이 연결(myGen)이 여전히 최신 세대이고 종료 중이 아닐 때만 doFinally/scheduleReconnect 가 동작한다.
        internal fun shouldActForGeneration(myGen: Int, currentGen: Int, shuttingDown: Boolean): Boolean =
            myGen == currentGen && !shuttingDown
    }

    @PostConstruct
    fun init() {
        baselineTickers.addAll(watchlistProperties.tickerList())
        if (autoConnect && activeTickers().isNotEmpty()) reconnect()
    }

    @PreDestroy
    fun destroy() {
        // 먼저 shutdown 플래그 + 세대 증가로 진행 중 재연결 경로를 무효화한 뒤 현재 연결 정리.
        shuttingDown.set(true)
        connected.set(false)
        generation.incrementAndGet()
        disposable?.dispose()
    }

    // engine 이 활성화한 티커를 구독한다(ref-count 증가). 새로 추가된 티커가 있으면 재연결로 구독 메시지 갱신.
    fun subscribe(tickers: List<String>) {
        if (shuttingDown.get()) return
        val added = synchronized(subscriptionLock) {
            var changed = false
            for (t in tickers) {
                val before = refCounts.getOrDefault(t, 0)
                refCounts[t] = before + 1
                if (before == 0 && t !in baselineTickers) changed = true
            }
            changed
        }
        if (added) {
            log.info("Subscribing to tickers: {}", tickers)
            if (autoConnect) reconnect()
        }
    }

    // engine stop 시 호출. ref-count 를 낮추고 0 이 된(watchlist 밖) 티커만 실제 구독에서 제거한다.
    fun unsubscribe(tickers: List<String>) {
        if (shuttingDown.get()) return
        val removed = synchronized(subscriptionLock) {
            var changed = false
            for (t in tickers) {
                val before = refCounts.getOrDefault(t, 0)
                when {
                    before > 1 -> refCounts[t] = before - 1
                    before == 1 -> {
                        refCounts.remove(t)
                        if (t !in baselineTickers) changed = true
                    }
                    // before == 0: 구독 이력 없음 — 무시
                }
            }
            changed
        }
        if (removed) {
            log.info("Unsubscribing idle tickers: {}", tickers)
            if (autoConnect) reconnect()
        }
    }

    internal fun subscribedMarkets(): Set<String> = activeTickers()

    private fun activeTickers(): Set<String> {
        val result = HashSet(baselineTickers)
        result.addAll(refCounts.keys)
        return result
    }

    fun priceFlow(): Flux<RealtimePrice> = sink.asFlux()

    fun latestPrice(ticker: String): RealtimePrice? = latestPrices[ticker]

    fun allLatestPrices(): Map<String, RealtimePrice> = latestPrices.toMap()

    fun isConnected(): Boolean = connected.get()

    // half-open(TCP 살아있으나 무수신) 자동복구: 임계 초과 시 현재 연결을 끊어 doFinally→scheduleReconnect 로
    // 재연결시킨다. 매매 정확성은 TradingEngine staleness 게이팅이 이미 보호하므로 여기선 재연결 폭주만 막으면 되고,
    // 오판(저유동 마켓 등) 시 enabled 로 런타임 비활성할 수 있다.
    @Scheduled(
        fixedDelayString = "\${marketdata.watchdog.interval-ms:20000}",
        initialDelayString = "\${marketdata.watchdog.initial-delay-ms:30000}",
    )
    fun checkConnectionHealth() {
        if (!watchdogProperties.enabled || shuttingDown.get()) return
        if (!connected.get() || activeTickers().isEmpty()) return
        if (watchdogProperties.isStale(System.currentTimeMillis(), lastMessageAt)) {
            log.warn("WS half-open: no frame for >{}ms; forcing reconnect", watchdogProperties.staleMs)
            synchronized(connectionLock) {
                if (!shuttingDown.get()) disposable?.dispose()
            }
        }
    }

    private fun reconnect() {
        synchronized(connectionLock) {
            if (shuttingDown.get()) return
            startConnection()
        }
    }

    // connectionLock 을 보유한 상태에서만 호출. 새 세대를 부여하고 구 연결을 정리한 뒤 연결한다.
    private fun startConnection() {
        if (shuttingDown.get()) return
        val myGen = generation.incrementAndGet() // 구 연결 무효화 (dispose 이전에 증가)
        disposable?.dispose()
        // 구독 대상이 모두 빠졌으면(마지막 unsubscribe·watchlist 공백) 구 소켓만 정리하고 새로 연결하지 않는다.
        // 구 소켓 doFinally 는 세대 게이팅으로 no-op 이므로 connected 를 여기서 직접 내린다(status 오표기 방지).
        if (activeTickers().isEmpty()) {
            connected.set(false)
            return
        }
        lastMessageAt = System.currentTimeMillis() // reset-on-connect grace (워치독 폭주 방지)
        connect(myGen)
    }

    private fun connect(myGen: Int) {
        val httpClient = HttpClient.create()
        disposable = httpClient
            .websocket(WebsocketClientSpec.builder().maxFramePayloadLength(65536).build())
            .uri(URI.create(WS_URL))
            .handle { inbound, outbound ->
                if (shouldActForGeneration(myGen, generation.get(), shuttingDown.get())) {
                    connected.set(true)
                    reconnectAttempts.set(0)
                    log.info("WebSocket connected to Upbit. Subscribing to {} tickers.", activeTickers().size)
                }
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
                if (shouldActForGeneration(myGen, generation.get(), shuttingDown.get())) connected.set(false)
            }
            .doFinally {
                if (shouldActForGeneration(myGen, generation.get(), shuttingDown.get())) {
                    connected.set(false)
                    scheduleReconnect(myGen)
                }
            }
            .subscribe()
    }

    private fun buildSubscriptionMessage(): String {
        val ticket = mapOf("ticket" to UUID.randomUUID().toString())
        val type = mapOf(
            "type" to "ticker",
            "codes" to activeTickers().toList(),
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

            lastMessageAt = System.currentTimeMillis()
            latestPrices[price.market] = price
            val result = sink.tryEmitNext(price)
            // FAIL_ZERO_SUBSCRIBER 는 SSE 구독자가 없을 때 multicast sink 가 정상 drop 하는 케이스 — 가격은
            // latestPrices 에 저장되므로 운영 영향 없음. 실제 backpressure/overflow 만 가시화.
            if (result.isFailure && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                log.warn("Dropped realtime price for {} (sink emit {})", price.market, result)
            }
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            log.debug("Skipped non-parsable WS frame: {}", e.message)
        } catch (e: Exception) {
            log.warn("Failed to process WS message: {}", e.message)
        }
    }

    private fun scheduleReconnect(deadGen: Int) {
        if (shuttingDown.get() || activeTickers().isEmpty()) return
        if (deadGen != generation.get()) return // 이미 새 연결로 대체됨

        val attempt = reconnectAttempts.incrementAndGet()
        val delay = (BASE_RECONNECT_DELAY_MS * (1L shl minOf(attempt - 1, 5)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)
        log.info("Scheduling WebSocket reconnect in {}ms (attempt {})", delay, attempt)

        Thread {
            try {
                Thread.sleep(delay)
                synchronized(connectionLock) {
                    // sleep 중 새 연결(더 높은 세대)이 떴으면 재연결하지 않는다.
                    if (!shuttingDown.get() && deadGen == generation.get() && !connected.get()) {
                        startConnection()
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
