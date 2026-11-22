package com.trading.bot.marketdata

import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import com.trading.common.domain.NormalizedTrade
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MarketDataStoreTest {

    private val store = MarketDataStore()
    private val market = "KRW-BTC"

    private fun candle(epochSeconds: Long, close: Double, interval: CandleInterval = CandleInterval.D1) =
        NormalizedCandle(
            exchange = Exchange.UPBIT,
            market = market,
            openPrice = close,
            highPrice = close,
            lowPrice = close,
            closePrice = close,
            volume = 1.0,
            interval = interval,
            openTime = Instant.ofEpochSecond(epochSeconds),
        )

    // CandleAggregator 는 같은 D1 봉을 분봉마다 다시 보내므로 openTime 당 최신 값 하나만 남아야 한다.
    @Test
    fun `addCandle upserts by openTime keeping single latest candle`() {
        repeat(30) { i -> store.addCandle(candle(epochSeconds = 1_700_000_000L, close = 100.0 + i)) }

        val result = store.getCandles(Exchange.UPBIT, market, CandleInterval.D1, 30)

        assertEquals(1, result.size, "same openTime must collapse to a single candle")
        assertEquals(129.0, result[0].closePrice, "latest value must win on upsert")
    }

    @Test
    fun `addCandle keeps candles with distinct openTimes`() {
        store.addCandle(candle(1_700_000_000L, 100.0))
        store.addCandle(candle(1_700_086_400L, 101.0))
        store.addCandle(candle(1_700_173_200L, 102.0))

        assertEquals(3, store.getCandles(Exchange.UPBIT, market, CandleInterval.D1, 10).size)
    }

    // 삽입 순서와 무관하게 최신 openTime 이 [0] 이다.
    @Test
    fun `getCandles returns newest openTime first regardless of insertion order`() {
        store.addCandle(candle(1_700_086_400L, 101.0))
        store.addCandle(candle(1_700_173_200L, 102.0))
        store.addCandle(candle(1_700_000_000L, 100.0))

        val result = store.getCandles(Exchange.UPBIT, market, CandleInterval.D1, 10)

        assertEquals(listOf(102.0, 101.0, 100.0), result.map { it.closePrice })
    }

    @Test
    fun `getCandles take returns only newest N`() {
        (0 until 5).forEach { store.addCandle(candle(1_700_000_000L + it * 86_400L, 100.0 + it)) }

        val result = store.getCandles(Exchange.UPBIT, market, CandleInterval.D1, 2)

        assertEquals(listOf(104.0, 103.0), result.map { it.closePrice })
    }

    // 버퍼 상한은 200 개다.
    @Test
    fun `addCandle evicts oldest beyond capacity`() {
        (0 until 201).forEach { store.addCandle(candle(1_700_000_000L + it * 86_400L, it.toDouble())) }

        val result = store.getCandles(Exchange.UPBIT, market, CandleInterval.D1, 500)

        assertEquals(200, result.size)
        assertEquals(200.0, result.first().closePrice, "newest kept")
        assertEquals(1.0, result.last().closePrice, "oldest (close=0.0) evicted")
    }

    @Test
    fun `getCandles isolates by interval`() {
        store.addCandle(candle(1_700_000_000L, 100.0, CandleInterval.D1))
        store.addCandle(candle(1_700_000_000L, 50.0, CandleInterval.H1))

        assertEquals(100.0, store.getCandles(Exchange.UPBIT, market, CandleInterval.D1, 10)[0].closePrice)
        assertEquals(50.0, store.getCandles(Exchange.UPBIT, market, CandleInterval.H1, 10)[0].closePrice)
    }

    @Test
    fun `getCandles returns empty for unknown key`() {
        assertEquals(0, store.getCandles(Exchange.UPBIT, "KRW-XRP", CandleInterval.D1, 10).size)
    }

    private fun ticker(price: Double) =
        NormalizedTicker(exchange = Exchange.UPBIT, market = "BTC/KRW", price = price)

    @Test
    fun `updateTicker keeps the latest ticker per market`() {
        store.updateTicker(ticker(100.0))
        store.updateTicker(ticker(101.0))
        assertEquals(101.0, store.getLatestTicker(Exchange.UPBIT, "BTC/KRW")?.price)
    }

    private fun trade(seq: Long, market: String = "BTC/KRW", price: Double = 100.0) = NormalizedTrade(
        exchange = Exchange.UPBIT, market = market, price = price, volume = 1.0,
        tradeTime = Instant.ofEpochMilli(1_700_000_000_000L + seq), sequentialId = seq,
    )

    @Test
    fun `publishTrade delivers each new trade to subscribers in order`() = runTest {
        val received = mutableListOf<Long>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { store.trades.collect { received += it.sequentialId } }

        assertTrue(store.publishTrade(trade(1)))
        assertTrue(store.publishTrade(trade(2)))
        assertTrue(store.publishTrade(trade(1, market = "ETH/KRW")))

        assertEquals(listOf(1L, 2L, 1L), received)
        job.cancel()
    }

    // 재연결 직후 같은 체결이 다시 올 수 있다. 체결 번호는 늘기만 하고 연속이 아니라 빠짐은 알 수 없다.
    @Test
    fun `publishTrade drops a repeated sequential id per market and counts it`() = runTest {
        val received = mutableListOf<Long>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { store.trades.collect { received += it.sequentialId } }

        store.publishTrade(trade(1))
        assertFalse(store.publishTrade(trade(1, price = 999.0)))
        store.publishTrade(trade(5))
        store.publishTrade(trade(5, market = "ETH/KRW"))

        assertEquals(listOf(1L, 5L, 5L), received)
        assertEquals(
            mapOf("BTC/KRW" to TradeIntake(received = 3, duplicates = 1), "ETH/KRW" to TradeIntake(received = 1, duplicates = 0)),
            store.drainTradeIntake(),
        )
        assertEquals(emptyMap<String, TradeIntake>(), store.drainTradeIntake())
        job.cancel()
    }

    @Test
    fun `duplicate memory is bounded per market`() {
        store.publishTrade(trade(0))
        (1..MarketDataStore.TRADE_DEDUP_WINDOW.toLong()).forEach { store.publishTrade(trade(it)) }

        assertTrue(store.publishTrade(trade(0)), "the oldest id has left the bounded memory")
    }

    // 지금 가격의 출처는 ticker 하나다.
    @Test
    fun `trades do not change the latest ticker`() {
        store.updateTicker(ticker(100.0))
        store.publishTrade(trade(1, price = 200.0))

        assertEquals(100.0, store.getLatestTicker(Exchange.UPBIT, "BTC/KRW")?.price)
    }

    // 구독자가 멈춰 있어도 수집(발행 쪽)이 막히면 안 된다. 넘치면 오래된 체결부터 버린다.
    @Test
    fun `a stalled subscriber neither blocks publishing nor gets the oldest trades`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val received = mutableListOf<Long>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.trades.collect {
                gate.await()
                received += it.sequentialId
            }
        }
        val total = MarketDataStore.TRADE_BUFFER_CAPACITY + 10L
        (1..total).forEach { store.publishTrade(trade(it)) }
        gate.complete(Unit)

        assertEquals(total, received.last())
        assertTrue(received.size < total, "overflow must drop rather than wait")
        job.cancel()
    }
}
