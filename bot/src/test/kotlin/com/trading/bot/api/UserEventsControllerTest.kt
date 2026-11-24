package com.trading.bot.api

import com.trading.bot.events.UserEventHub
import com.trading.bot.events.UserEventStream
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult
import org.springframework.web.server.WebFilter

/** HTTP 겉모습: 200·text/event-stream·첫 이벤트 hello, HEAD 는 자리를 잡지 않음, 자리가 없으면 본문 없는 503. */
class UserEventsControllerTest {

    private fun client(stream: UserEventStream): WebTestClient = WebTestClient
        .bindToController(UserEventsController(stream))
        .webFilter<WebTestClient.ControllerSpec>(
            WebFilter { exchange, chain ->
                chain.filter(exchange).contextWrite(
                    ReactiveSecurityContextHolder.withAuthentication(UsernamePasswordAuthenticationToken(1L, null, emptyList())),
                )
            },
        )
        .build()

    private fun stream(maxPerUser: Int = 5) = UserEventStream(
        UserEventHub(), 10, maxPerUser, Duration.ofMillis(500), Duration.ofSeconds(15), Duration.ofHours(1), Dispatchers.Default,
    )

    @Test
    fun `it streams server-sent events starting with hello`() {
        val result = client(stream()).get().uri("/api/user/events").accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult<String>()

        val first = result.responseBody.blockFirst(Duration.ofSeconds(5))
        result.responseBody.subscribe().dispose()

        assertThat(first).isEqualTo("0")
    }

    @Test
    fun `a HEAD request takes no slot`() {
        val stream = stream()

        repeat(6) { client(stream).head().uri("/api/user/events").exchange().expectStatus().isOk }

        assertThat(stream.openConnections()).isZero()
        assertThat(stream.hasRoom(1L)).isTrue()
    }

    @Test
    fun `no free slot returns 503 without a body`() {
        client(stream(maxPerUser = 0)).get().uri("/api/user/events").accept(MediaType.TEXT_EVENT_STREAM).exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().valueEquals("Retry-After", "10")
            .expectBody().isEmpty()
    }
}
