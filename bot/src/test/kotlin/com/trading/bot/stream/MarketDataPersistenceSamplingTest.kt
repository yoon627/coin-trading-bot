package com.trading.bot.stream

import com.trading.bot.persistence.MarketCandleRepository
import com.trading.bot.persistence.MarketTickerRepository
import com.trading.bot.persistence.entity.MarketTickerEntity
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedTicker
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * 저장 샘플링은 **종목별**이어야 한다. 전역 카운터였을 때는 고활동 종목이 카운트를 독식해
 * 저활동 종목이 1h 창에 거의 남지 않았고, watchlist 의 1h 변화율이 null 이 됐다.
 */
class MarketDataPersistenceSamplingTest {

    private val tickerRepo = mockk<MarketTickerRepository>()
    private val candleRepo = mockk<MarketCandleRepository>(relaxed = true)
    private val aggregator = mockk<CandleAggregator>(relaxed = true)
    private val service = MarketDataPersistenceService(tickerRepo, candleRepo, aggregator)

    private val saved = mutableListOf<MarketTickerEntity>()

    private fun arrangeCapture() {
        val s = slot<MarketTickerEntity>()
        every { tickerRepo.save(capture(s)) } answers {
            saved += s.captured
            Mono.just(s.captured)
        }
    }

    private fun tick(market: String) = NormalizedTicker(Exchange.UPBIT, market, 100.0)

    @Test
    fun `고활동 종목이 저활동 종목의 저장을 막지 않는다`() {
        arrangeCapture()

        // 활발한 종목이 99번 튀는 동안 조용한 종목은 10번만 튄다.
        repeat(99) { service.persistTicker(tick("KRW-BTC")) }
        repeat(10) { service.persistTicker(tick("KRW-DOGE")) }

        // 종목별 카운터라면 DOGE 는 10번째에 정확히 한 번 저장된다.
        // 전역 카운터였다면 DOGE 의 tick 은 카운터 100~109 구간에 들어가 100 번째 하나만 걸린다.
        assertEquals(1, saved.count { it.market == "KRW-DOGE" }, "저활동 종목도 자기 10번째에 저장돼야 한다")
        assertEquals(9, saved.count { it.market == "KRW-BTC" }, "고활동 종목은 99/10 = 9회")
    }

    @Test
    fun `종목별로 10 tick 마다 저장한다`() {
        arrangeCapture()

        repeat(9) { service.persistTicker(tick("KRW-ETH")) }
        assertEquals(0, saved.size, "10 미만이면 저장하지 않는다")

        service.persistTicker(tick("KRW-ETH"))
        assertEquals(1, saved.size, "10번째에 저장한다")
    }
}
