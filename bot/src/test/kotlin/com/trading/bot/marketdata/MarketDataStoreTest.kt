package com.trading.bot.marketdata

import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

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

    // 핵심 회귀: CandleAggregator 가 같은 D1 period(openTime)를 매 분봉 갱신·재전송해도(반복 addCandle)
    // 버퍼에는 openTime 당 1개만, 최신 값으로 유지돼야 한다.
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

    // getCandles 계약: 최신 openTime 이 [0]. 삽입 순서가 뒤섞여도 openTime 기준 desc.
    @Test
    fun `getCandles returns newest openTime first regardless of insertion order`() {
        store.addCandle(candle(1_700_086_400L, 101.0)) // middle
        store.addCandle(candle(1_700_173_200L, 102.0)) // newest
        store.addCandle(candle(1_700_000_000L, 100.0)) // oldest

        val result = store.getCandles(Exchange.UPBIT, market, CandleInterval.D1, 10)

        assertEquals(listOf(102.0, 101.0, 100.0), result.map { it.closePrice })
    }

    @Test
    fun `getCandles take returns only newest N`() {
        (0 until 5).forEach { store.addCandle(candle(1_700_000_000L + it * 86_400L, 100.0 + it)) }

        val result = store.getCandles(Exchange.UPBIT, market, CandleInterval.D1, 2)

        assertEquals(listOf(104.0, 103.0), result.map { it.closePrice })
    }

    // MAX_CANDLE_BUFFER_SIZE = 200. distinct openTime 201개 → 가장 오래된 1개 evict.
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
}
