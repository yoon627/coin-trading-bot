package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.CciReversal
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CciReversalTest {

    private val strategy = CciReversal()
    private val config = TradingProperties()

    /** 시간순(오래→최신) 종가를 전략 입력(최신순)으로 뒤집는다. high/low 를 종가에 맞춰 typical price = 종가. */
    private fun candles(chronological: List<Double>): List<Candle> =
        chronological.reversed().map {
            Candle(market = "KRW-BTC", openingPrice = it, highPrice = it, lowPrice = it, tradePrice = it)
        }

    @Test
    fun `should buy when CCI crosses back above -100`() = runTest {
        // 19봉 평탄 → 급락(직전 CCI ≈ -667) → 복귀(현재 CCI ≈ +35).
        val chrono = List(19) { 10_000.0 } + listOf(9_000.0, 10_000.0)
        val candles = candles(chrono)

        assertTrue(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should not buy on a steady uptrend`() = runTest {
        val chrono = (0..20).map { 10_000.0 + it * 20.0 }
        assertFalse(strategy.shouldBuy(candles(chrono), 10_400.0, config))
    }

    @Test
    fun `should not buy with insufficient candles`() = runTest {
        val chrono = List(19) { 10_000.0 } + listOf(9_000.0)
        val candles = candles(chrono)
        assertFalse(strategy.shouldBuy(candles.take(strategy.minCandles - 1), candles[0].tradePrice, config))
    }

    @Test
    fun `should not buy while CCI is still below -100`() = runTest {
        // 급락 후 반등이 미미해 현재 CCI 도 -100 아래(≈ -324) — 교차가 아직 아니다.
        val chrono = List(19) { 10_000.0 } + listOf(9_000.0, 9_050.0)
        val candles = candles(chrono)

        assertFalse(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should not buy on a flat series with zero mean deviation`() = runTest {
        assertFalse(strategy.shouldBuy(candles(List(21) { 10_000.0 }), 10_000.0, config))
    }
}
