package com.trading.bot.api

import com.trading.bot.config.WatchlistProperties
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.MarketCandleRepository
import com.trading.bot.persistence.MarketTickerRepository
import com.trading.bot.persistence.entity.MarketCandleEntity
import com.trading.bot.persistence.entity.MarketTickerEntity
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedTicker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * watchlist 는 `price_snapshots` 대신 메모리 스냅샷 + `market_tickers` 를 읽는다.
 * 외부 응답 계약은 그대로여야 한다 — 전환의 목적이 중복 수집 제거이지 API 변경이 아니다.
 */
class WatchlistControllerTest {

    private val store = mockk<MarketDataStore>()
    private val repo = mockk<MarketTickerRepository>()
    private val candles = mockk<MarketCandleRepository>()
    private val props = WatchlistProperties("KRW-BTC,KRW-ETH")
    private val controller = WatchlistController(store, repo, candles, props)
    private val client = WebTestClient.bindToController(controller).build()

    private val now = Instant.parse("2026-08-05T00:00:00Z") // KST 09:00

    private fun snapshot(market: String, price: Double) = NormalizedTicker(
        exchange = Exchange.UPBIT,
        market = market,
        price = price,
        quoteVolume24h = 1_000.0,
        changeRate24h = 0.05,
        highPrice24h = 121.0,
        lowPrice24h = 99.0,
        timestamp = Instant.now(),
    )

    // 폴백에는 신선도 검사(실제 now 기준 1시간)가 걸리므로 고정 시각이 아니라 현재 기준으로 만든다.
    private fun row(market: String, price: Double, id: Long = 1L) = MarketTickerEntity(
        id = id, exchange = "UPBIT", market = market, price = price,
        recordedAt = Instant.now().minusSeconds(60),
    )

    private fun candle(market: String, close: Double) = MarketCandleEntity(
        exchange = "UPBIT", market = market, intervalMinutes = 1,
        openPrice = close, highPrice = close, lowPrice = close, closePrice = close,
        volume = 1.0, openTime = Instant.now().minusSeconds(3_300),
        closeTime = Instant.now().minusSeconds(3_240),
    )

    /** DB ticker 이력이 없는 기본 상태 — 개별 테스트가 필요하면 덮어쓴다. */
    private fun noDbHistory() {
        every { repo.findRecent("UPBIT", any(), any()) } returns Flux.empty()
    }

    private fun noBaseline() {
        every { candles.findOldestInRange("UPBIT", any(), 1, any(), any()) } returns Mono.empty()
    }

    /** 두 종목 모두 메모리에 있고, ETH 는 1h 기준점이 없는 기본 상태. */
    private fun arrangeBoth(btcPrice: Double = 110.0, ethPrice: Double = 200.0) {
        every { store.getLatestTicker(Exchange.UPBIT, "BTC/KRW") } returns snapshot("BTC/KRW", btcPrice)
        every { store.getLatestTicker(Exchange.UPBIT, "ETH/KRW") } returns snapshot("ETH/KRW", ethPrice)
        noDbHistory()
        noBaseline()
    }

    @Test
    fun `응답 키와 값이 기존 계약을 유지한다`() {
        arrangeBoth()
        every { candles.findOldestInRange("UPBIT", "BTC/KRW", 1, any(), any()) } returns
            Mono.just(candle("BTC/KRW", 100.0))

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
    fun `거래가 드문 종목도 목록에 남는다`() {
        // 종목별 10 tick 마다 저장하므로 조용한 종목은 1h 창에 행이 없을 수 있다. 그래도
        // 목록에서 사라지면 안 된다 — 구 REST 수집은 5분마다 기록해 항상 노출됐다.
        every { store.getLatestTicker(Exchange.UPBIT, "BTC/KRW") } returns
            snapshot("BTC/KRW", 110.0).copy(quoteVolume24h = 999.0) // 정렬 [0] 고정
        every { store.getLatestTicker(Exchange.UPBIT, "ETH/KRW") } returns
            snapshot("ETH/KRW", 200.0).copy(quoteVolume24h = 10.0)
        noDbHistory()
        noBaseline()

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.coins.length()").isEqualTo(2)
            .jsonPath("$.coins[0].ticker").isEqualTo("KRW-BTC")
            .jsonPath("$.coins[0].price").isEqualTo(110.0) // 현재가는 메모리에서 나온다
            .jsonPath("$.coins[0].change_1h").doesNotExist()
    }

    @Test
    fun `메모리가 비면 DB 마지막 기록으로 폴백한다`() {
        // 재시작 직후에는 WS 가 아직 emit 하지 않아 메모리가 비어 있다(isOnlyRealtime 이라
        // 초기 스냅샷도 없다). 그 사이 종목이 사라지면 구 REST 수집 대비 회귀다.
        every { store.getLatestTicker(Exchange.UPBIT, "BTC/KRW") } returns null
        every { store.getLatestTicker(Exchange.UPBIT, "ETH/KRW") } returns null
        every { repo.findRecent("UPBIT", "BTC/KRW", 1) } returns
            Flux.just(row("BTC/KRW", 77.0).copy(quoteVolume24h = 5.0))
        every { repo.findRecent("UPBIT", "ETH/KRW", 1) } returns Flux.empty()
        noBaseline()

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.coins.length()").isEqualTo(1)
            .jsonPath("$.coins[0].ticker").isEqualTo("KRW-BTC")
            .jsonPath("$.coins[0].price").isEqualTo(77.0)
    }

    @Test
    fun `메모리 값이 낡았으면 더 신선한 DB 기록을 쓴다`() {
        // WS 가 끊기거나 그 종목 이벤트가 오래 없으면 메모리 값이 stale 하다.
        // 더 최근 관측이 DB 에 있으면 그쪽을 쓴다.
        every { store.getLatestTicker(Exchange.UPBIT, "BTC/KRW") } returns
            snapshot("BTC/KRW", 999.0).copy(timestamp = Instant.now().minusSeconds(7_200))
        every { store.getLatestTicker(Exchange.UPBIT, "ETH/KRW") } returns null
        every { repo.findRecent("UPBIT", "BTC/KRW", 1) } returns Flux.just(row("BTC/KRW", 77.0))
        every { repo.findRecent("UPBIT", "ETH/KRW", 1) } returns Flux.empty()
        noBaseline()

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.coins.length()").isEqualTo(1)
            .jsonPath("$.coins[0].price").isEqualTo(77.0) // stale 한 999.0 이 아니다
    }

    @Test
    fun `값이 오래됐어도 종목은 목록에 남고 updated_at 이 시각을 드러낸다`() {
        // 신선도로 걸러 목록에서 빼면 조용한 종목이 UI 에서 사라진다 — 구 REST 수집에서는
        // 없던 일이다. 낡은 값으로 1h 변화율을 만들지 않는 것은 기준봉 조건이 맡는다.
        every { store.getLatestTicker(Exchange.UPBIT, "BTC/KRW") } returns null
        every { store.getLatestTicker(Exchange.UPBIT, "ETH/KRW") } returns null
        every { repo.findRecent("UPBIT", "BTC/KRW", 1) } returns
            Flux.just(row("BTC/KRW", 77.0).copy(recordedAt = Instant.now().minusSeconds(86_400)))
        every { repo.findRecent("UPBIT", "ETH/KRW", 1) } returns Flux.empty()
        noBaseline()

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.coins.length()").isEqualTo(1)
            .jsonPath("$.coins[0].ticker").isEqualTo("KRW-BTC")
            .jsonPath("$.coins[0].price").isEqualTo(77.0)
            .jsonPath("$.coins[0].updated_at").exists()
    }

    @Test
    fun `메모리에도 DB 에도 없는 종목만 빠진다`() {
        every { store.getLatestTicker(Exchange.UPBIT, "BTC/KRW") } returns snapshot("BTC/KRW", 100.0)
        every { store.getLatestTicker(Exchange.UPBIT, "ETH/KRW") } returns null
        noDbHistory()
        noBaseline()

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.coins.length()").isEqualTo(1)
            .jsonPath("$.coins[0].ticker").isEqualTo("KRW-BTC")
    }

    @Test
    fun `1시간 전과 가격이 같으면 change_1h 는 0 이다`() {
        // null 이 아니다 — 기존 구현은 창에 2건 이상이면 변화가 없어도 0.0 을 냈다.
        arrangeBoth(btcPrice = 100.0)
        every { candles.findOldestInRange("UPBIT", "BTC/KRW", 1, any(), any()) } returns
            Mono.just(candle("BTC/KRW", 100.0))

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.coins[0].change_1h").isEqualTo(0.0)
    }

    @Test
    fun `거래가 드문 종목도 1h 변화율을 얻는다`() {
        // 이 작업의 핵심 회귀 방어. ticker 저장은 종목별 10 tick 샘플링이라 조용한 종목은
        // 1시간 창에 행이 0~1건뿐이다. 캔들은 60초 REST 폴링이라 거래량과 무관하게 채워지므로
        // 기준가를 거기서 얻는다 — 구 REST 수집(5분 주기)이 제공하던 값을 잃지 않는다.
        arrangeBoth(btcPrice = 110.0)
        every { repo.findRecent("UPBIT", any(), any()) } returns Flux.empty() // ticker 이력 없음
        every { candles.findOldestInRange("UPBIT", "BTC/KRW", 1, any(), any()) } returns
            Mono.just(candle("BTC/KRW", 100.0))

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.coins[0].change_1h").isEqualTo(10.0)
    }

    @Test
    fun `현재값이 1시간보다 오래되면 change_1h 를 만들지 않는다`() {
        // WS 중단으로 폴백값이 낡았는데 기준봉만 최근이면 엉뚱한 변화율이 나온다.
        // 목록에는 남기되(사용자 결정) 변화율은 비운다.
        every { store.getLatestTicker(Exchange.UPBIT, "BTC/KRW") } returns null
        every { store.getLatestTicker(Exchange.UPBIT, "ETH/KRW") } returns null
        every { repo.findRecent("UPBIT", "BTC/KRW", 1) } returns
            Flux.just(row("BTC/KRW", 77.0).copy(recordedAt = Instant.now().minusSeconds(86_400)))
        every { repo.findRecent("UPBIT", "ETH/KRW", 1) } returns Flux.empty()
        every { candles.findOldestInRange("UPBIT", any(), 1, any(), any()) } returns
            Mono.just(candle("BTC/KRW", 100.0))

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.coins[0].price").isEqualTo(77.0) // 목록엔 남는다
            .jsonPath("$.coins[0].change_1h").doesNotExist()
    }

    @Test
    fun `기준봉이 최근 것뿐이면 change_1h 를 만들지 않는다`() {
        // 재시작·수집 공백 직후에는 창의 첫 봉이 사실상 현재 봉이다. 그걸로 계산하면
        // 1시간 변화율이 아니라 몇 분 변화율이 된다 — 기존 구현의 단일 관측과 같이 null.
        arrangeBoth(btcPrice = 110.0)
        every { candles.findOldestInRange("UPBIT", "BTC/KRW", 1, any(), any()) } returns
            Mono.just(candle("BTC/KRW", 100.0).copy(openTime = Instant.now().minusSeconds(120)))

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.coins[0].change_1h").doesNotExist()
    }

    @Test
    fun `조회 키를 정규화 형식으로 변환해 넘긴다`() {
        // 변환을 빠뜨리면 에러 없이 항상 빈 결과가 나온다(무증상). 호출 인자를 직접 고정한다.
        arrangeBoth()
        every { candles.findOldestInRange("UPBIT", "BTC/KRW", 1, any(), any()) } returns Mono.empty()

        client.get().uri("/api/watchlist").exchange().expectStatus().isOk

        verify { store.getLatestTicker(Exchange.UPBIT, "BTC/KRW") }
        verify { candles.findOldestInRange("UPBIT", "BTC/KRW", 1, any(), any()) }
        verify(exactly = 0) { candles.findOldestInRange("UPBIT", "KRW-BTC", 1, any(), any()) }
    }

    @Test
    fun `24h 거래대금 내림차순으로 정렬한다`() {
        every { store.getLatestTicker(Exchange.UPBIT, "BTC/KRW") } returns
            snapshot("BTC/KRW", 100.0).copy(quoteVolume24h = 10.0)
        every { store.getLatestTicker(Exchange.UPBIT, "ETH/KRW") } returns
            snapshot("ETH/KRW", 200.0).copy(quoteVolume24h = 999.0)
        noDbHistory()
        noBaseline()

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.coins[0].ticker").isEqualTo("KRW-ETH")
            .jsonPath("$.coins[1].ticker").isEqualTo("KRW-BTC")
    }

    @Test
    fun `기준봉 조회가 실패해도 현재가 항목은 남는다`() {
        // 기준봉은 change_1h 에만 쓰인다 — 실패로 종목 전체가 사라지면 안 된다.
        arrangeBoth()
        every { candles.findOldestInRange("UPBIT", "BTC/KRW", 1, any(), any()) } returns
            Mono.error(RuntimeException("db down"))

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.coins.length()").isEqualTo(2)
            .jsonPath("$.coins[?(@.ticker == 'KRW-BTC')].price").isEqualTo(110.0)
    }

    @Test
    fun `DB 가 죽어도 메모리 시세로 응답한다`() {
        arrangeBoth()
        every { repo.findRecent("UPBIT", any(), any()) } returns Flux.error(RuntimeException("db down"))

        client.get().uri("/api/watchlist").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.coins.length()").isEqualTo(2)
    }
}
