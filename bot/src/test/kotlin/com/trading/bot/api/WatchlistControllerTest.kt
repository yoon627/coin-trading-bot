package com.trading.bot.api

import com.trading.bot.config.WatchlistProperties
import com.trading.bot.persistence.MarketTickerRepository
import com.trading.bot.persistence.entity.MarketTickerEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import java.time.Instant

/**
 * watchlist 는 `price_snapshots` 대신 `market_tickers` 를 읽는다. 외부 응답 계약은 그대로여야 한다 —
 * 전환의 목적이 중복 수집 제거이지 API 변경이 아니다.
 */
class WatchlistControllerTest {

    private val repo = mockk<MarketTickerRepository>()
    private val props = WatchlistProperties("KRW-BTC,KRW-ETH")
    private val controller = WatchlistController(repo, props)
    private val client = WebTestClient.bindToController(controller).build()

    private fun ticker(
        market: String,
        price: Double,
        at: Instant,
        high: Double = 121.0,
        low: Double = 99.0,
    ) = MarketTickerEntity(
        exchange = "UPBIT",
        market = market,
        price = price,
        quoteVolume24h = 1_000.0,
        changeRate24h = 0.05,
        highPrice24h = high,
        lowPrice24h = low,
        recordedAt = at,
    )

    @Test
    fun `응답 키와 값이 기존 계약을 유지한다`() {
        val now = Instant.parse("2026-08-05T00:00:00Z")
        every { repo.findByTimeRange("UPBIT", "BTC/KRW", any(), any()) } returns Flux.just(
            ticker("BTC/KRW", 110.0, now),
            ticker("BTC/KRW", 100.0, now.minusSeconds(3000)),
        )
        every { repo.findByTimeRange("UPBIT", "ETH/KRW", any(), any()) } returns Flux.empty()

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.coins[0].ticker").isEqualTo("KRW-BTC")
            .jsonPath("$.coins[0].currency").isEqualTo("BTC")
            .jsonPath("$.coins[0].price").isEqualTo(110.0)
            .jsonPath("$.coins[0].change_24h").isEqualTo(5.0) // changeRate24h * 100
            .jsonPath("$.coins[0].change_1h").isEqualTo(10.0) // (110-100)/100*100
            .jsonPath("$.coins[0].volume_24h").isEqualTo(1000.0)
            .jsonPath("$.coins[0].high_price").isEqualTo(121.0)
            .jsonPath("$.coins[0].low_price").isEqualTo(99.0)
            .jsonPath("$.coins[0].updated_at").exists()
    }

    @Test
    fun `데이터 없는 종목은 목록에서 빠진다`() {
        val now = Instant.parse("2026-08-05T00:00:00Z")
        every { repo.findByTimeRange("UPBIT", "BTC/KRW", any(), any()) } returns
            Flux.just(ticker("BTC/KRW", 100.0, now))
        every { repo.findByTimeRange("UPBIT", "ETH/KRW", any(), any()) } returns Flux.empty()

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.coins.length()").isEqualTo(1)
            .jsonPath("$.coins[0].ticker").isEqualTo("KRW-BTC")
    }

    @Test
    fun `1h 창에 1건뿐이면 change_1h 는 null 이다`() {
        // 기존 price_snapshots 구현과 같은 규칙 — 비교 대상이 없으면 변화율을 만들지 않는다.
        val now = Instant.parse("2026-08-05T00:00:00Z")
        every { repo.findByTimeRange("UPBIT", "BTC/KRW", any(), any()) } returns
            Flux.just(ticker("BTC/KRW", 100.0, now))
        every { repo.findByTimeRange("UPBIT", "ETH/KRW", any(), any()) } returns Flux.empty()

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.coins[0].change_1h").doesNotExist()
    }

    @Test
    fun `24h 거래대금 내림차순으로 정렬한다`() {
        val now = Instant.parse("2026-08-05T00:00:00Z")
        every { repo.findByTimeRange("UPBIT", "BTC/KRW", any(), any()) } returns
            Flux.just(ticker("BTC/KRW", 100.0, now).copy(quoteVolume24h = 10.0))
        every { repo.findByTimeRange("UPBIT", "ETH/KRW", any(), any()) } returns
            Flux.just(ticker("ETH/KRW", 200.0, now).copy(quoteVolume24h = 999.0))

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.coins[0].ticker").isEqualTo("KRW-ETH")
            .jsonPath("$.coins[1].ticker").isEqualTo("KRW-BTC")
    }

    @Test
    fun `조회 키를 정규화 형식으로 변환해 넘긴다`() {
        // 변환을 빠뜨리면 에러 없이 항상 빈 결과가 나온다(무증상). 호출 인자를 직접 고정한다.
        val now = Instant.parse("2026-08-05T00:00:00Z")
        every { repo.findByTimeRange("UPBIT", "BTC/KRW", any(), any()) } returns
            Flux.just(ticker("BTC/KRW", 100.0, now))
        every { repo.findByTimeRange("UPBIT", "ETH/KRW", any(), any()) } returns Flux.empty()

        client.get().uri("/api/watchlist").exchange().expectStatus().isOk

        verify { repo.findByTimeRange("UPBIT", "BTC/KRW", any(), any()) }
        verify { repo.findByTimeRange("UPBIT", "ETH/KRW", any(), any()) }
        verify(exactly = 0) { repo.findByTimeRange("UPBIT", "KRW-BTC", any(), any()) }
    }

    @Test
    fun `nullable 컬럼이 비어도 응답에 null 을 내보내지 않는다`() {
        // price_snapshots 는 이 값들이 non-null 이었다 — market_tickers 로 옮기면서
        // null 이 새로 등장하면 프론트 계약이 조용히 바뀐다.
        val now = Instant.parse("2026-08-05T00:00:00Z")
        every { repo.findByTimeRange("UPBIT", "BTC/KRW", any(), any()) } returns Flux.just(
            MarketTickerEntity(
                exchange = "UPBIT", market = "BTC/KRW", price = 100.0,
                quoteVolume24h = null, changeRate24h = null,
                highPrice24h = null, lowPrice24h = null, recordedAt = now,
            ),
        )
        every { repo.findByTimeRange("UPBIT", "ETH/KRW", any(), any()) } returns Flux.empty()

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.coins[0].high_price").isEqualTo(0.0)
            .jsonPath("$.coins[0].low_price").isEqualTo(0.0)
            .jsonPath("$.coins[0].volume_24h").isEqualTo(0.0)
            .jsonPath("$.coins[0].change_24h").isEqualTo(0.0)
    }

    @Test
    fun `updated_at 은 KST 로 표현한다`() {
        // price_snapshots 는 KST LocalDateTime 을 저장했다. Instant 로 바뀌어도 표현은 같아야 한다.
        val now = Instant.parse("2026-08-05T00:00:00Z") // KST 09:00
        every { repo.findByTimeRange("UPBIT", "BTC/KRW", any(), any()) } returns
            Flux.just(ticker("BTC/KRW", 100.0, now))
        every { repo.findByTimeRange("UPBIT", "ETH/KRW", any(), any()) } returns Flux.empty()

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.coins[0].updated_at").isEqualTo("2026-08-05T09:00")
    }

    @Test
    fun `조회 실패한 종목은 건너뛰고 나머지를 반환한다`() {
        val now = Instant.parse("2026-08-05T00:00:00Z")
        every { repo.findByTimeRange("UPBIT", "BTC/KRW", any(), any()) } returns
            Flux.error(RuntimeException("db down"))
        every { repo.findByTimeRange("UPBIT", "ETH/KRW", any(), any()) } returns
            Flux.just(ticker("ETH/KRW", 200.0, now))

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.coins.length()").isEqualTo(1)
            .jsonPath("$.coins[0].ticker").isEqualTo("KRW-ETH")
    }
}
