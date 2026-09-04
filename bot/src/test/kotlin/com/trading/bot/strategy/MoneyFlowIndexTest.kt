package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.MoneyFlowIndex
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MoneyFlowIndexTest {

    private val strategy = MoneyFlowIndex()
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
    fun `should buy when MFI crosses above the oversold line`() = runTest {
        // 직전까지는 매도 flow 만 있어 MFI=0, 마지막 대량 매수 봉이 20 위로 끌어올린다.
        val chronological = (0..23).map { candle(10_000.0 - it * 10.0, 10.0) }.toMutableList()
        chronological.add(candle(10_100.0, 100.0))

        assertTrue(strategy.shouldBuy(latestFirst(chronological), 10_100.0, config))
    }

    @Test
    fun `should not buy during a continuous sell-off`() = runTest {
        val chronological = (0..29).map { candle(10_000.0 - it * 10.0, 10.0) }
        assertFalse(strategy.shouldBuy(latestFirst(chronological), 9_710.0, config))
    }

    @Test
    fun `should not buy with insufficient candles`() = runTest {
        val chronological = (0 until strategy.minCandles - 2).map { candle(10_000.0 - it * 10.0, 10.0) }
            .toMutableList()
        chronological.add(candle(10_100.0, 100.0))
        val candles = latestFirst(chronological)
        assertTrue(candles.size == strategy.minCandles - 1)
        assertFalse(strategy.shouldBuy(candles, 10_100.0, config))
    }

    @Test
    fun `should not buy when MFI is already well above the oversold line`() = runTest {
        // 이미 20 위에 있으면 교차가 아니므로 신호가 없어야 한다(재진입 방지).
        val chronological = (0..29).map { candle(10_000.0 + it * 10.0, 10.0) }
        assertFalse(strategy.shouldBuy(latestFirst(chronological), 10_290.0, config))
    }

    @Test
    fun `should buy at exactly the declared minimum candle count`() = runTest {
        val chronological = (0 until strategy.minCandles - 1).map { candle(10_000.0 - it * 10.0, 10.0) }
            .toMutableList()
        chronological.add(candle(10_100.0, 100.0))

        val candles = latestFirst(chronological)
        assertTrue(candles.size == strategy.minCandles)
        assertTrue(strategy.shouldBuy(candles, 10_100.0, config))
    }
}
