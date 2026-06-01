package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.strategy.VolatilityBreakout
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TradingStrategy 의 공통 default 매도(shouldSell/shouldSellNormalized) 검증.
 * default 는 데드크로스(5/20) 이며 전략 종류와 무관 — VolatilityBreakout 를 대표 인스턴스로 사용.
 */
class TradingStrategyDefaultSellTest {

    private val config = TradingProperties()
    private val strategy = VolatilityBreakout()

    private fun deadCrossCandles(): List<Candle> =
        listOf(Candle(tradePrice = 50.0)) + (1..20).map { i -> Candle(tradePrice = 200.0 - i * 2.0) }

    private fun uptrendCandles(): List<Candle> =
        (0..20).map { i -> Candle(tradePrice = 200.0 - i * 2.0) }

    private fun normCandle(close: Double) = NormalizedCandle(
        exchange = Exchange.UPBIT,
        market = "KRW-BTC",
        openPrice = close,
        highPrice = close,
        lowPrice = close,
        closePrice = close,
        volume = 1.0,
    )

    @Test
    fun `default shouldSell returns true on dead cross`() = runBlocking {
        assertTrue(strategy.shouldSell(deadCrossCandles(), 50.0, config))
    }

    @Test
    fun `default shouldSell returns false on uptrend`() = runBlocking {
        assertFalse(strategy.shouldSell(uptrendCandles(), 100.0, config))
    }

    @Test
    fun `default shouldSellNormalized delegates via toLegacyCandle`() = runBlocking {
        // NormalizedCandle.closePrice -> Candle.tradePrice 변환 후 동일 데드크로스 판정([0]=최신 순서 보존).
        val normalized = listOf(normCandle(50.0)) + (1..20).map { i -> normCandle(200.0 - i * 2.0) }
        assertTrue(strategy.shouldSellNormalized(normalized, 50.0, config))
    }
}
