package com.trading.bot.characterization

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.Ticker
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.bot.engine.DailyCandleCache
import com.trading.bot.engine.DailyResetManager
import com.trading.bot.engine.PositionManager
import com.trading.bot.engine.TradingEngine
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import com.trading.common.strategy.TradingStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.coVerifySequence
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Upbit 엔진 한 tick 의 외부 호출을 고정한다. 가격은 30초 안의 store 시세가 없으면 getTicker, 매수 평가용 일봉은 store 가
 * 모자라면 dailyCandleCache(주입 시) 또는 getDayCandles(ticker, 60) 이고, 오늘 봉이 없으면 매수 평가를 건너뛴다.
 * 미해소 주문이 있으면 그 tick 에는 reconcile 만 하고, 청산 사유는 손절 > 트레일링 > 익절 > 보유일 순이다.
 * 장 시간 게이트가 없어(24/7) 주말·새벽에도 평가하고, 거래일은 KST 09:00 에 바뀌며, 보유 일수는 주말을 포함한 달력 일수로 센다.
 * 루프는 시작할 때 활성 티커마다 기본 TradingState 를 만들고 syncPosition 한 뒤 tick 을 돈다.
 */
class TradingEngineTickCharacterizationTest {

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val positionManager = mockk<PositionManager>(relaxed = true)
    private val strategy = mockk<TradingStrategy>()
    private val store = mockk<MarketDataStore>(relaxed = true)

    @BeforeEach
    fun setup() {
        every { strategy.name } returns "test_strategy"
        every { strategy.minCandles } returns 21
        every { store.getLatestTicker(any(), any()) } returns null
        every { store.getCandles(any(), any(), any(), any()) } returns emptyList()
        coEvery { strategy.shouldBuy(any(), any(), any()) } returns true
        coEvery { strategy.shouldBuyNormalized(any(), any(), any()) } returns true
    }

    private fun engine(
        clock: Clock,
        props: TradingProperties = TradingProperties(),
        dailyCandleCache: DailyCandleCache? = null,
    ) = TradingEngine(
        upbitClient = upbit,
        positionManager = positionManager,
        dailyResetManager = DailyResetManager(props, clock),
        strategy = strategy,
        tradingProperties = props,
        userId = 1L,
        marketDataStore = store,
        dailyCandleCache = dailyCandleCache,
        clock = clock,
    )

    private fun storeTickerAged(seconds: Long, price: Double) =
        NormalizedTicker(exchange = Exchange.UPBIT, market = "BTC/KRW", price = price, timestamp = Instant.now().minusSeconds(seconds))

    /** [date] KST [hour] 시 정각. 거래일은 clock 의 zone 으로 계산하므로 운영 기본값처럼 KST zone 을 쓴다. */
    private fun kstClock(date: LocalDate, hour: Int): Clock =
        Clock.fixed(date.atTime(hour, 0).atZone(TradingDay.KST).toInstant(), TradingDay.KST)

    private fun restDaily(newest: LocalDate): List<Candle> =
        (0 until 21).map { Candle(candleDateTimeUtc = "${newest.minusDays(it.toLong())}T00:00:00", tradePrice = 100.0) }

    private fun storeDaily(newest: LocalDate): List<NormalizedCandle> =
        (0 until 21).map {
            NormalizedCandle(
                Exchange.UPBIT, "BTC/KRW", 100.0, 100.0, 100.0, 100.0, 1.0,
                openTime = newest.minusDays(it.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant(),
            )
        }

    private val monday: LocalDate = LocalDate.of(2026, 10, 19)

    @Test
    fun `without store data a flat tick calls getTicker then getDayCandles with 60 and nothing else on the client`() = runTest {
        coEvery { upbit.getTicker("KRW-BTC") } returns listOf(Ticker(market = "KRW-BTC", tradePrice = 100.0))
        coEvery { upbit.getDayCandles("KRW-BTC", 60) } returns restDaily(monday)
        val state = TradingState("KRW-BTC")

        engine(kstClock(monday, 12)).processTicker("KRW-BTC", state, strategy)

        coVerifySequence {
            upbit.getTicker("KRW-BTC")
            upbit.getDayCandles("KRW-BTC", 60)
        }
        verify { store.getLatestTicker(Exchange.UPBIT, "BTC/KRW") }
        verify { store.getCandles(Exchange.UPBIT, "BTC/KRW", CandleInterval.D1, 60) }
        coVerify(exactly = 1) { positionManager.buy("KRW-BTC", state, 100.0, "test_strategy") }
    }

    @Test
    fun `a fresh store ticker and today's store daily candles mean no client call at all`() = runTest {
        every { store.getLatestTicker(Exchange.UPBIT, "BTC/KRW") } returns
            NormalizedTicker(exchange = Exchange.UPBIT, market = "BTC/KRW", price = 101.0, timestamp = Instant.now())
        every { store.getCandles(Exchange.UPBIT, "BTC/KRW", CandleInterval.D1, 60) } returns storeDaily(monday)

        engine(kstClock(monday, 12)).processTicker("KRW-BTC", TradingState("KRW-BTC"), strategy)

        confirmVerified(upbit)
        coVerify(exactly = 1) { strategy.shouldBuyNormalized(any(), 101.0, any()) }
        coVerify(exactly = 1) { positionManager.buy("KRW-BTC", any(), 101.0, "test_strategy") }
    }

    @Test
    fun `buy evaluation runs on a Saturday afternoon and a Sunday before dawn`() = runTest {
        val saturday = LocalDate.of(2026, 10, 17)
        val sundayBeforeNine = kstClock(LocalDate.of(2026, 10, 18), 3) // 거래일은 아직 토요일
        coEvery { upbit.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        coEvery { upbit.getDayCandles("KRW-BTC", 60) } returns restDaily(saturday)

        engine(kstClock(saturday, 14)).processTicker("KRW-BTC", TradingState("KRW-BTC"), strategy)
        engine(sundayBeforeNine).processTicker("KRW-BTC", TradingState("KRW-BTC"), strategy)

        coVerify(exactly = 2) { positionManager.buy("KRW-BTC", any(), 100.0, "test_strategy") }
    }

    @Test
    fun `the trading day turns over at 09 00 KST and only then releases the daily buy gate`() {
        val clock = MutableClock(monday.atTime(8, 59, 59).atZone(TradingDay.KST).toInstant(), TradingDay.KST)
        val reset = DailyResetManager(TradingProperties(), clock)
        val state = TradingState("KRW-BTC", boughtToday = true, boughtDate = monday.minusDays(1))
        val states = mapOf("KRW-BTC" to state)

        assertEquals(monday.minusDays(1), reset.getTradingDate())
        assertTrue(reset.checkAndReset(states)) // 첫 호출은 항상 리셋
        assertTrue(state.boughtToday)
        assertFalse(reset.checkAndReset(states))

        clock.advance(1)
        assertEquals(monday, reset.getTradingDate())
        assertTrue(reset.checkAndReset(states))
        assertFalse(state.boughtToday)
    }

    @Test
    fun `maxHoldDays counts weekend days`() {
        val props = TradingProperties(maxHoldDays = 2)
        val friday = LocalDate.of(2026, 10, 16)
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 100.0, holdVolume = 1.0, buyDate = friday)

        assertFalse(DailyResetManager(props, kstClock(LocalDate.of(2026, 10, 17), 10)).shouldSellForDailyReset(state))
        assertTrue(DailyResetManager(props, kstClock(LocalDate.of(2026, 10, 18), 10)).shouldSellForDailyReset(state))
    }

    @Test
    fun `a store price 29 s old is used, one 30 s old falls back to getTicker`() = runTest {
        every { store.getCandles(Exchange.UPBIT, "BTC/KRW", CandleInterval.D1, 60) } returns storeDaily(monday)
        coEvery { upbit.getTicker("KRW-BTC") } returns listOf(Ticker(market = "KRW-BTC", tradePrice = 100.0))

        every { store.getLatestTicker(Exchange.UPBIT, "BTC/KRW") } returns storeTickerAged(29, 101.0)
        engine(kstClock(monday, 12)).processTicker("KRW-BTC", TradingState("KRW-BTC"), strategy)
        coVerify(exactly = 0) { upbit.getTicker(any()) }
        coVerify(exactly = 1) { positionManager.buy("KRW-BTC", any(), 101.0, "test_strategy") }

        every { store.getLatestTicker(Exchange.UPBIT, "BTC/KRW") } returns storeTickerAged(30, 101.0)
        engine(kstClock(monday, 12)).processTicker("KRW-BTC", TradingState("KRW-BTC"), strategy)
        coVerify(exactly = 1) { upbit.getTicker("KRW-BTC") }
        coVerify(exactly = 1) { positionManager.buy("KRW-BTC", any(), 100.0, "test_strategy") }
    }

    @Test
    fun `REST daily candles without today's candle skip buy evaluation`() = runTest {
        coEvery { upbit.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        coEvery { upbit.getDayCandles("KRW-BTC", 60) } returns restDaily(monday.minusDays(1))

        engine(kstClock(monday, 12)).processTicker("KRW-BTC", TradingState("KRW-BTC"), strategy)

        coVerify(exactly = 1) { upbit.getDayCandles("KRW-BTC", 60) }
        coVerify(exactly = 0) { strategy.shouldBuy(any(), any(), any()) }
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `an injected daily candle cache replaces getDayCandles`() = runTest {
        val cache = mockk<DailyCandleCache>()
        coEvery { cache.get("KRW-BTC", 60) } returns restDaily(monday)
        coEvery { upbit.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))

        engine(kstClock(monday, 12), dailyCandleCache = cache).processTicker("KRW-BTC", TradingState("KRW-BTC"), strategy)

        coVerify(exactly = 1) { cache.get("KRW-BTC", 60) }
        coVerify(exactly = 0) { upbit.getDayCandles(any(), any()) }
        coVerify(exactly = 1) { positionManager.buy("KRW-BTC", any(), 100.0, "test_strategy") }
    }

    @Test
    fun `a pending buy is reconciled first and that tick evaluates nothing else, settled or not`() = runTest {
        coEvery { upbit.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        every { positionManager.checkStopLoss(any(), any()) } returns true
        val engine = engine(kstClock(monday, 12))

        val unsettled = TradingState("KRW-BTC", pendingBuyUuid = "u-1")
        coEvery { positionManager.reconcilePendingBuy("KRW-BTC", unsettled, 100.0) } returns null
        engine.processTicker("KRW-BTC", unsettled, strategy)

        val settled = TradingState("KRW-BTC", pendingBuyUuid = "u-2")
        coEvery { positionManager.reconcilePendingBuy("KRW-BTC", settled, 100.0) } coAnswers {
            settled.pendingBuyUuid = null
            settled.position = true
            settled.avgBuyPrice = 200.0
            mockk<TradeRecord>(relaxed = true)
        }
        engine.processTicker("KRW-BTC", settled, strategy)

        coVerify(exactly = 2) { positionManager.reconcilePendingBuy(any(), any(), any()) }
        verify(exactly = 0) { positionManager.checkStopLoss(any(), any()) }
        coVerify(exactly = 0) { positionManager.sell(any(), any(), any(), any()) }
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
        coVerify(exactly = 0) { upbit.getDayCandles(any(), any()) }
    }

    @Test
    fun `a pending sell is reconciled first and an unsettled one blocks exit and entry for that tick`() = runTest {
        coEvery { upbit.getTicker("KRW-BTC") } returns listOf(Ticker(tradePrice = 100.0))
        every { positionManager.checkStopLoss(any(), any()) } returns true
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 200.0, holdVolume = 1.0, pendingSellUuid = "s-1")
        coEvery { positionManager.reconcilePendingSell("KRW-BTC", state, 100.0) } returns null

        engine(kstClock(monday, 12)).processTicker("KRW-BTC", state, strategy)

        coVerify(exactly = 1) { positionManager.reconcilePendingSell("KRW-BTC", state, 100.0) }
        verify(exactly = 0) { positionManager.checkStopLoss(any(), any()) }
        coVerify(exactly = 0) { positionManager.sell(any(), any(), any(), any()) }
        coVerify(exactly = 0) { positionManager.buy(any(), any(), any(), any()) }
    }

    @Test
    fun `exit reasons rank stop-loss, then trailing stop, then take-profit, then hold days`() {
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 100.0, holdVolume = 1.0, buyDate = monday.minusDays(5))
        val engine = engine(kstClock(monday, 12))
        every { positionManager.checkStopLoss(state, 100.0) } returns true
        every { positionManager.checkTrailingStop(state, 100.0) } returns true
        every { positionManager.checkTakeProfit(state, 100.0) } returns true

        assertEquals(SellReason.STOP_LOSS, engine.decideSell(state, 100.0))
        every { positionManager.checkStopLoss(state, 100.0) } returns false
        assertEquals(SellReason.TRAILING_STOP, engine.decideSell(state, 100.0))
        every { positionManager.checkTrailingStop(state, 100.0) } returns false
        assertEquals(SellReason.TAKE_PROFIT, engine.decideSell(state, 100.0))
        every { positionManager.checkTakeProfit(state, 100.0) } returns false
        assertEquals(SellReason.DAILY_RESET, engine.decideSell(state, 100.0))
    }

    @Test
    fun `the loop seeds a default state per ticker and syncs it before the first tick`() = runBlocking {
        coEvery { upbit.getTicker(any()) } returns emptyList()
        val engine = engine(kstClock(monday, 12))

        engine.start(listOf("KRW-BTC"))
        try {
            coVerify(timeout = 5_000) { upbit.getTicker("KRW-BTC") }
        } finally {
            engine.stop()
        }

        coVerifyOrder {
            positionManager.syncPosition("KRW-BTC", TradingState("KRW-BTC"))
            upbit.getTicker("KRW-BTC")
        }
        assertEquals(mapOf("KRW-BTC" to TradingState("KRW-BTC")), engine.getStates())
    }
}
