package com.trading.bot.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

/**
 * 업비트는 120초 동안 주고받은 것이 없으면 연결을 닫고, 클라이언트가 PING 을 보내야 {"status":"UP"} 을 돌려준다(2026-11-23 접속 시험).
 * 거래가 뜸한 마켓만 구독해도 연결이 살아 있음을 알 수 있게 피드가 PING 을 보내고 UP 을 넘긴다. 다만 시세를 한 번도 받지 못한
 * 연결(구독 거절 등)의 UP 은 넘기지 않는다.
 */
class UpbitMarketFeedKeepaliveTest {

    private val requests = Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var answerSubscriptionWithTicker = true
    private lateinit var server: DisposableServer

    @BeforeEach
    fun startServer() {
        server = HttpServer.create().host("127.0.0.1").port(0)
            .route { routes ->
                routes.ws("/") { inbound, outbound ->
                    outbound.sendString(
                        inbound.receive().asString().doOnNext { requests.add(it) }.mapNotNull { request ->
                            when {
                                request == "PING" -> UP_FRAME
                                answerSubscriptionWithTicker -> TICKER_FRAME
                                else -> null
                            }
                        },
                    ).then()
                }
            }
            .bindNow()
    }

    @AfterEach
    fun stopServer() {
        server.disposeNow()
    }

    private fun feed() = UpbitMarketFeed(mockk(relaxed = true), ObjectMapper(), URI.create("ws://127.0.0.1:${server.port()}/"), 1_000, pingIntervalMs = 50)

    @Test
    fun `the feed pings after subscribing and hands every UP reply over once the connection has delivered prices`() = runBlocking {
        val alive = AtomicInteger()

        val collecting = launch(Dispatchers.IO) { feed().marketFlow(listOf("BTC/KRW"), true, {}, { alive.incrementAndGet() }).collect {} }
        try {
            withTimeout(5_000) { while (alive.get() < 3) delay(20) }
        } finally {
            collecting.cancelAndJoin()
        }

        assertTrue(requests.first().contains("\"codes\":[\"KRW-BTC\"]"), requests.first())
        assertEquals(listOf("PING"), requests.drop(1).distinct())
    }

    @Test
    fun `UP on a connection that never delivered a price is not a sign of life`() = runBlocking {
        answerSubscriptionWithTicker = false
        val alive = AtomicInteger()

        val collecting = launch(Dispatchers.IO) { feed().marketFlow(listOf("BTC/KRW"), true, {}, { alive.incrementAndGet() }).collect {} }
        try {
            withTimeout(5_000) { while (requests.count { it == "PING" } < 3) delay(20) }
            delay(100)
        } finally {
            collecting.cancelAndJoin()
        }

        assertEquals(0, alive.get())
    }

    private companion object {
        const val UP_FRAME = """{"status":"UP"}"""
        const val TICKER_FRAME = """{"type":"ticker","code":"KRW-BTC","trade_price":1.0,"timestamp":1}"""
    }
}
