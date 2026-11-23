package com.trading.bot.marketdata

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import com.trading.common.domain.NormalizedTrade
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.Disposable
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.WebsocketClientSpec
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.time.Duration
import reactor.core.publisher.Mono

/** Upbit ticker·체결(trade)은 WebSocket 한 연결로, 캔들은 REST 로 가져와 정규화한다. */
@Component
class UpbitMarketFeed internal constructor(
    private val upbitWebClient: WebClient,
    private val objectMapper: ObjectMapper,
    private val wsUri: URI,
    private val baseReconnectDelayMs: Long,
    private val pingIntervalMs: Long = PING_INTERVAL_MS,
) {
    // 기본값 파라미터로 두면 같은 타입의 빈이 생겼을 때 조용히 주입되므로 생성자를 따로 둔다.
    @Autowired
    constructor(upbitWebClient: WebClient, objectMapper: ObjectMapper) :
        this(upbitWebClient, objectMapper, URI.create(WS_URL), BASE_RECONNECT_DELAY_MS)

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val WS_URL = "wss://api.upbit.com/websocket/v1"
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
        private const val WARN_INTERVAL_MS = 60_000L

        // 업비트는 120초 동안 주고받은 것이 없으면 닫고, PING 을 받아야 약 10초마다 {"status":"UP"} 을 보낸다(문서·2026-11-23 접속 시험).
        private const val PING_INTERVAL_MS = 30_000L
        private const val PING = "PING"

        // 수집이 10초 멈춰도 실측 최대(BTC 초당 40건) × 13종목을 담는다 — 종목이 늘어도 체결률은 BTC 가 이끈다. 그보다 밀리면 오래된 체결부터 버린다.
        internal const val TRADE_CHANNEL_CAPACITY = 40 * 13 * 10
    }

    /** 마켓별 수를 모아 두고 WARN 은 1분에 한 번만 낸다. 수는 수집 요약이 [drain] 으로 가져가며 비운다. */
    private inner class ThrottledCounts(private val warn: (count: Long, detail: String?) -> Unit) {
        private val counts = ConcurrentHashMap<String, Long>()
        private val sinceWarn = AtomicLong()
        private val lastWarnAt = AtomicLong(-WARN_INTERVAL_MS)

        fun record(market: String?, nowMs: Long, detail: String? = null) {
            if (market != null) counts.merge(market, 1L, Long::plus)
            sinceWarn.incrementAndGet()
            val last = lastWarnAt.get()
            if (nowMs - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, nowMs)) warn(sinceWarn.getAndSet(0), detail)
        }

        // remove 가 값을 원자적으로 가져가므로 그 뒤의 증가는 새 항목으로 남아 다음 drain 에 잡힌다.
        fun drain(): Map<String, Long> = counts.keys.mapNotNull { key -> counts.remove(key)?.let { key to it } }.toMap()
    }

    private val droppedTrades = ThrottledCounts { count, _ ->
        log.warn("Upbit WS collector fell behind; dropped {} trades since the last warning", count)
    }
    private val parseFailures = ThrottledCounts { count, detail ->
        log.warn("Upbit WS dropped {} malformed frames since the last warning (latest: {})", count, detail)
    }

    /**
     * ticker 는 받는 즉시 [onTicker] 로 넘긴다(netty 스레드) — 지금 손절가의 출처라 체결이 밀려도 잃지 않게 체결 버퍼와 나눈다.
     * 체결은 상한 있는 버퍼를 거쳐 flow 로 내보낸다. 구독 뒤 [pingIntervalMs] 마다 PING 을 보내고 UP 응답은 [onAlive] 로 넘긴다 —
     * 거래가 뜸한 마켓만 구독해도 연결이 살아 있음을 안다.
     */
    fun marketFlow(
        markets: List<String>,
        includeTrades: Boolean,
        onTicker: (NormalizedTicker) -> Unit,
        onAlive: () -> Unit = {},
    ): Flow<NormalizedTrade> = flow {
        val connected = AtomicBoolean(false)
        val reconnectAttempts = AtomicInteger(0)
        val running = AtomicBoolean(true)
        val trades = Channel<NormalizedTrade>(TRADE_CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST) { dropped ->
            // 닫을 때 남은 체결도 여기로 오는데 그건 밀려서 버린 것이 아니다.
            if (running.get()) recordDropped(dropped.market, System.currentTimeMillis())
        }
        val disposable = AtomicReference<Disposable?>(null)
        val reconnectThread = AtomicReference<Thread?>(null)

        fun connect() {
            if (!running.get()) return

            val httpClient = HttpClient.create()
            val subscription = httpClient
                .websocket(WebsocketClientSpec.builder().maxFramePayloadLength(65536).build())
                .uri(wsUri)
                .handle { inbound, outbound ->
                    connected.set(true)
                    // 시세를 한 번이라도 받은 연결에서만 UP 을 생존으로 친다. 구독이 거절되거나 아무것도 오지 않는 연결은 UP 이 와도 끊긴 것이다.
                    val delivered = AtomicBoolean(false)
                    log.info("Upbit WebSocket connected. Markets: {}", markets)

                    val subscriptionMessage = buildSubscriptionMessage(markets, includeTrades)
                    val pings = reactor.core.publisher.Flux.interval(Duration.ofMillis(pingIntervalMs)).map { PING }
                    val sendMono = outbound.sendString(reactor.core.publisher.Flux.just(subscriptionMessage).concatWith(pings)).then()

                    val receiveMono = inbound.receive()
                        .asByteArray()
                        .map { bytes -> String(bytes, StandardCharsets.UTF_8) }
                        .doOnNext { message ->
                            val event = parseMessage(message) ?: return@doOnNext
                            // handshake 성공만으로는 구독이 받아들여졌는지 알 수 없어 ticker 나 체결을 받은 뒤에 횟수를 되돌린다.
                            // UP 은 PING 응답일 뿐 구독을 받아들였다는 뜻이 아니라 되돌리지 않는다.
                            // 횟수는 연결이 끝날 때만 늘어 한 연결에서는 처음 받은 메시지에서만 0 이 아니다.
                            if (event !is MarketEvent.Alive) {
                                val failed = reconnectAttempts.getAndSet(0)
                                if (failed > 0) log.info("Upbit WS receiving data; reconnect backoff reset after {} attempts", failed)
                            }
                            when (event) {
                                is MarketEvent.Ticker -> {
                                    delivered.set(true)
                                    if (running.get()) onTicker(event.ticker)
                                }
                                is MarketEvent.Trade -> {
                                    delivered.set(true)
                                    trades.trySend(event.trade)
                                }
                                MarketEvent.Alive -> if (running.get() && delivered.get()) onAlive()
                            }
                        }
                        .doOnError { e -> log.warn("Upbit WS receive error: {}", e.message) }
                        .then()

                    // PING 은 연결 내내 보내므로 받기와 나란히 돈다. 받기가 끝나면(서버가 닫음) 연결도 끝난다.
                    Mono.firstWithSignal(receiveMono, sendMono)
                }
                .doOnError { e ->
                    log.warn("Upbit WS connection error: {}", e.message)
                    connected.set(false)
                }
                .doFinally {
                    connected.set(false)
                    if (running.get()) {
                        val attempt = reconnectAttempts.incrementAndGet()
                        val delay = (baseReconnectDelayMs * (1L shl minOf(attempt - 1, 5)))
                            .coerceAtMost(MAX_RECONNECT_DELAY_MS)
                        log.info("Upbit WS reconnecting in {}ms (attempt {})", delay, attempt)
                        // netty 스레드에서 sleep 하면 이벤트 루프를 붙잡으므로 별도 스레드에서 기다린다.
                        val thread = Thread {
                            try {
                                Thread.sleep(delay)
                                connect()
                            } catch (_: InterruptedException) {
                                // 종료할 때 대기 중인 재연결을 인터럽트로 취소한다.
                            }
                        }.apply { isDaemon = true }
                        reconnectThread.set(thread)
                        thread.start()
                    }
                }
                .subscribe()
            disposable.set(subscription)
            // 그 사이 flow 가 닫혔으면 방금 연 연결을 바로 닫는다.
            if (!running.get()) subscription.dispose()
        }

        connect()

        try {
            for (trade in trades) emit(trade)
        } finally {
            running.set(false)
            reconnectThread.get()?.interrupt()
            disposable.get()?.dispose()
            trades.cancel()
            log.info("Upbit market flow closed")
        }
    }

    suspend fun getCandles(market: String, interval: CandleInterval, count: Int): List<NormalizedCandle> {
        val upbitMarket = MarketPair.toUpbitFormat(market)

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
                market = market,
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

    internal fun recordDropped(market: String, nowMs: Long) = droppedTrades.record(market, nowMs)

    /** 마지막 호출 뒤 마켓별로 밀려서 버린 체결 수. */
    fun drainDropped(): Map<String, Long> = droppedTrades.drain()

    /** 마지막 호출 뒤 마켓별로 읽지 못한 ticker·체결 프레임 수. */
    fun drainParseFailures(): Map<String, Long> = parseFailures.drain()

    // 업비트는 한 요청에 type 객체를 여러 개 받는다. 연결·요청 한도(초당 5)를 늘리지 않으려고 같은 요청에 싣는다.
    // ticker 는 구독하자마자 마켓마다 현재 상태(스냅샷)를 한 번 받는다 — 거래가 뜸한 마켓만 구독해도 이 연결이 시세를 받는지 안다.
    // 체결은 지난 체결을 다시 받지 않게 실시간만 받는다.
    internal fun buildSubscriptionMessage(markets: List<String>, includeTrades: Boolean): String {
        val upbitCodes = markets.map { MarketPair.toUpbitFormat(it) }
        val ticket = mapOf("ticket" to UUID.randomUUID().toString())
        val ticker = mapOf("type" to "ticker", "codes" to upbitCodes)
        val trade = mapOf("type" to "trade", "codes" to upbitCodes, "isOnlyRealtime" to true)
        return objectMapper.writeValueAsString(listOf(ticket, ticker) + if (includeTrades) listOf(trade) else emptyList())
    }

    internal fun parseMessage(message: String, nowMs: Long = System.currentTimeMillis()): MarketEvent? {
        val node = try {
            objectMapper.readTree(message)
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            // 연결 ACK 같은 프레임이 흔히 섞여 들어온다.
            log.debug("Skipped non-parsable frame: {}", e.message)
            return null
        }
        if (node["status"]?.asText() == "UP") return MarketEvent.Alive
        // 구독 요청이 틀리면 업비트는 오류 프레임을 보낸다. 조용히 버리면 시세가 안 오는 이유를 알 수 없다.
        node["error"]?.let { error ->
            parseFailures.record(null, nowMs, "error frame ${error["name"]?.asText()}: ${error["message"]?.asText()}")
            return null
        }
        return try {
            when (node["type"]?.asText()) {
                "ticker" -> MarketEvent.Ticker(toTicker(node))
                "trade" -> MarketEvent.Trade(toTrade(node))
                else -> null
            }
        } catch (e: Exception) {
            parseFailures.record(marketOf(node), nowMs, e.message)
            null
        }
    }

    private fun marketOf(node: JsonNode): String? =
        node["code"]?.asText()?.let { runCatching { MarketPair.normalize(Exchange.UPBIT, it) }.getOrNull() }

    private fun toTicker(node: JsonNode): NormalizedTicker {
        val upbitCode = node["code"].asText()
        return NormalizedTicker(
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
    }

    // 체결 번호·체결 시각이 없으면 중복도 낡음도 가릴 수 없어 받지 않는다(required 가 던진다).
    private fun toTrade(node: JsonNode): NormalizedTrade = NormalizedTrade(
        exchange = Exchange.UPBIT,
        market = MarketPair.normalize(Exchange.UPBIT, node.required("code").asText()),
        price = node.required("trade_price").asDouble(),
        volume = node.required("trade_volume").asDouble(),
        tradeTime = Instant.ofEpochMilli(node.required("trade_timestamp").asLong()),
        sequentialId = node.required("sequential_id").asLong(),
    )
}
