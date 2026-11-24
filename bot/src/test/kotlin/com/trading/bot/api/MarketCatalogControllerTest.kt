package com.trading.bot.api

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.marketdata.MarketCatalog
import com.trading.bot.marketdata.MarketListing
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.reactive.server.WebTestClient

/** 검색 화면이 읽는 모양: 업비트 표기 코드와 snake_case 이름. 목록이 없으면 503. */
class MarketCatalogControllerTest {
    private val catalog = mockk<MarketCatalog>()

    // 앱의 ObjectMapper 와 같은 이름 규칙(SNAKE_CASE).
    private val client = WebTestClient
        .bindToController(MarketCatalogController(catalog))
        .httpMessageCodecs { it.defaultCodecs().jackson2JsonEncoder(Jackson2JsonEncoder(jacksonObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE))) }
        .build()

    @Test
    fun `markets are listed in Upbit form with their names`() {
        coEvery { catalog.listings() } returns listOf(MarketListing("BTC/KRW", "비트코인", "Bitcoin"))

        client.get().uri("/api/market/markets").exchange()
            .expectStatus().isOk
            .expectBody().json("""{"markets":[{"market":"KRW-BTC","korean_name":"비트코인","english_name":"Bitcoin"}]}""", JsonCompareMode.STRICT)
    }

    @Test
    fun `no list yet is 503`() {
        coEvery { catalog.listings() } returns null

        client.get().uri("/api/market/markets").exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().valueEquals("Retry-After", "60")
    }
}
