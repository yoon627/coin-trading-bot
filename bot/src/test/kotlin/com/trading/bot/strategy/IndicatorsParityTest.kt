package com.trading.bot.strategy

import com.trading.common.domain.Candle
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.strategy.Indicators
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * indicator/strategy Indicators 통합(M12) 회귀 보호.
 * 같은 OHLC 를 Candle(매매 경로)과 NormalizedCandle(차트 경로)로 만들어 모든 지표가 동일값을 내는지,
 * 그리고 NormalizedCandle 필드 매핑이 swap 없이 정확한지 검증한다.
 */
class IndicatorsParityTest {

    private fun candle(open: Double, high: Double, low: Double, close: Double) =
        Candle(openingPrice = open, highPrice = high, lowPrice = low, tradePrice = close)

    private fun normalized(open: Double, high: Double, low: Double, close: Double) =
        NormalizedCandle(
            exchange = Exchange.UPBIT,
            market = "KRW-BTC",
            openPrice = open,
            highPrice = high,
            lowPrice = low,
            closePrice = close,
            volume = 0.0,
        )

    // OHLC 를 서로 다른 값으로 구성 → close↔open 등 필드 swap 버그를 검출한다.
    private val ohlc: List<DoubleArray> = (1..40).map { i ->
        val base = 100.0 + i
        doubleArrayOf(base, base + 5.0, base - 3.0, base + 2.0) // open, high, low, close
    }
    private val candles = ohlc.map { candle(it[0], it[1], it[2], it[3]) }
    private val normalizedCandles = ohlc.map { normalized(it[0], it[1], it[2], it[3]) }

    @Test
    fun `모든 지표가 Candle 과 NormalizedCandle 에서 동일값`() {
        assertEquals(Indicators.calculateRsi(candles), Indicators.calculateRsi(normalizedCandles))
        assertEquals(Indicators.calculateMa(candles, 5), Indicators.calculateMa(normalizedCandles, 5))
        assertEquals(Indicators.calculateMa(candles, 20), Indicators.calculateMa(normalizedCandles, 20))
        assertEquals(
            Indicators.calculateTargetPrice(candles, 0.5),
            Indicators.calculateTargetPrice(normalizedCandles, 0.5),
        )
        assertEquals(Indicators.calculateEma(candles, 12), Indicators.calculateEma(normalizedCandles, 12))
        assertEquals(Indicators.calculateStdDev(candles, 10), Indicators.calculateStdDev(normalizedCandles, 10))
        assertEquals(Indicators.checkGoldenCross(candles), Indicators.checkGoldenCross(normalizedCandles))
        assertEquals(Indicators.checkDeadCross(candles), Indicators.checkDeadCross(normalizedCandles))
        assertEquals(Indicators.isMaUptrend(candles), Indicators.isMaUptrend(normalizedCandles))
        assertEquals(
            Indicators.calculateBollingerBands(candles),
            Indicators.calculateBollingerBands(normalizedCandles),
        )
        assertEquals(Indicators.calculateMacd(candles), Indicators.calculateMacd(normalizedCandles))
    }

    @Test
    fun `NormalizedCandle 필드 매핑이 정확 (swap 없음)`() {
        val nc = listOf(
            normalized(open = 100.0, high = 110.0, low = 90.0, close = 105.0),
            normalized(open = 50.0, high = 60.0, low = 40.0, close = 55.0),
        )
        // close 매핑: MA(1) = 첫 캔들의 close
        assertEquals(105.0, Indicators.calculateMa(nc, 1))
        // open/high/low 매핑: target = today.open + (yesterday.high - yesterday.low) * 0.5
        assertEquals(100.0 + (60.0 - 40.0) * 0.5, Indicators.calculateTargetPrice(nc, 0.5))
    }
}
