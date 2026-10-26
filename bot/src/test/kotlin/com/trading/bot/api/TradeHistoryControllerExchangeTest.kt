package com.trading.bot.api

import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.entity.TradeRecordEntity
import com.trading.common.domain.Exchange
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter

/** 거래소 파라미터의 기본값은 WebFlux 바인딩을 거쳐야 정해지므로 HTTP 요청으로 본다. */
class TradeHistoryControllerExchangeTest {

    private val repository = mockk<TradeRecordRepository>()
    private val max = TradeHistoryController.MAX_SOURCE_RECORDS

    private fun row(id: Long, ticker: String, side: String, exchange: Exchange) = TradeRecordEntity(
        id = id, ticker = ticker, side = side, price = 100.0, volume = 1.0, totalAmount = 100.0, userId = 1L,
        createdAt = LocalDateTime.parse("2026-01-01T00:00").plusMinutes(id), exchange = exchange.name,
    )

    private val upbitRows = listOf(row(1, "KRW-BTC", "BUY", Exchange.UPBIT), row(2, "KRW-BTC", "SELL", Exchange.UPBIT))
    private val paperRows = listOf(row(3, "KRX-005930", "BUY", Exchange.KIS_PAPER))

    init {
        coEvery { repository.findByUserIdAndExchange(1L, Exchange.UPBIT, 100, 0) } returns upbitRows.reversed()
        coEvery { repository.countByUserIdAndExchange(1L, Exchange.UPBIT) } returns 2L
        coEvery { repository.findRecentAscending(1L, Exchange.UPBIT, max + 1) } returns upbitRows
        coEvery { repository.findByUserIdAndExchange(1L, Exchange.KIS_PAPER, 100, 0) } returns paperRows
        coEvery { repository.countByUserIdAndExchange(1L, Exchange.KIS_PAPER) } returns 1L
        coEvery { repository.findRecentAscending(1L, Exchange.KIS_PAPER, max + 1) } returns paperRows
    }

    private val client: WebTestClient = WebTestClient
        .bindToController(TradeHistoryController(repository, RequestValidators()))
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

        coVerify(exactly = 0) { repository.findByUserIdAndExchange(any(), Exchange.UPBIT, any(), any()) }
        coVerify(exactly = 0) { repository.findRecentAscending(any(), Exchange.UPBIT, any()) }
    }

    @Test
    fun `모르는 거래소 값은 조회 전에 400 이다`() {
        listOf("upbit", "BINANCE").forEach {
            client.get().uri("/api/trades?exchange=$it").exchange().expectStatus().isBadRequest
            client.get().uri("/api/trades/roundtrips?exchange=$it").exchange().expectStatus().isBadRequest
        }

        coVerify(exactly = 0) { repository.findByUserIdAndExchange(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.countByUserIdAndExchange(any(), any()) }
        coVerify(exactly = 0) { repository.findRecentAscending(any(), any(), any()) }
    }
}
