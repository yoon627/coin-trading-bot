package com.trading.bot.engine

import com.trading.common.domain.Candle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * IntrabarExitModel.evaluate 판정식 격리 검증 — BacktestEngine 을 거치지 않고 게이트 로직만 확인해
 * processExit 추출의 정합성을 1차 가드한다(#33 intrabar 시나리오 재현).
 */
class IntrabarExitModelTest {

    private fun bar(open: Double, high: Double, low: Double, close: Double) =
        Candle(market = "KRW-BTC", openingPrice = open, highPrice = high, lowPrice = low, tradePrice = close)

    @Test
    fun `trailing fires on low penetration below entry using arm peak`() {
        // armPeak(직전 고점) 10500 → 트레일링선 10290(>진입가). 저점 9900(<진입가)이라도 이익 트레일링 청산.
        val config = BacktestConfig(maxLossPct = 3.0, takeProfitPct = 99.0, trailingStopPct = 2.0, trailingArmPct = 0.0)
        val d = IntrabarExitModel.evaluate(
            bar(10490.0, 10500.0, 9900.0, 9950.0), buyPrice = 10000.0, armPeak = 10500.0,
            atHoldLimit = false, config = config, chartExitSignal = false,
        )
        assertNotNull(d)
        assertEquals("TRAILING_STOP", d!!.reason)
        assertEquals(10290.0, d.sellPrice, 1e-6) // 10500*(1-0.02)
    }

    @Test
    fun `stop loss fires when low breaches threshold though close is above`() {
        val config = BacktestConfig(maxLossPct = 3.0, takeProfitPct = 99.0, trailingStopPct = 99.0)
        val d = IntrabarExitModel.evaluate(
            bar(10000.0, 10100.0, 9600.0, 9900.0), buyPrice = 10000.0, armPeak = 10000.0,
            atHoldLimit = false, config = config, chartExitSignal = false,
        )
        assertNotNull(d)
        assertEquals("STOP_LOSS", d!!.reason)
        assertEquals(9700.0, d.sellPrice, 1e-6) // 10000*(1-0.03)
    }

    @Test
    fun `worst-case prefers stop loss when SL and TP both hit`() {
        // 저점 SL선·고점 TP선 동시 침. armPeak=진입가라 트레일링 미발동 → SL 우선.
        val config = BacktestConfig(maxLossPct = 3.0, takeProfitPct = 5.0, trailingStopPct = 99.0)
        val d = IntrabarExitModel.evaluate(
            bar(10000.0, 10600.0, 9600.0, 10000.0), buyPrice = 10000.0, armPeak = 10000.0,
            atHoldLimit = false, config = config, chartExitSignal = false,
        )
        assertEquals("STOP_LOSS", d?.reason)
    }

    @Test
    fun `same-bar new high does not phantom-trail`() {
        // armPeak=진입가(직전 고점 없음)면 이 봉 신고점(10600)으로는 트레일링 arm 안 됨 → 저점 SL침 시 SL.
        val config = BacktestConfig(maxLossPct = 3.0, takeProfitPct = 99.0, trailingStopPct = 2.0, trailingArmPct = 0.0)
        val d = IntrabarExitModel.evaluate(
            bar(10000.0, 10600.0, 9600.0, 10000.0), buyPrice = 10000.0, armPeak = 10000.0,
            atHoldLimit = false, config = config, chartExitSignal = false,
        )
        assertEquals("STOP_LOSS", d?.reason)
        assertTrue((d?.sellPrice ?: 0.0) < 10000.0, "SL 손실 체결(팬텀 트레일링 이익 아님)")
    }

    @Test
    fun `trailing respects arm threshold`() {
        // 고점 +3%(armPeak 10300)인데 arm 5% → 트레일링 미발동. 다른 게이트도 미달 → null.
        val config = BacktestConfig(maxLossPct = 99.0, takeProfitPct = 99.0, trailingStopPct = 2.0, trailingArmPct = 5.0)
        val d = IntrabarExitModel.evaluate(
            bar(10300.0, 10300.0, 10100.0, 10150.0), buyPrice = 10000.0, armPeak = 10300.0,
            atHoldLimit = false, config = config, chartExitSignal = false,
        )
        assertNull(d, "peak +3% < arm 5% must not fire trailing")
    }

    @Test
    fun `hold limit bar exits at open via TIME_EXIT ignoring intraday`() {
        // atHoldLimit → high=low=open 으로 눌러 intraday 무시. 저점이 SL선을 쳐도 open 기준이라 SL 미발동, TIME_EXIT(open).
        val config = BacktestConfig(maxLossPct = 3.0, takeProfitPct = 99.0, trailingStopPct = 99.0)
        val d = IntrabarExitModel.evaluate(
            bar(10000.0, 10100.0, 9600.0, 9900.0), buyPrice = 10000.0, armPeak = 10000.0,
            atHoldLimit = true, config = config, chartExitSignal = false,
        )
        assertEquals("TIME_EXIT", d?.reason)
        assertEquals(10000.0, d?.sellPrice) // open
    }

    @Test
    fun `chart exit fires only when enabled and signal true and not at hold limit`() {
        val on = BacktestConfig(maxLossPct = 99.0, takeProfitPct = 99.0, trailingStopPct = 99.0, chartExitEnabled = true)
        val flat = bar(10000.0, 10000.0, 10000.0, 10000.0)
        assertEquals("CHART_EXIT", IntrabarExitModel.evaluate(flat, 10000.0, 10000.0, false, on, chartExitSignal = true)?.reason)
        assertNull(IntrabarExitModel.evaluate(flat, 10000.0, 10000.0, false, on, chartExitSignal = false), "signal false → no chart exit")

        val off = on.copy(chartExitEnabled = false)
        assertNull(IntrabarExitModel.evaluate(flat, 10000.0, 10000.0, false, off, chartExitSignal = true), "disabled → no chart exit")
    }

    @Test
    fun `no exit returns null when no gate met`() {
        val config = BacktestConfig(maxLossPct = 99.0, takeProfitPct = 99.0, trailingStopPct = 99.0)
        assertNull(
            IntrabarExitModel.evaluate(
                bar(10000.0, 10050.0, 9990.0, 10010.0), buyPrice = 10000.0, armPeak = 10000.0,
                atHoldLimit = false, config = config, chartExitSignal = false,
            ),
        )
    }

    @Test
    fun `updatedPeak tracks bar high but uses open at hold limit`() {
        val b = bar(10000.0, 10600.0, 9600.0, 10000.0)
        assertEquals(10600.0, IntrabarExitModel.updatedPeak(10500.0, b, atHoldLimit = false))
        assertEquals(10500.0, IntrabarExitModel.updatedPeak(10500.0, b, atHoldLimit = true)) // open(10000)<prev, high 무시
    }
}
