package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.DonchianBreakout
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DonchianBreakoutTest {

    private val strategy = DonchianBreakout()
    private val config = TradingProperties()

    private fun candle(high: Double, close: Double) = Candle(
        market = "KRW-BTC",
        openingPrice = close,
        highPrice = high,
        lowPrice = high - 200.0,
        tradePrice = close,
        candleAccTradeVolume = 100.0,
    )

    /** 최신순: index 0 이 현재 봉, 1..20 이 채널 계산 구간(고가 10_000 고정). */
    private fun series(currentClose: Double, currentHigh: Double = currentClose, size: Int = 25) =
        listOf(candle(currentHigh, currentClose)) +
            List(size - 1) { candle(10_000.0, 9_900.0) }

    @Test
    fun `should buy when close breaks above the prior channel high`() = runTest {
        val series = series(currentClose = 10_050.0, currentHigh = 10_060.0)
        assertTrue(strategy.shouldBuy(series, 10_050.0, config))
    }

    @Test
    fun `should not buy when close stays inside the channel`() = runTest {
        val series = series(currentClose = 9_950.0)
        assertFalse(strategy.shouldBuy(series, 9_950.0, config))
    }

    @Test
    fun `should not buy with insufficient candles`() = runTest {
        val series = series(currentClose = 10_050.0, currentHigh = 10_060.0, size = strategy.minCandles - 1)
        assertFalse(strategy.shouldBuy(series, 10_050.0, config))
    }

    @Test
    fun `should not buy when close only touches the prior high`() = runTest {
        // 돌파는 초과여야 한다 — 동일가는 신호가 아니다.
        val series = series(currentClose = 10_000.0)
        assertFalse(strategy.shouldBuy(series, 10_000.0, config))
    }

    @Test
    fun `channel excludes the current candle`() = runTest {
        // 21번째보다 더 오래된 봉의 고가는 채널에 들어가지 않는다.
        val series = listOf(candle(10_060.0, 10_050.0)) +
            List(20) { candle(10_000.0, 9_900.0) } +
            List(4) { candle(20_000.0, 19_900.0) }
        assertTrue(strategy.shouldBuy(series, 10_050.0, config))
    }
}
