package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.Indicators
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stage B 리서치 노브(레짐필터 기간·ATR 가변 손절익절·부분 익절)의 계약.
 *
 * 이 노브들은 **기본값에서 기존 동작과 완전히 같아야** 하고(골든·parity 가드), 켰을 때는 D1 백테와 M1 replay 가
 * 조용히 다른 정책으로 갈라지지 않아야 한다(M1 은 명시적으로 거부한다).
 */
class BacktestResearchKnobTest {

    // ------------------------------------------------------------- 기본값 가드

    @Test
    fun `research knobs are off by default`() {
        val config = BacktestConfig()
        assertEquals(BacktestEngine.MIN_CANDLES, config.marketFilterMaPeriod, "필터 기간 기본 = 현행 MA50")
        assertNull(config.atrStopMultiplier)
        assertNull(config.atrTakeProfitR)
        assertNull(config.partialTakeProfitPct)
        assertNull(config.partialTakeProfitFraction)
    }

    @Test
    fun `config rejects combinations that would silently degrade into another policy`() {
        // 엔진 window 가 50봉 고정이라 51 이상은 min() 으로 조용히 절삭된다 — "기간을 바꿔도 같다"는 거짓 결론 방지.
        assertThrows(IllegalArgumentException::class.java) { BacktestConfig(marketFilterMaPeriod = 51) }
        assertThrows(IllegalArgumentException::class.java) { BacktestConfig(marketFilterMaPeriod = 0) }
        assertThrows(IllegalArgumentException::class.java) { BacktestConfig(atrTakeProfitR = 2.0) }
        assertThrows(IllegalArgumentException::class.java) { BacktestConfig(atrStopMultiplier = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { BacktestConfig(partialTakeProfitPct = 2.0) }
        assertThrows(IllegalArgumentException::class.java) { BacktestConfig(partialTakeProfitFraction = 0.5) }
        assertThrows(IllegalArgumentException::class.java) {
            BacktestConfig(partialTakeProfitPct = 2.0, partialTakeProfitFraction = 1.0)
        }
    }

    @Test
    fun `M1 replay refuses configs whose exit policy it cannot model`() {
        val bars = listOf(bar("2026-01-01T00:00:00", 100.0, 101.0, 99.0, 100.0))
        val entry = LocalDateTime.parse("2026-01-01T00:00:00")
        for (config in listOf(
            BacktestConfig(atrStopMultiplier = 2.0),
            BacktestConfig(partialTakeProfitPct = 2.0, partialTakeProfitFraction = 0.5),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                M1ReplayEngine.replayExit(100.0, entry, entry.plusDays(1), bars, config)
            }
        }
    }

    // ------------------------------------------------------------------ ATR

    @Test
    fun `ATR is the simple mean of true range and needs one extra bar`() {
        // 최신순 3봉. TR = max(high−low, |high−prevClose|, |low−prevClose|)
        val candles = listOf(
            bar("d3", open = 10.0, high = 12.0, low = 9.0, close = 11.0), // prevClose 8 → TR = max(3, 4, 1) = 4
            bar("d2", open = 8.0, high = 9.0, low = 7.0, close = 8.0), //  prevClose 5 → TR = max(2, 4, 2) = 4
            bar("d1", open = 5.0, high = 6.0, low = 4.0, close = 5.0),
        )
        assertEquals(4.0, Indicators.calculateAtr(candles, period = 2), 1e-9)
        assertEquals(0.0, Indicators.calculateAtr(candles, period = 3), 1e-9, "period+1 봉이 없으면 0")
        assertEquals(0.0, Indicators.calculateAtr(emptyList(), period = 14), 1e-9)
    }

    @Test
    fun `ATR exit levels replace the percent thresholds and R is measured in stop widths`() {
        val levels = IntrabarExitModel.exitLevels(
            buyPrice = 100.0,
            config = BacktestConfig(atrStopMultiplier = 2.0, atrTakeProfitR = 3.0),
            entryAtr = 1.5,
        )
        assertEquals(97.0, levels.atrStopPrice!!, 1e-9, "손절 = 진입가 − 2×ATR")
        assertEquals(109.0, levels.atrTakeProfitPrice!!, 1e-9, "익절 = 진입가 + 3×손절폭")
    }

    @Test
    fun `ATR levels refuse a zero ATR instead of putting the stop on the entry price`() {
        assertThrows(IllegalArgumentException::class.java) {
            IntrabarExitModel.exitLevels(100.0, BacktestConfig(atrStopMultiplier = 2.0), entryAtr = 0.0)
        }
    }

    @Test
    fun `percent mode leaves the levels empty so the existing comparisons stay untouched`() {
        val levels = IntrabarExitModel.exitLevels(100.0, BacktestConfig())
        assertNull(levels.atrStopPrice)
        assertNull(levels.atrTakeProfitPrice)
        assertNull(levels.partialTakeProfitPrice)
    }

    // --------------------------------------------------------------- 부분 익절

    @Test
    fun `a full stop-loss on the same bar wins over the partial take-profit`() {
        val config = BacktestConfig(partialTakeProfitPct = 2.0, partialTakeProfitFraction = 0.5)
        val levels = IntrabarExitModel.exitLevels(100.0, config)
        // high 는 부분 익절선(102)을, low 는 손절선(95)을 함께 건드린 봉.
        val bar = bar("d", open = 100.0, high = 103.0, low = 94.0, close = 96.0)
        val decision = IntrabarExitModel.evaluate(bar, 100.0, 100.0, false, config, false, levels)
        assertEquals("STOP_LOSS", decision?.reason, "순서 불명이면 손절 우선 — 부분 익절을 먼저 인정하면 pnl 이 부풀려진다")
        assertTrue(IntrabarExitModel.partialTakeProfitFires(bar, false, levels), "가격만 보면 부분 익절선에는 닿았다")
    }

    @Test
    fun `partial take-profit blends both legs and marks the trade`() = runTest {
        // 진입 후 +3% 까지 올랐다가(부분 익절선 2% 통과) 익일 시가에 보유상한 청산되는 시계열.
        val candles = risingThenFlat()
        val engine = BacktestEngine(listOf(AlwaysBuy()), TradingProperties())
        val config = BacktestConfig(
            takeProfitPct = 50.0, maxLossPct = 50.0, trailingStopPct = 50.0,
            partialTakeProfitPct = 2.0, partialTakeProfitFraction = 0.5,
        )
        val result = engine.run(AlwaysBuy.NAME, candles, "SYN", config)!!
        val partial = result.trades.first { it.partialFraction != null }
        assertEquals(0.5, partial.partialFraction)

        val buy = partial.buyPrice
        val fee = config.feeRate * 2 * 100
        val leg1 = ((buy * 1.02 - buy) / buy) * 100.0 - fee
        val leg2 = ((partial.sellPrice - buy) / buy) * 100.0 - fee
        assertEquals(0.5 * leg1 + 0.5 * leg2, partial.pnlPercent, 1e-9, "가중 합성")
    }

    @Test
    fun `without the partial knob every trade keeps the price-to-pnl invariant`() = runTest {
        val engine = BacktestEngine(listOf(CombinedStrategy()), TradingProperties())
        val config = BacktestConfig(reentryMode = ReentryMode.LIVE_SAME_BAR)
        val result = engine.run("combined", YearlyFixtures.load("KRW-BTC"), "KRW-BTC", config)!!
        assertTrue(result.trades.isNotEmpty())
        val fee = config.feeRate * 2 * 100
        for (t in result.trades) {
            assertNull(t.partialFraction, "부분 익절을 안 켰으면 합성 레코드가 없어야 한다")
            val expected = ((t.sellPrice - t.buyPrice) / t.buyPrice) * 100.0 - fee
            assertEquals(expected, t.pnlPercent, 1e-9, "pnl ↔ 가격 불변식")
        }
    }

    // ----------------------------------------------------------- 레짐 필터 기간

    @Test
    fun `a shorter market filter period changes which entries survive`() = runTest {
        val engine = BacktestEngine(listOf(CombinedStrategy()), TradingProperties())
        val candles = YearlyFixtures.load("KRW-SOL")
        val ma50 = engine.run("combined", candles, "KRW-SOL", BacktestConfig(useMarketFilter = true))!!
        val ma10 = engine.run("combined", candles, "KRW-SOL", BacktestConfig(useMarketFilter = true, marketFilterMaPeriod = 10))!!
        assertNotNull(ma50)
        assertFalse(ma50.trades == ma10.trades, "기간이 실제로 필터를 바꾼다(조용히 절삭되면 같아진다)")
    }

    private fun bar(id: String, open: Double, high: Double, low: Double, close: Double) = Candle(
        market = "SYN",
        candleDateTimeUtc = if (id.startsWith("2")) id else "2026-01-01T00:00:00",
        candleDateTimeKst = id,
        openingPrice = open, highPrice = high, lowPrice = low, tradePrice = close,
        candleAccTradeVolume = 100.0, candleAccTradePrice = 100.0 * close,
    )

    /** 워밍업 50봉 + 상승 구간. 최신순으로 돌려준다. */
    private fun risingThenFlat(): List<Candle> {
        val out = ArrayList<Candle>()
        var price = 100.0
        for (i in 0 until 60) {
            val open = price
            val close = if (i < 50) price else price * 1.005
            val high = if (i < 50) open * 1.001 else open * 1.03
            out += bar("bar-%03d".format(i), open, high, minOf(open, close) * 0.999, close)
            price = close
        }
        return out.reversed()
    }

    /** 워밍업만 넘기면 언제나 매수 — 부분 익절 경로를 시계열로 재현하기 위한 최소 전략. */
    private class AlwaysBuy : com.trading.common.strategy.TradingStrategy {
        override val name = NAME
        override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = true
        override suspend fun shouldSell(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = false

        companion object {
            const val NAME = "always_buy"
        }
    }
}
