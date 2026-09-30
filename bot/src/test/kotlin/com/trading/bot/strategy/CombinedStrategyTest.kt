package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.strategy.CombinedStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 운영 전략 `combined` 의 매수 조건 — 돌파가 초과 · MA5 > MA20 · RSI(14) 30~70 을 모두 만족할 때만 산다.
 * 매수 거절 입력은 각각 한 조건만 어기도록 만들었다(값은 주석에 손으로 계산해 두었다).
 */
class CombinedStrategyTest {

    private val strategy = CombinedStrategy()
    private val config = TradingProperties(kValue = 0.5)

    /** 오래된 것부터 준 종가로 일봉을 만든다 — 시가 = 전일 종가, 고저 = 시가·종가 ±1. 반환은 최신순(index 0 = 오늘). */
    private fun daily(vararg closes: Double): List<Candle> =
        closes.mapIndexed { i, close ->
            val open = if (i == 0) close else closes[i - 1]
            Candle(
                market = "KRW-BTC",
                openingPrice = open,
                highPrice = maxOf(open, close) + 1,
                lowPrice = minOf(open, close) - 1,
                tradePrice = close,
            )
        }.reversed()

    // +3/−2 로 오르는 21봉: 돌파가 = 112 + (113−108)×0.5 = 114.5, MA5 110 > MA20 106.5, RSI ≈ 59
    private val uptrend = daily(
        100.0, 103.0, 101.0, 104.0, 102.0, 105.0, 103.0, 106.0, 104.0, 107.0, 105.0,
        108.0, 106.0, 109.0, 107.0, 110.0, 108.0, 111.0, 109.0, 112.0, 110.0,
    )

    @Test
    fun `buys when price breaks the target in an uptrend with moderate RSI`() = runTest {
        assertTrue(strategy.shouldBuy(uptrend, 115.0, config))
    }

    @Test
    fun `does not buy at the breakout target`() = runTest {
        assertFalse(strategy.shouldBuy(uptrend, 114.5, config))
    }

    @Test
    fun `does not buy without a short over long MA uptrend`() = runTest {
        // −3/+2 로 내리는 21봉: 돌파가 = 108 + (112−107)×0.5 = 110.5 는 넘고 RSI ≈ 41 이지만 MA5 110 < MA20 113.5
        val downtrend = daily(
            120.0, 117.0, 119.0, 116.0, 118.0, 115.0, 117.0, 114.0, 116.0, 113.0, 115.0,
            112.0, 114.0, 111.0, 113.0, 110.0, 112.0, 109.0, 111.0, 108.0, 110.0,
        )
        assertFalse(strategy.shouldBuy(downtrend, 111.0, config))
    }

    @Test
    fun `does not buy when RSI is above the band`() = runTest {
        // 매일 +1: 돌파가 = 119 + (120−117)×0.5 = 120.5 는 넘고 MA5 118 > MA20 110.5 지만 하락이 없어 RSI 100
        val straightUp = daily(*DoubleArray(21) { 100.0 + it })
        assertFalse(strategy.shouldBuy(straightUp, 121.0, config))
    }

    @Test
    fun `does not buy when RSI is below the band`() = runTest {
        // 첫날 반토막 뒤 매일 +1: 돌파가 = 118 + (119−116)×0.5 = 119.5 는 넘고 MA5 117 > MA20 109.5 지만
        // 첫날 손실이 Wilder 평균에 남아 RSI ≈ 17
        val crashThenRecover = daily(200.0, *DoubleArray(20) { 100.0 + it })
        assertFalse(strategy.shouldBuy(crashThenRecover, 120.0, config))
    }

    @Test
    fun `does not buy with fewer than 21 daily candles`() = runTest {
        // 가장 오래된 봉 하나만 뺀 20봉 — 나머지 세 조건은 그대로 만족한다(RSI ≈ 57)
        assertFalse(strategy.shouldBuy(uptrend.dropLast(1), 115.0, config))
    }

    @Test
    fun `decides the same on the normalized store path`() = runTest {
        // 엔진은 store 캔들이 충분하면 shouldBuyNormalized 를 부른다 — toLegacyCandle 의 시가·고저 매핑이 돌파가에 들어간다.
        val normalized = uptrend.map {
            NormalizedCandle(
                exchange = Exchange.UPBIT,
                market = it.market,
                openPrice = it.openingPrice,
                highPrice = it.highPrice,
                lowPrice = it.lowPrice,
                closePrice = it.tradePrice,
                volume = 1.0,
            )
        }
        assertTrue(strategy.shouldBuyNormalized(normalized, 115.0, config))
        assertFalse(strategy.shouldBuyNormalized(normalized, 114.5, config))
    }
}
