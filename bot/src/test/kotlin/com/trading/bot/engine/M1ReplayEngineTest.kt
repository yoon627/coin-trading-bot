package com.trading.bot.engine

import com.trading.common.domain.Candle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * M1ReplayEngine 결정론 검증 — 합성 M1 시퀀스로 "먼저 닿는 게이트"·한도봉 경계·window 미달을 확인.
 * D1 worst-case 가정과 달라지는 지점(TP 선행)이 편향 측정의 핵심이므로 특히 (a) 를 가드.
 */
class M1ReplayEngineTest {

    private val entryUtc = LocalDateTime.parse("2024-01-02T00:00:00")
    private val limitInstant = LocalDateTime.parse("2024-01-03T00:00:00") // 진입일 + 1일(maxHoldDays=1)

    private fun m1(minute: Int, open: Double, high: Double, low: Double, close: Double): Candle {
        val t = entryUtc.plusMinutes(minute.toLong())
        return Candle(
            market = "KRW-BTC",
            candleDateTimeUtc = t.toString(),
            openingPrice = open, highPrice = high, lowPrice = low, tradePrice = close,
        )
    }

    private val config = BacktestConfig(maxLossPct = 3.0, takeProfitPct = 5.0, trailingStopPct = 2.0, trailingArmPct = 0.0)

    @Test
    fun `TP reached before SL yields take profit (unlike D1 worst-case)`() {
        // 봉1 고점이 TP선(10500)을 먼저 침. 뒤 봉이 SL선을 쳐도 순서상 TP 가 먼저 → TAKE_PROFIT.
        // D1 은 한 봉에 둘 다면 worst-case 로 SL 을 찍었을 상황 — 이 차이가 편향.
        val bars = listOf(
            m1(1, 10000.0, 10600.0, 10000.0, 10550.0), // TP선 도달
            m1(2, 10550.0, 10550.0, 9500.0, 9600.0),   // 이후 SL선(무의미 — 이미 청산)
        )
        val r = M1ReplayEngine.replayExit(10000.0, entryUtc, limitInstant, bars, config.copy(trailingStopPct = 99.0))
        assertEquals("TAKE_PROFIT", r.exit?.reason)
        assertEquals(10500.0, r.exit?.sellPrice)
    }

    @Test
    fun `monotone decline after peak exits at trailing line with profit`() {
        val bars = listOf(
            m1(1, 10000.0, 10500.0, 10400.0, 10450.0), // 고점 형성(peak 10500), 트레일링 arm 은 다음 봉부터
            m1(2, 10450.0, 10450.0, 10200.0, 10250.0), // 저점 10200 → dropFromPeak 2.86% >= 2 → 트레일링
        )
        // TP 억제(99%)해 트레일링만 검증 — 봉1 고점이 기본 TP선(5%)을 치므로.
        val r = M1ReplayEngine.replayExit(10000.0, entryUtc, limitInstant, bars, config.copy(takeProfitPct = 99.0))
        assertEquals("TRAILING_STOP", r.exit?.reason)
        assertEquals(10290.0, r.exit?.sellPrice!!, 1e-6) // 10500*(1-0.02), 이익
    }

    @Test
    fun `flat then hold-limit boundary exits at boundary bar open`() {
        val bars = listOf(
            m1(1, 10000.0, 10010.0, 9990.0, 10000.0),
            // 다음날 00:00Z(=limitInstant) 봉 = 한도봉 → TIME_EXIT at open
            Candle("KRW-BTC", candleDateTimeUtc = limitInstant.toString(), openingPrice = 10005.0, highPrice = 10005.0, lowPrice = 10005.0, tradePrice = 10005.0),
        )
        val r = M1ReplayEngine.replayExit(10000.0, entryUtc, limitInstant, bars, config)
        assertEquals("TIME_EXIT", r.exit?.reason)
        assertEquals(10005.0, r.exit?.sellPrice) // 경계봉 open
    }

    @Test
    fun `missing boundary bar makes next bar the hold-limit exit`() {
        // limitInstant 정각 봉 결측 → t >= limitInstant 인 첫 봉이 한도봉이 됨.
        val bars = listOf(
            m1(1, 10000.0, 10010.0, 9990.0, 10000.0),
            Candle("KRW-BTC", candleDateTimeUtc = limitInstant.plusMinutes(3).toString(), openingPrice = 10007.0, highPrice = 10007.0, lowPrice = 10007.0, tradePrice = 10007.0),
        )
        val r = M1ReplayEngine.replayExit(10000.0, entryUtc, limitInstant, bars, config)
        assertEquals("TIME_EXIT", r.exit?.reason)
        assertEquals(10007.0, r.exit?.sellPrice)
    }

    @Test
    fun `window short of limit returns no exit (excluded from aggregation)`() {
        val bars = listOf(
            m1(1, 10000.0, 10050.0, 9990.0, 10010.0),
            m1(2, 10010.0, 10040.0, 9995.0, 10020.0),
        ) // 게이트 미달 + limitInstant 미도달
        val r = M1ReplayEngine.replayExit(10000.0, entryUtc, limitInstant, bars, config.copy(maxLossPct = 99.0, takeProfitPct = 99.0, trailingStopPct = 99.0))
        assertNull(r.exit)
        assertFalse(r.reachedLimit)
        assertEquals(2, r.barsSeen)
    }
}
