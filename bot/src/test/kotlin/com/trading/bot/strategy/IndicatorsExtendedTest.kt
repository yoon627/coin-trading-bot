package com.trading.bot.strategy

import com.trading.common.domain.Candle
import com.trading.common.strategy.Indicators
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sin

class IndicatorsExtendedTest {

    // --- calculateRsi edge cases ---

    @Test
    fun `calculateRsi returns 50 for insufficient data`() {
        val candles = (0..5).map { Candle(tradePrice = 100.0) }
        assertEquals(50.0, Indicators.calculateRsi(candles, 14))
    }

    @Test
    fun `calculateRsi returns 100 for all gains`() {
        // All prices increasing -> RSI close to 100
        val candles = (0..20).map { i -> Candle(tradePrice = 200.0 - i * 5.0) }
        val rsi = Indicators.calculateRsi(candles, 14)
        assertTrue(rsi > 90, "RSI should be high for monotonic increase: $rsi")
    }

    @Test
    fun `calculateRsi returns near 0 for all losses`() {
        // All prices decreasing -> RSI close to 0
        val candles = (0..20).map { i -> Candle(tradePrice = 100.0 + i * 5.0) }
        val rsi = Indicators.calculateRsi(candles, 14)
        assertTrue(rsi < 10, "RSI should be low for monotonic decrease: $rsi")
    }

    @Test
    fun `calculateRsi returns around 50 for flat prices`() {
        val candles = (0..20).map { Candle(tradePrice = 100.0) }
        val rsi = Indicators.calculateRsi(candles, 14)
        // Flat prices = no gains, no losses -> avgLoss = 0 -> RSI = 100 technically
        // But if all changes are 0, avgGain and avgLoss both = 0
        assertTrue(rsi >= 50.0) // division by zero returns 100 in impl
    }

    // --- calculateMa edge cases ---

    @Test
    fun `calculateMa returns 0 for insufficient data`() {
        val candles = (0..3).map { Candle(tradePrice = 100.0) }
        assertEquals(0.0, Indicators.calculateMa(candles, 5))
    }

    @Test
    fun `calculateMa calculates correct average`() {
        val candles = listOf(
            Candle(tradePrice = 50.0),
            Candle(tradePrice = 40.0),
            Candle(tradePrice = 30.0),
            Candle(tradePrice = 20.0),
            Candle(tradePrice = 10.0),
        )
        assertEquals(30.0, Indicators.calculateMa(candles, 5))
    }

    @Test
    fun `calculateMa uses only first N candles`() {
        val candles = listOf(
            Candle(tradePrice = 100.0),
            Candle(tradePrice = 200.0),
            Candle(tradePrice = 300.0),
            Candle(tradePrice = 999.0), // not included in MA(3)
        )
        assertEquals(200.0, Indicators.calculateMa(candles, 3))
    }

    // --- calculateBollingerBands ---

    @Test
    fun `calculateBollingerBands returns null for insufficient data`() {
        val candles = (0..10).map { Candle(tradePrice = 100.0) }
        assertNull(Indicators.calculateBollingerBands(candles, 20))
    }

    @Test
    fun `calculateBollingerBands for flat prices has zero width`() {
        val candles = (0..25).map { Candle(tradePrice = 100.0) }
        val bb = Indicators.calculateBollingerBands(candles, 20)
        assertNotNull(bb)
        assertEquals(100.0, bb!!.middle)
        assertEquals(100.0, bb.upper) // stddev = 0
        assertEquals(100.0, bb.lower)
        assertEquals(0.0, bb.width)
    }

    @Test
    fun `calculateBollingerBands upper is above lower`() {
        val candles = (0..25).map { i -> Candle(tradePrice = 100.0 + (i % 5) * 10.0) }
        val bb = Indicators.calculateBollingerBands(candles, 20)
        assertNotNull(bb)
        assertTrue(bb!!.upper > bb.lower)
        assertTrue(bb.upper > bb.middle)
        assertTrue(bb.middle > bb.lower)
    }

    // --- calculateMacd ---

    @Test
    fun `calculateMacd returns null for insufficient data`() {
        val candles = (0..20).map { Candle(tradePrice = 100.0) }
        assertNull(Indicators.calculateMacd(candles, 12, 26, 9))
    }

    @Test
    fun `calculateMacd returns result for sufficient data`() {
        val candles = (0..40).map { i -> Candle(tradePrice = 100.0 + i * 2.0) }
        val macd = Indicators.calculateMacd(candles, 12, 26, 9)
        assertNotNull(macd)
        // histogram = macd - signal
        assertEquals(macd!!.macd - macd.signal, macd.histogram, 0.001)
    }

    // 표준 MACD(TA-Lib 관례): EMA 는 첫 period 개의 SMA 로 seed 하고 **받은 히스토리 전체**를 누적한다. fast 창은 slow 창의
    // 꼬리에서 시작하고(ta_MACD.c), 시그널은 slow EMA 가 정의되는 시점부터의 MACD 선 전체에 EMA(9). 35봉만 잘라 첫 값으로
    // seed 하면 EMA 가 수렴하지 못해 ~42% 오차(#27). 이 참조는 구현과 같은 규칙을 다시 쓴 것이라 전사 실수만 잡는다 —
    // 규칙 자체의 독립 앵커는 아래 손계산 테스트다.
    private fun referenceMacd(closesOldestFirst: List<Double>, fast: Int, slow: Int, signal: Int): Triple<Double, Double, Double> {
        fun ema(series: List<Double>, period: Int): List<Double> {
            val k = 2.0 / (period + 1)
            val out = ArrayList<Double>(series.size - period + 1)
            var value = series.take(period).average()
            out.add(value)
            for (i in period until series.size) {
                value = series[i] * k + value * (1 - k)
                out.add(value)
            }
            return out
        }
        val fastEma = ema(closesOldestFirst.drop(slow - fast), fast) // fast 창은 slow 창의 꼬리에서 시작
        val slowEma = ema(closesOldestFirst, slow)
        val macdLine = fastEma.zip(slowEma) { f, s -> f - s }
        val signalLine = ema(macdLine, signal)
        val macd = macdLine.last()
        val sig = signalLine.last()
        return Triple(macd, sig, macd - sig)
    }

    @Test
    fun `calculateMacd matches the standard full-history MACD`() {
        // 추세 + 주기 성분이 있는 결정적 시계열(최신순 입력 규약: index 0 = 최신).
        val closesOldestFirst = (0 until 120).map { i -> 100.0 + 0.3 * i + 10.0 * sin(i / 7.0) }
        val candles = closesOldestFirst.reversed().map { Candle(tradePrice = it) }
        val (macd, signal, hist) = referenceMacd(closesOldestFirst, 12, 26, 9)

        val result = Indicators.calculateMacd(candles, 12, 26, 9)!!

        assertEquals(macd, result.macd, 1e-9)
        assertEquals(signal, result.signal, 1e-9)
        assertEquals(hist, result.histogram, 1e-9)
        // 35봉으로 잘라 계산한 값과는 달라야 한다 — 같다면 히스토리를 버리고 있는 것이다.
        val truncated = Indicators.calculateMacd(candles.take(35), 12, 26, 9)!!
        assertTrue(abs(truncated.macd - result.macd) > 1e-3, "전체 히스토리를 쓰면 35봉 절단 결과와 달라야 한다")
    }

    @Test
    fun `calculateMacd reproduces a hand-computed TA-Lib case`() {
        // 참조 구현과 독립인 외부 앵커 — 종이로 검증한 값. TA-Lib 규칙: EMA seed 는 첫 period 개 SMA, fast 창은 slow 창의
        // 꼬리(bars[slow-fast..slow-1]), 시그널은 MACD 선 첫 signal 개 SMA 로 seed.
        // closes(오래된 순) = 1,3,2,5,4,7 / fast=2, slow=3, signal=2
        //   slow EMA(3): seed SMA(1,3,2)=2 → 3.5 → 3.75 → 5.375
        //   fast EMA(2): seed SMA(3,2)=2.5 → 25/6 → 73/18 → 325/54
        //   MACD: 1/2, 2/3, 11/36, 139/216 · signal EMA(2): seed 7/12 → 43/108 → 91/162 · hist = 53/648
        val candles = listOf(1.0, 3.0, 2.0, 5.0, 4.0, 7.0).reversed().map { Candle(tradePrice = it) }

        val result = Indicators.calculateMacd(candles, fastPeriod = 2, slowPeriod = 3, signalPeriod = 2)!!

        assertEquals(139.0 / 216.0, result.macd, 1e-12)
        assertEquals(91.0 / 162.0, result.signal, 1e-12)
        assertEquals(53.0 / 648.0, result.histogram, 1e-12)
    }

    @Test
    fun `calculateMacd is zero on a flat series`() {
        val candles = (0 until 60).map { Candle(tradePrice = 100.0) }
        val result = Indicators.calculateMacd(candles, 12, 26, 9)!!
        assertEquals(0.0, result.macd, 1e-12)
        assertEquals(0.0, result.signal, 1e-12)
    }

    // --- checkGoldenCross ---

    @Test
    fun `checkGoldenCross returns false for insufficient data`() {
        val candles = (0..10).map { Candle(tradePrice = 100.0) }
        assertFalse(Indicators.checkGoldenCross(candles))
    }

    @Test
    fun `checkGoldenCross returns false for flat prices`() {
        val candles = (0..25).map { Candle(tradePrice = 100.0) }
        assertFalse(Indicators.checkGoldenCross(candles))
    }

    // --- checkDeadCross ---

    @Test
    fun `checkDeadCross returns false for insufficient data`() {
        val candles = (0..10).map { Candle(tradePrice = 100.0) }
        assertFalse(Indicators.checkDeadCross(candles))
    }

    @Test
    fun `checkDeadCross returns false for flat prices`() {
        val candles = (0..25).map { Candle(tradePrice = 100.0) }
        assertFalse(Indicators.checkDeadCross(candles))
    }

    @Test
    fun `checkDeadCross returns false for uptrend`() {
        // candles[0]=최신. 최신이 높은 단조 상승 -> shortMa > longMa -> 데드크로스 아님
        val candles = (0..20).map { i -> Candle(tradePrice = 200.0 - i * 2.0) }
        assertFalse(Indicators.checkDeadCross(candles))
    }

    @Test
    fun `checkDeadCross returns true when short MA crosses below long MA`() {
        // 어제까지 상승추세(prevShort >= prevLong), 최신 봉(c0) 급락으로 shortMa 가 longMa 아래로 하향 교차.
        // shortMa(166) < longMa(173.5), prevShortMa(194) >= prevLongMa(179) -> 데드크로스.
        val candles = listOf(Candle(tradePrice = 50.0)) +
            (1..20).map { i -> Candle(tradePrice = 200.0 - i * 2.0) }
        assertTrue(Indicators.checkDeadCross(candles))
    }

    @Test
    fun `checkDeadCross treats flat-then-drop as cross via equality boundary`() {
        // 과거 완전 평평(prevShortMa == prevLongMa) + 최신 급락 -> prev 의 등호(>=)로 데드크로스 true.
        // checkGoldenCross(prev <=) 와 대칭인 의도된 경계 동작을 고정.
        val candles = listOf(Candle(tradePrice = 10.0)) + (1..20).map { Candle(tradePrice = 100.0) }
        assertTrue(Indicators.checkDeadCross(candles))
    }

    @Test
    fun `checkDeadCross returns false for exactly 20 candles`() {
        // longPeriod(20) + 1 = 21 미만이면 평가 안 함.
        val candles = (0..19).map { i -> Candle(tradePrice = 200.0 - i * 2.0) }
        assertFalse(Indicators.checkDeadCross(candles))
    }

    // --- isMaUptrend ---

    @Test
    fun `isMaUptrend returns true for uptrending prices`() {
        // More recent candles have higher prices -> short MA > long MA
        val candles = (0..25).map { i -> Candle(tradePrice = 200.0 - i * 3.0) }
        assertTrue(Indicators.isMaUptrend(candles))
    }

    @Test
    fun `isMaUptrend returns false for downtrending prices`() {
        val candles = (0..25).map { i -> Candle(tradePrice = 100.0 + i * 3.0) }
        assertFalse(Indicators.isMaUptrend(candles))
    }

    // --- calculateStdDev ---

    @Test
    fun `calculateStdDev returns 0 for flat prices`() {
        val candles = (0..10).map { Candle(tradePrice = 100.0) }
        assertEquals(0.0, Indicators.calculateStdDev(candles, 10))
    }

    @Test
    fun `calculateStdDev returns positive for varying prices`() {
        val candles = (0..10).map { i -> Candle(tradePrice = 100.0 + (i % 2) * 50.0) }
        assertTrue(Indicators.calculateStdDev(candles, 10) > 0)
    }

    // --- calculateEma ---

    @Test
    fun `calculateEma returns 0 for insufficient data`() {
        val candles = (0..3).map { Candle(tradePrice = 100.0) }
        assertEquals(0.0, Indicators.calculateEma(candles, 5))
    }

    @Test
    fun `calculateEma gives more weight to recent prices`() {
        val candles = listOf(
            Candle(tradePrice = 200.0), // most recent
            Candle(tradePrice = 100.0),
            Candle(tradePrice = 100.0),
            Candle(tradePrice = 100.0),
            Candle(tradePrice = 100.0),
        )
        val ema = Indicators.calculateEma(candles, 5)
        val sma = Indicators.calculateMa(candles, 5)
        // EMA should be closer to 200 than SMA
        assertTrue(ema > sma, "EMA ($ema) should be > SMA ($sma) when recent price is high")
    }

    // --- calculateTargetPrice ---

    @Test
    fun `calculateTargetPrice with no range returns opening price`() {
        val candles = listOf(
            Candle(openingPrice = 100.0, highPrice = 50.0, lowPrice = 50.0, tradePrice = 100.0),
            Candle(openingPrice = 90.0, highPrice = 50.0, lowPrice = 50.0, tradePrice = 90.0),
        )
        assertEquals(100.0, Indicators.calculateTargetPrice(candles, 0.5))
    }

    @Test
    fun `calculateTargetPrice returns 0 for insufficient candles`() {
        val candles = listOf(Candle(tradePrice = 100.0))
        assertEquals(0.0, Indicators.calculateTargetPrice(candles))
    }
}
