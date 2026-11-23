package com.trading.bot.api

import com.trading.bot.persistence.OrderRowRepository
import com.trading.bot.persistence.entity.OrderRowEntity
import com.trading.common.domain.Exchange
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** 거래소 파라미터의 기본값은 WebFlux 바인딩을 거쳐야 정해지므로 HTTP 요청으로 본다. */
class TradeHistoryControllerExchangeTest {

    private val repository = mockk<OrderRowRepository>()
    private val max = TradeHistoryController.MAX_SOURCE_RECORDS

    private fun row(id: Long, ticker: String, side: String, exchange: Exchange) = OrderRowEntity(
        id = id, userId = 1L, exchange = exchange.name, market = ticker, side = side, status = "FILLED", decisionPrice = 100.0,
        filledVolume = 1.0, positionVolumeAfter = 1.0, bookAvgPrice = 100.0, fillSource = "ORDER",
        completedAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(60 * id),
    )

    private val upbitRows = listOf(row(1, "KRW-BTC", "BUY", Exchange.UPBIT), row(2, "KRW-BTC", "SELL", Exchange.UPBIT))
    private val paperRows = listOf(row(3, "KRX-005930", "BUY", Exchange.KIS_PAPER))

    init {
        every { repository.findFilledPage(1L, "UPBIT", 100, 0) } answers { Flux.fromIterable(upbitRows.reversed()) }
        every { repository.countFilled(1L, "UPBIT") } answers { Mono.just(2L) }
        every { repository.findFilledRecentAscending(1L, "UPBIT", max + 1) } answers { Flux.fromIterable(upbitRows) }
        every { repository.findFilledPage(1L, "KIS_PAPER", 100, 0) } answers { Flux.fromIterable(paperRows) }
        every { repository.countFilled(1L, "KIS_PAPER") } answers { Mono.just(1L) }
        every { repository.findFilledRecentAscending(1L, "KIS_PAPER", max + 1) } answers { Flux.fromIterable(paperRows) }
    }

    private val client: WebTestClient = WebTestClient
        .bindToController(TradeHistoryController(repository, RequestValidators(), ZoneOffset.UTC))
        .webFilter<WebTestClient.ControllerSpec>(
            WebFilter { exchange, chain ->
                chain.filter(exchange).contextWrite(
                    ReactiveSecurityContextHolder.withAuthentication(UsernamePasswordAuthenticationToken(1L, null, emptyList())),
                )
            },
        )
        .build()

    private fun body(path: String): String =
        client.get().uri(path).exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody.orEmpty()

    @Test
    fun `파라미터 없음과 exchange=UPBIT 는 Upbit 기록만 같은 응답으로 돌려준다`() {
        val trades = body("/api/trades")
        val roundTrips = body("/api/trades/roundtrips")

        assertEquals(trades, body("/api/trades?exchange=UPBIT"))
        assertEquals(roundTrips, body("/api/trades/roundtrips?exchange=UPBIT"))
        assertTrue(trades.contains("\"total\":2"), trades)
        assertTrue(!trades.contains("KRX-005930") && !roundTrips.contains("KRX-005930"), trades + roundTrips)
        client.get().uri("/api/trades/roundtrips").exchange().expectBody().jsonPath("$.total").isEqualTo(1)
    }

    @Test
    fun `KIS_PAPER 는 그 거래소 기록과 그 거래소 total 이다`() {
        client.get().uri("/api/trades?exchange=KIS_PAPER").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.total").isEqualTo(1)
            .jsonPath("$.records.length()").isEqualTo(1)
            .jsonPath("$.records[0].ticker").isEqualTo("KRX-005930")
        client.get().uri("/api/trades/roundtrips?exchange=KIS_PAPER").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.round_trips[0].ticker").isEqualTo("KRX-005930")

        verify(exactly = 0) { repository.findFilledPage(any(), "UPBIT", any(), any()) }
        verify(exactly = 0) { repository.findFilledRecentAscending(any(), "UPBIT", any()) }
    }

    @Test
    fun `모르는 거래소 값은 조회 전에 400 이다`() {
        listOf("upbit", "BINANCE").forEach {
            client.get().uri("/api/trades?exchange=$it").exchange().expectStatus().isBadRequest
            client.get().uri("/api/trades/roundtrips?exchange=$it").exchange().expectStatus().isBadRequest
        }

        verify(exactly = 0) { repository.findFilledPage(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.countFilled(any(), any()) }
        verify(exactly = 0) { repository.findFilledRecentAscending(any(), any(), any()) }
    }
}
