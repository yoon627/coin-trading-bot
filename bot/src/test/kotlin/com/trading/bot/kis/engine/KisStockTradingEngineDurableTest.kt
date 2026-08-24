package com.trading.bot.kis.engine

import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.domain.KisHolding
import com.trading.bot.kis.marketdata.KisMarketCalendar
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.StockPositionStateService
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import com.trading.common.strategy.TradingStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** #64 — durable flush 가 "언제" 일어나는지(배선) 검증. 상태 매핑 자체는 StockPositionStateServiceTest. */
class KisStockTradingEngineDurableTest {

    private lateinit var pm: StockPositionManager
    private lateinit var client: KisClient
    private lateinit var store: MarketDataStore
    private lateinit var stateService: StockPositionStateService
    private lateinit var engine: KisStockTradingEngine

    @BeforeEach
    fun setup() {
        pm = mockk(relaxed = true)
        client = mockk()
        store = mockk()
        stateService = mockk(relaxed = true)
        val strategy = mockk<TradingStrategy>()
        every { strategy.name } returns "rsi"
        // 지금은 이 테스트가 매수·청산 경로를 타지 않아 읽히지 않지만, 엔진이 minCandles 를 보게 된 이상
        // 경로가 늘면 strict mock 이 터진다. relaxed 로 바꾸면 0 이 반환돼 게이트가 무력화되므로 stub 으로 둔다.
        every { strategy.minCandles } returns 21

        engine = KisStockTradingEngine(
            userId = 1L, positionManager = pm, client = client,
            strategies = listOf(strategy), tradingProperties = TradingProperties(),
            marketDataStore = store, marketCalendar = mockk<KisMarketCalendar>(),
            liveEnabled = true, positionStateService = stateService,
        )
    }

    private fun noPriceAvailable() {
        every { store.getLatestTicker(Exchange.KIS, "005930") } returns null
        every { store.getCandles(any(), any(), any(), any()) } returns emptyList()
        coEvery { client.getCurrentPrice("005930") } throws RuntimeException("quote 5xx")
    }

    @Test
    fun `durable change is flushed even when the quote lookup fails`() = runTest {
        // syncFromHoldings 가 청산을 반영해 고점·진입전략을 지우면 durable 변경이 생긴다.
        // 그 직후 시세 조회가 실패해 패스를 빠져나가더라도 그 변경은 저장돼야 한다 —
        // 저장 못 한 채 재시작하면 옛 고점이 살아남아 신규 진입이 즉시 트레일링에 걸린다.
        every { pm.syncFromHoldings(any(), any(), any()) } answers {
            firstArg<StockPosition>().syncFromHolding(0, 0.0)
        }
        val pos = StockPosition("005930").apply {
            peakPrice = 90_000.0
            entryStrategy = "rsi"
            markDurableClean()
        }
        engine.getPositions() // touch
        noPriceAvailable()

        // 엔진이 소유한 포지션에 위 상태를 심는다.
        engine.restorePositionState(listOf("005930"))
        engine.getPositions()["005930"]!!.apply {
            peakPrice = pos.peakPrice
            entryStrategy = pos.entryStrategy
            markDurableClean()
        }

        engine.processSymbol("005930", emptyList<KisHolding>())

        coVerify(exactly = 1) { stateService.upsert(1L, any(), any()) }
    }

    @Test
    fun `clean position is not written`() = runTest {
        every { pm.syncFromHoldings(any(), any(), any()) } returns Unit
        noPriceAvailable()
        engine.restorePositionState(listOf("005930"))

        engine.processSymbol("005930", emptyList<KisHolding>())

        // 변경이 없으면 매 tick upsert 하지 않는다(write 증폭 방지).
        coVerify(exactly = 0) { stateService.upsert(any(), any(), any()) }
    }

    @Test
    fun `restore runs before the loop can trade`() = runTest {
        coEvery { stateService.loadInto(any(), any(), any(), any()) } throws RuntimeException("db down")

        var started = false
        try {
            engine.restorePositionState(listOf("005930"))
            started = true
        } catch (_: RuntimeException) {
            // 복원 실패는 전파돼야 한다 — 호출부가 engine.start() 를 건너뛴다.
        }

        assertTrue(!started, "복원 실패를 삼키면 고점·진입 게이트 없이 매매가 시작된다")
    }
}
