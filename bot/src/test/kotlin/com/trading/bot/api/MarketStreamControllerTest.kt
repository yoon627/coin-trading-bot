package com.trading.bot.api

import com.trading.bot.config.MarketBoardProperties
import com.trading.bot.marketdata.MarketBoardStore
import com.trading.bot.marketdata.MarketStream
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedTicker
import java.time.Duration
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult
import org.springframework.web.server.WebFilter

/** HTTP 겉모습: 200·text/event-stream·첫 snapshot, 자리가 없거나 시세판이 꺼지면 본문 없는 503. */
class MarketStreamControllerTest {

    private val store = MarketBoardStore().apply { update(NormalizedTicker(Exchange.UPBIT, "BTC/KRW", 100.0)) }

    // 앱의 ObjectMapper 와 같은 이름 규칙(SNAKE_CASE) — 화면이 읽는 짧은 키가 바뀌지 않는지 본다.
    private val mapper = jacksonObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    private fun client(stream: MarketStream, enabled: Boolean = true): WebTestClient = WebTestClient
        .bindToController(MarketStreamController(stream, MarketBoardProperties(enabled = enabled)))
        .httpMessageCodecs { it.defaultCodecs().jackson2JsonEncoder(Jackson2JsonEncoder(mapper)) }
        .webFilter<WebTestClient.ControllerSpec>(
            WebFilter { exchange, chain ->
                chain.filter(exchange).contextWrite(
                    ReactiveSecurityContextHolder.withAuthentication(UsernamePasswordAuthenticationToken(1L, null, emptyList())),
                )
            },
        )
        .build()

    private fun stream(maxPerUser: Int = 3) =
        MarketStream(store, 10, maxPerUser, Duration.ofSeconds(1), Duration.ofSeconds(15), Dispatchers.Default)

    @Test
    fun `it streams server-sent events starting with the snapshot`() {
        val result = client(stream()).get().uri("/api/market/stream").accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult<String>()

        val first = result.responseBody.blockFirst(Duration.ofSeconds(5))
        result.responseBody.subscribe().dispose()

        assertThat(first).contains(""""m":"KRW-BTC"""").contains(""""p":100.0""").contains(""""rows":[""").contains(""""removed":[]""")
    }

    // HEAD 는 GET 매핑으로 오지만 본문을 읽지 않는다. 자리를 잡으면 돌아오지 않아 세 번이면 그 사용자가 막힌다.
    @Test
    fun `a HEAD request takes no slot`() {
        val stream = stream()

        repeat(5) { client(stream).head().uri("/api/market/stream").exchange().expectStatus().isOk }

        assertThat(stream.openConnections()).isZero()
        assertThat(stream.hasRoom(1L)).isTrue()
    }

    @Test
    fun `no free slot returns 503 without a body`() {
        val stream = stream(maxPerUser = 0)

        client(stream).get().uri("/api/market/stream").accept(MediaType.TEXT_EVENT_STREAM).exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().valueEquals("Retry-After", "10")
    }

    @Test
    fun `a disabled board returns 503 and takes no slot`() {
        val stream = stream()

        client(stream, enabled = false).get().uri("/api/market/stream").accept(MediaType.TEXT_EVENT_STREAM).exchange()
            .expectStatus().isEqualTo(503)

        assertThat(stream.openConnections()).isZero()
    }
}
