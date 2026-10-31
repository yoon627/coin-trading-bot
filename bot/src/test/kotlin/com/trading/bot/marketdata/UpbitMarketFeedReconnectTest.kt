package com.trading.bot.marketdata

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.common.domain.NormalizedTicker
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import reactor.netty.http.websocket.WebsocketInbound
import reactor.netty.http.websocket.WebsocketOutbound
import java.net.URI
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * handshake 성공만으로는 구독이 받아들여졌는지 알 수 없다. 시세 없이 끊기는 연결이 이어지면 재연결 간격이 늘어야 하고,
 * 시세를 받은 연결 뒤에야 처음부터 다시 센다. 오류 프레임·즉시 close 가 "시세 없이 끊김" 을 대신한다(#304 처럼 구독
 * 메시지를 만들다 던지는 경우도 같은 경로다).
 */
class UpbitMarketFeedReconnectTest {

    private enum class Step { CLOSE, ERROR, TICKER, HOLD }

    private val connections = AtomicInteger(0)
    @Volatile
    private var script: (Int) -> Step = { Step.HOLD }
    private lateinit var server: DisposableServer
    private val logger = LoggerFactory.getLogger(UpbitMarketFeed::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun startServer() {
        server = HttpServer.create().host("127.0.0.1").port(0)
            .route { routes -> routes.ws("/") { inbound, outbound -> serve(connections.incrementAndGet(), inbound, outbound) } }
            .bindNow()
        logger.addAppender(appender)
    }

    @AfterEach
    fun stopServer() {
        logger.detachAppender(appender)
        appender.stop()
        server.disposeNow()
    }

    /**
     * 첫 프레임(구독)을 받은 뒤 연결 번호별 동작. inbound 를 끝까지 읽는다 — 첫 프레임에서 구독을 끊으면 서버가 연결을 바로
     * 닫아 스크립트가 흐려진다. 대기는 클라이언트와 같은 event loop 를 막지 않게 Mono.delay 로 한다.
     */
    private fun serve(n: Int, inbound: WebsocketInbound, outbound: WebsocketOutbound): Mono<Void> {
        val subscribed = Sinks.empty<Void>()
        val drain = inbound.receive().asString().doOnNext { subscribed.tryEmitEmpty() }.then()
        val step = when (script(n)) {
            Step.CLOSE -> outbound.sendClose()
            Step.ERROR -> Mono.from(outbound.sendString(Mono.just(ERROR_FRAME))).then(outbound.sendClose())
            Step.TICKER -> Mono.delay(Duration.ofMillis(50))
                .then(Mono.from(outbound.sendString(Mono.just(TICKER_FRAME))))
                .then(outbound.sendClose())
            Step.HOLD -> Mono.never()
        }
        return subscribed.asMono().then(step).and(drain)
    }

    private fun feed(baseDelayMs: Long) =
        UpbitMarketFeed(mockk(relaxed = true), ObjectMapper(), URI.create("ws://127.0.0.1:${server.port()}/"), baseDelayMs)

    private fun messages(): List<String> = synchronized(appender) { appender.list.map { it.formattedMessage } }

    /** (지연 ms, 시도 번호) */
    private fun reconnects(): List<Pair<Long, Int>> =
        messages().mapNotNull { RECONNECT.find(it) }.map { it.groupValues[1].toLong() to it.groupValues[2].toInt() }

    @Test
    fun `handshake 뒤 시세 없이 끊기면 재연결 횟수가 늘고, 시세를 받은 연결 뒤에는 처음부터 다시 센다`() {
        // 첫 연결은 실패 없이 시세를 받아 되돌릴 횟수가 없다 — 리셋 INFO 는 실패 뒤의 연결에서만 나온다.
        val steps = listOf(Step.TICKER, Step.CLOSE, Step.ERROR, Step.TICKER, Step.CLOSE)
        script = { n -> steps.getOrElse(n - 1) { Step.HOLD } }
        val received = Collections.synchronizedList(mutableListOf<NormalizedTicker>())

        runBlocking {
            withTimeout(10_000) {
                val job = launch(Dispatchers.IO) { feed(20).tickerFlow(listOf("BTC/KRW")).collect { received.add(it) } }
                // 두 번째 시세가 collector 에 닿기 전에 닫으면 그 시세가 버려진다.
                while (reconnects().size < 5 || received.size < 2) delay(20)
                job.cancelAndJoin()
            }
        }

        assertEquals(listOf(1, 2, 3, 1, 2), reconnects().map { it.second })
        assertEquals(listOf(20L, 40L, 80L, 20L, 40L), reconnects().map { it.first })
        assertEquals(listOf("BTC/KRW", "BTC/KRW"), received.map { it.market })
        assertEquals(
            listOf("Upbit WS receiving tickers; reconnect backoff reset after 3 attempts"),
            messages().filter { it.startsWith("Upbit WS receiving tickers") },
        )
    }

    /** 재연결 횟수 리셋이 아니라 워치독의 cancelAndJoin 이 기대는 성질(닫힌 flow 는 다시 붙지 않는다)을 지킨다. */
    @Test
    fun `flow 를 닫으면 backoff 를 기다리던 재연결은 열리지 않는다`() {
        script = { Step.CLOSE }

        runBlocking {
            withTimeout(10_000) {
                val job = launch(Dispatchers.IO) { feed(200).tickerFlow(listOf("BTC/KRW")).collect {} }
                // 두 번째 재연결 줄이 찍히면 그 지연만큼 잠든 사이다.
                while (reconnects().size < 2) delay(10)
                job.cancelAndJoin()
            }
        }
        val opened = connections.get()
        Thread.sleep(1_000)

        assertEquals(opened, connections.get())
    }

    private companion object {
        val RECONNECT = Regex("""Upbit WS reconnecting in (\d+)ms \(attempt (\d+)\)""")
        const val ERROR_FRAME = """{"error":{"name":"INVALID_PARAM","message":"x"}}"""
        const val TICKER_FRAME = """{"type":"ticker","code":"KRW-BTC","trade_price":1.0,"timestamp":1700000000000}"""
    }
}
