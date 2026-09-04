package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.VwapBand
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VwapBandTest {

    private val strategy = VwapBand()
    private val config = TradingProperties()

    /** 시간순(오래→최신) 종가를 전략 입력(최신순)으로 뒤집는다. 봉당 거래량 1. */
    private fun candles(chronological: List<Double>, withQuoteVolume: Boolean = true, volume: Double = 1.0) =
        chronological.reversed().map {
            Candle(
                market = "KRW-BTC",
                openingPrice = it,
                highPrice = it,
                lowPrice = it,
                tradePrice = it,
                candleAccTradePrice = if (withQuoteVolume) it * volume else 0.0,
                candleAccTradeVolume = volume,
            )
        }

    private val crossUp = List(19) { 10_000.0 } + listOf(9_000.0, 10_500.0)

    @Test
    fun `should buy when close crosses back above VWAP`() = runTest {
        // 직전 종가 9000 < 직전 VWAP 9950, 현재 종가 10500 > 현재 VWAP 9975.
        val candles = candles(crossUp)
        assertTrue(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should not buy while price stays above VWAP`() = runTest {
        val chrono = (0..20).map { 10_000.0 + it * 20.0 }
        assertFalse(strategy.shouldBuy(candles(chrono), 10_400.0, config))
    }

    @Test
    fun `should not buy with insufficient candles`() = runTest {
        val candles = candles(crossUp)
        assertFalse(strategy.shouldBuy(candles.take(strategy.minCandles - 1), candles[0].tradePrice, config))
    }

    @Test
    fun `should fall back to typical price when quote volume is missing`() = runTest {
        val candles = candles(crossUp, withQuoteVolume = false)
        assertTrue(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should not buy when volume is zero`() = runTest {
        val candles = candles(crossUp, withQuoteVolume = false, volume = 0.0)
        assertFalse(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }
}
