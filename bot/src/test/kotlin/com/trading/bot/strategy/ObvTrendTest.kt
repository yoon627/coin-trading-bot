package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.ObvTrend
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ObvTrendTest {

    private val strategy = ObvTrend()
    private val config = TradingProperties()

    /** 과거순으로 만든 뒤 최신순(index 0 = 최신)으로 뒤집어 전략 입력 규약에 맞춘다. */
    private fun latestFirst(chronological: List<Candle>): List<Candle> = chronological.reversed()

    private fun candle(close: Double, volume: Double) = Candle(
        market = "KRW-BTC",
        openingPrice = close,
        highPrice = close + 10.0,
        lowPrice = close - 10.0,
        tradePrice = close,
        candleAccTradeVolume = volume,
    )

    @Test
    fun `should buy when OBV crosses above its 20-period SMA`() = runTest {
        // 21봉 완만한 하락으로 OBV 를 SMA 아래로 끌어내린 뒤, 마지막 봉에서 대량 매수로 상향 교차시킨다.
        val chronological = mutableListOf<Candle>()
        for (i in 0..20) {
            chronological.add(candle(10_000.0 - i * 10.0, 100.0))
        }
        chronological.add(candle(10_500.0, 3_000.0))

        assertTrue(strategy.shouldBuy(latestFirst(chronological), 10_500.0, config))
    }

    @Test
    fun `should not buy while OBV stays below its SMA`() = runTest {
        val chronological = (0..29).map { candle(10_000.0 - it * 10.0, 100.0) }
        assertFalse(strategy.shouldBuy(latestFirst(chronological), 9_710.0, config))
    }

    @Test
    fun `should not buy with insufficient candles`() = runTest {
        // 신호가 날 모양(하락 후 대량 매수)이지만 봉이 하나 모자라면 예외 없이 false.
        val chronological = (0 until strategy.minCandles - 2).map { candle(10_000.0 - it * 10.0, 100.0) }
            .toMutableList()
        chronological.add(candle(10_500.0, 5_000.0))

        val candles = latestFirst(chronological)
        assertTrue(candles.size == strategy.minCandles - 1)
        assertFalse(strategy.shouldBuy(candles, 10_500.0, config))
    }

    @Test
    fun `should not buy when OBV merely touches its SMA`() = runTest {
        // 가격 변화가 없으면 OBV 가 0 으로 고정돼 SMA 와 정확히 같다 — 교차는 강한 부등호라 신호가 없어야 한다.
        val chronological = (0..29).map { candle(10_000.0, 100.0) }
        assertFalse(strategy.shouldBuy(latestFirst(chronological), 10_000.0, config))
    }

    @Test
    fun `should buy at exactly the declared minimum candle count`() = runTest {
        // 선언된 minCandles 에서 실제로 판정이 가능해야 한다 — 선언이 실제보다 작으면 여기서 드러난다.
        val chronological = mutableListOf<Candle>()
        for (i in 0..19) {
            chronological.add(candle(10_000.0 - i * 10.0, 100.0))
        }
        chronological.add(candle(10_500.0, 5_000.0))

        val candles = latestFirst(chronological)
        assertTrue(candles.size == strategy.minCandles)
        assertTrue(strategy.shouldBuy(candles, 10_500.0, config))
    }
}
