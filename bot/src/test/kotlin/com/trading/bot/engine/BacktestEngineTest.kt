package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.TradingStrategy
import com.trading.common.strategy.VolatilityBreakout
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BacktestEngineTest {

    private val strategy = VolatilityBreakout()
    private val tradingProperties = TradingProperties()
    private val engine = BacktestEngine(listOf(strategy), tradingProperties)

    @Test
    fun `returns null for unknown strategy`() = runTest {
        val candles = buildCandles(100)
        val result = engine.run("nonexistent", candles, "KRW-BTC")
        assertNull(result)
    }

    @Test
    fun `returns null for insufficient candles`() = runTest {
        val candles = buildCandles(30)
        val result = engine.run("volatility_breakout", candles, "KRW-BTC")
        assertNull(result)
    }

    @Test
    fun `runs backtest with sufficient data`() = runTest {
        val candles = buildTrendCandles(120)
        val result = engine.run("volatility_breakout", candles, "KRW-BTC")

        assertNotNull(result)
        result!!
        assertEquals("volatility_breakout", result.strategyName)
        assertEquals("KRW-BTC", result.ticker)
        assertTrue(result.totalTrades >= 0)
        assertEquals(result.winTrades + result.lossTrades, result.totalTrades)
        assertTrue(result.winRate in 0.0..100.0)
        assertTrue(result.maxDrawdownPct >= 0.0)
    }

    @Test
    fun `compareAll runs all strategies`() = runTest {
        val anotherStrategy = object : TradingStrategy {
            override val name = "always_buy"
            override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = true
        }
        val multiEngine = BacktestEngine(listOf(strategy, anotherStrategy), tradingProperties)
        val candles = buildTrendCandles(120)

        val results = multiEngine.compareAll(candles, "KRW-BTC")

        assertEquals(2, results.size)
        assertTrue(results.any { it.strategyName == "volatility_breakout" })
        assertTrue(results.any { it.strategyName == "always_buy" })
    }

    @Test
    fun `backtest respects stop loss`() = runTest {
        // trailingStopPct=99 로 트레일링을 격리해 STOP_LOSS 만 남긴다 —
        // 그렇지 않으면 SL 거래 0건이어도(트레일링이 선점) 단언이 공허참으로 통과한다.
        val config = BacktestConfig(maxLossPct = 3.0, takeProfitPct = 50.0, trailingStopPct = 99.0, maxHoldDays = 100)
        val candles = buildCrashCandles(120)

        val result = engine.run("volatility_breakout", candles, "KRW-BTC", config)

        assertNotNull(result)
        val stopLossTrades = result!!.trades.filter { it.reason == "STOP_LOSS" }
        assertTrue(stopLossTrades.isNotEmpty(), "급락 시나리오는 STOP_LOSS 거래를 내야 한다")
        stopLossTrades.forEach { trade ->
            assertTrue(trade.pnlPercent <= 0, "Stop loss trade should have negative PnL")
        }
    }

    @Test
    fun `buy and hold calculation is correct`() = runTest {
        val candles = buildTrendCandles(120)
        val result = engine.run("volatility_breakout", candles, "KRW-BTC")

        assertNotNull(result)
        result!!
        // Buy & hold should reflect the overall trend
        val chronological = candles.reversed()
        val firstPrice = chronological[50].tradePrice
        val lastPrice = chronological.last().tradePrice
        val expectedBuyAndHold = ((lastPrice - firstPrice) / firstPrice) * 100.0
        assertEquals(expectedBuyAndHold, result.buyAndHoldPct, 0.01)
    }

    private fun buildCandles(count: Int): List<Candle> {
        // Flat prices - unlikely to trigger any strategy
        return (0 until count).map {
            Candle(
                market = "KRW-BTC",
                tradePrice = 10000.0,
                openingPrice = 10000.0,
                highPrice = 10000.0,
                lowPrice = 10000.0,
                candleAccTradeVolume = 100.0,
            )
        }
    }

    private fun buildTrendCandles(count: Int): List<Candle> {
        // Uptrend with some volatility to trigger breakout signals
        return (0 until count).map { i ->
            val base = 10000.0 + (count - i) * 50.0
            val volatility = if (i % 3 == 0) 200.0 else -100.0
            Candle(
                market = "KRW-BTC",
                tradePrice = base + volatility,
                openingPrice = base,
                highPrice = base + 300.0,
                lowPrice = base - 150.0,
                candleAccTradeVolume = 100.0 + i * 5.0,
            )
        }
    }

    private fun buildCrashCandles(count: Int): List<Candle> {
        // Initial breakout then sharp decline
        return (0 until count).map { i ->
            val reversedI = count - 1 - i
            val price = when {
                reversedI < 55 -> 10000.0 + reversedI * 30.0  // gentle rise to set up
                reversedI < 65 -> 11650.0 + (reversedI - 55) * 200.0  // breakout
                else -> 13650.0 - (reversedI - 65) * 300.0  // crash
            }
            Candle(
                market = "KRW-BTC",
                tradePrice = price.coerceAtLeast(5000.0),
                openingPrice = price - 50.0,
                highPrice = price + 100.0,
                lowPrice = price - 200.0,
                candleAccTradeVolume = 200.0,
            )
        }
    }

    // 손익% 안전망을 넓게 둬 chartExit(데드크로스)만 트리거되게.
    private fun wideStopConfig(chartExit: Boolean) = BacktestConfig(
        maxLossPct = 99.0,
        takeProfitPct = 99.0,
        trailingStopPct = 99.0,
        maxHoldDays = 999,
        chartExitEnabled = chartExit,
    )

    private fun alwaysBuyStrategy() = object : TradingStrategy {
        override val name = "always_buy"
        override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = true
        // shouldSell 은 default(데드크로스 5/20)
    }

    // chronological(과거→최신): 전반 완만 상승(골든) → 후반 급락 → 5/20 데드크로스 교차.
    private fun buildDeadCrossScenario(count: Int): List<Candle> {
        val riseEnd = count - 20
        return (0 until count).map { i ->
            val chronoIdx = count - 1 - i // 0=과거, count-1=최신 (입력은 최신순)
            val price = if (chronoIdx < riseEnd) {
                10000.0 + chronoIdx * 50.0
            } else {
                val peak = 10000.0 + (riseEnd - 1) * 50.0
                peak - (chronoIdx - riseEnd + 1) * 500.0
            }
            Candle(
                market = "KRW-BTC",
                tradePrice = price.coerceAtLeast(1000.0),
                openingPrice = price,
                highPrice = price + 100.0,
                lowPrice = price - 100.0,
                candleAccTradeVolume = 100.0,
            )
        }
    }

    // --- #27 정합: 디폴트 parity / TIME_EXIT / trailing arm ---

    @Test
    fun `config defaults match live trading defaults`() {
        // 백테 디폴트 ≠ 라이브 디폴트가 #27 부정합의 근본 원인 — drift 를 CI 로 가드.
        val live = TradingProperties()
        val bt = BacktestConfig()
        assertEquals(live.kValue, bt.kValue) // #31 로 신호에 반영되므로 parity 대상
        assertEquals(live.takeProfitPct, bt.takeProfitPct)
        assertEquals(live.maxLossPct, bt.maxLossPct)
        assertEquals(live.trailingStopPct, bt.trailingStopPct)
        assertEquals(live.trailingArmPct, bt.trailingArmPct)
        assertEquals(live.maxHoldDays, bt.maxHoldDays)
        assertEquals(live.chartExitEnabled, bt.chartExitEnabled)
        assertEquals(live.roundTripFeeRate, bt.feeRate * 2, 1e-12) // 편도 vs 왕복 표현 차이
        assertFalse(bt.useMarketFilter) // 라이브 매수 경로에 MA50 필터 없음
        // 라이브와 **다르게** 두는 유일한 항목 — 라이브는 09:00 리셋 매도 직후 재매수가 가능한데(0공백),
        // 기본값을 그리로 바꾸면 M1ReplayBiasTest·ParameterSweepTest·/backtest 호출자의 모집단이 조용히
        // 달라진다. 전환은 라이브 변경을 결정할 때 함께 판단한다(#128). 그때까지 이 불일치를 CI 가 들고 있는다.
        assertEquals(ReentryMode.LEGACY_NEXT_BAR, bt.reentryMode)
        assertEquals(0, bt.reentryCooldownBars)
    }

    @Test
    fun `backtest exits by TIME_EXIT after maxHoldDays 1`() = runTest {
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val config = BacktestConfig(
            maxLossPct = 99.0, takeProfitPct = 99.0, trailingStopPct = 99.0,
            maxHoldDays = 1, useMarketFilter = false,
        )
        val result = ce.run("always_buy", buildCandles(120), "KRW-BTC", config)

        assertNotNull(result)
        assertTrue(result!!.totalTrades > 0, "scenario must produce trades")
        val timeExits = result.trades.filter { it.reason == "TIME_EXIT" }
        assertTrue(timeExits.isNotEmpty(), "expected TIME_EXIT trades")
        timeExits.forEach { assertEquals(1, it.holdDays) }
    }

    // chronological: 50봉 워밍업(10000) → 진입 → +3% 고점 → 2.5% drop(pnl +0.4%) → 횡보.
    private fun buildArmScenario(count: Int): List<Candle> {
        return (0 until count).map { i ->
            val c = count - 1 - i // chronological index (0=과거)
            val (open, close) = when {
                c <= 50 -> 10000.0 to 10000.0
                c == 51 -> 10000.0 to 10300.0 // fill 봉: +3% 고점 형성
                c == 52 -> 10300.0 to 10040.0 // drop 2.52% from peak, pnl +0.4%
                else -> 10040.0 to 10040.0
            }
            Candle(
                market = "KRW-BTC",
                tradePrice = close,
                openingPrice = open,
                highPrice = maxOf(open, close),
                lowPrice = minOf(open, close),
                candleAccTradeVolume = 100.0,
            )
        }
    }

    // #33 intrabar 청산 검증용: 50봉 워밍업 → c=51 진입(fill)봉 → c=52 시나리오봉 → 종가 횡보.
    // fill/bar 는 [open, high, low, close] 순. 종가 모델과 intrabar 모델의 청산 차이를 드러내려 high≠low 로 지정.
    private fun buildExitScenario(fill: List<Double>, bar: List<Double>, count: Int = 120): List<Candle> {
        return (0 until count).map { i ->
            val c = count - 1 - i
            val ohlc = when {
                c <= 50 -> listOf(10000.0, 10000.0, 10000.0, 10000.0)
                c == 51 -> fill
                c == 52 -> bar
                else -> bar[3].let { listOf(it, it, it, it) }
            }
            Candle(
                market = "KRW-BTC",
                openingPrice = ohlc[0],
                highPrice = ohlc[1],
                lowPrice = ohlc[2],
                tradePrice = ohlc[3],
                candleAccTradeVolume = 100.0,
            )
        }
    }

    @Test
    fun `backtest trailing stop fires with arm zero`() = runTest {
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val config = BacktestConfig(
            maxLossPct = 99.0, takeProfitPct = 99.0, trailingStopPct = 2.0, trailingArmPct = 0.0,
            maxHoldDays = 999, useMarketFilter = false,
        )
        val result = ce.run("always_buy", buildArmScenario(120), "KRW-BTC", config)

        assertNotNull(result)
        assertTrue(result!!.trades.any { it.reason == "TRAILING_STOP" }, "arm=0 must fire trailing stop")
    }

    @Test
    fun `backtest trailing stop respects arm threshold`() = runTest {
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val config = BacktestConfig(
            maxLossPct = 99.0, takeProfitPct = 99.0, trailingStopPct = 2.0, trailingArmPct = 5.0,
            maxHoldDays = 999, useMarketFilter = false,
        )
        val result = ce.run("always_buy", buildArmScenario(120), "KRW-BTC", config)

        assertNotNull(result)
        assertTrue(result!!.totalTrades > 0, "scenario must produce trades")
        assertTrue(result.trades.none { it.reason == "TRAILING_STOP" }, "peak +3% < arm 5% must not fire")
    }

    @Test
    fun `backtest triggers CHART_EXIT when enabled and dead cross occurs`() = runTest {
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val result = ce.run("always_buy", buildDeadCrossScenario(120), "KRW-BTC", wideStopConfig(chartExit = true))

        assertNotNull(result)
        assertTrue(result!!.trades.any { it.reason == "CHART_EXIT" }, "expected a CHART_EXIT trade")
    }

    @Test
    fun `backtest has no CHART_EXIT when disabled`() = runTest {
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val result = ce.run("always_buy", buildDeadCrossScenario(120), "KRW-BTC", wideStopConfig(chartExit = false))

        assertNotNull(result)
        // trade 가 0건이면 none 단언이 공허참이 되므로 진입이 실제로 일어났음을 함께 보장.
        assertTrue(result!!.totalTrades > 0, "scenario must produce trades")
        assertTrue(result.trades.none { it.reason == "CHART_EXIT" }, "CHART_EXIT must not occur when disabled")
    }

    // --- #31: 진입 신호 파라미터(kValue)가 config 로 백테에 반영되는지 ---

    @Test
    fun `backtest reflects config kValue in entry signal`() = runTest {
        // 돌파 목표가 = open + (전일 range) * k. k 가 낮으면 목표가가 낮아 진입이 쉬워 거래가 많고,
        // 높으면 진입이 어려워 거래가 적다. #31 결함(신호가 live tradingProperties 의 0.5 고정)이면 둘이 동일.
        val candles = buildTrendCandles(120)
        // highK 는 API 상한(StrategyController 의 kValue in 0.0..2.0)의 경계값 — 실제 도달 가능한 최대.
        val lowK = engine.run("volatility_breakout", candles, "KRW-BTC", BacktestConfig(kValue = 0.1))
        val highK = engine.run("volatility_breakout", candles, "KRW-BTC", BacktestConfig(kValue = 2.0))

        assertNotNull(lowK)
        assertNotNull(highK)
        assertTrue(lowK!!.totalTrades > 0, "낮은 kValue 시나리오는 진입이 발생해야 유효한 대조")
        assertTrue(
            lowK.totalTrades > highK!!.totalTrades,
            "config.kValue 가 신호에 반영되면 낮은 k 의 거래수가 높은 k 보다 많아야 한다 (#31)",
        )
    }

    @Test
    fun `backtest reflects config kValue for combined strategy`() = runTest {
        // 라이브 기본 전략(combined, TradingProperties.strategy 기본값)도 진입에 config.kValue 를 읽으므로
        // 실사용 경로까지 #31 수정을 가드한다. combined 는 kValue 돌파에 더해 MA 상승/RSI 게이트를 AND 한다.
        val combinedEngine = BacktestEngine(listOf(CombinedStrategy()), tradingProperties)
        val candles = buildTrendCandles(120)
        val lowK = combinedEngine.run("combined", candles, "KRW-BTC", BacktestConfig(kValue = 0.1))
        val highK = combinedEngine.run("combined", candles, "KRW-BTC", BacktestConfig(kValue = 2.0))

        assertNotNull(lowK)
        assertNotNull(highK)
        assertTrue(lowK!!.totalTrades > 0, "combined 낮은 kValue 시나리오는 진입이 발생해야 유효한 대조")
        assertTrue(
            lowK.totalTrades > highK!!.totalTrades,
            "combined 전략도 config.kValue 가 신호에 반영되어야 한다 (#31)",
        )
    }

    // --- #33: intrabar 보수 청산 모델 ---

    @Test
    fun `intrabar trailing fires on low penetration below entry`() = runTest {
        // 진입봉 장중 고점 +5%(peak), 다음봉 저점이 진입가 아래(-1%)지만 트레일링선(peak*0.98=+2.9%)은 진입가 위.
        // 종가 모델은 장중 고점을 못 봐 이익 트레일링을 통째로 놓치지만, intrabar 모델은 트레일링선에서 이익 청산.
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val config = BacktestConfig(
            maxLossPct = 3.0, takeProfitPct = 99.0, trailingStopPct = 2.0, trailingArmPct = 0.0,
            maxHoldDays = 999, useMarketFilter = false,
        )
        val candles = buildExitScenario(
            fill = listOf(10000.0, 10500.0, 10450.0, 10490.0), // 진입가 10000, 장중 peak 10500
            bar = listOf(10490.0, 10500.0, 9900.0, 9950.0),     // 저점 9900 < 진입가, 종가 9950
        )
        val result = ce.run("always_buy", candles, "KRW-BTC", config)

        assertNotNull(result)
        val trailing = result!!.trades.filter { it.reason == "TRAILING_STOP" }
        assertTrue(trailing.isNotEmpty(), "저점-침투 봉에서 트레일링이 발동해야 한다 (intrabar)")
        assertTrue(trailing.any { it.sellPrice > 10000.0 }, "트레일링은 진입가 위 이익 청산(트레일링선≈10290)이어야 한다")
    }

    @Test
    fun `intrabar stop loss fires when low breaches threshold though close is above`() = runTest {
        // 종가(-1%)로는 SL 미달이나 장중 저점(-4%)이 SL선(-3%)을 침 → intrabar 모델만 SL 발동.
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val config = BacktestConfig(
            maxLossPct = 3.0, takeProfitPct = 99.0, trailingStopPct = 99.0,
            maxHoldDays = 999, useMarketFilter = false,
        )
        val candles = buildExitScenario(
            fill = listOf(10000.0, 10000.0, 10000.0, 10000.0),
            bar = listOf(10000.0, 10100.0, 9600.0, 9900.0), // 저점 -4%(SL선 침), 종가 -1%(SL 미달)
        )
        val result = ce.run("always_buy", candles, "KRW-BTC", config)

        assertNotNull(result)
        val sl = result!!.trades.filter { it.reason == "STOP_LOSS" }
        assertTrue(sl.isNotEmpty(), "장중 저점이 SL선을 치면 종가가 위여도 SL 발동해야 한다 (intrabar)")
        assertTrue(sl.all { it.sellPrice in 9600.0..9800.0 }, "SL 체결가는 손절선(≈9700) 근처여야 한다")
    }

    @Test
    fun `intrabar worst-case prefers stop loss when SL and TP both hit in one bar`() = runTest {
        // 한 봉에서 저점이 SL선(-3%), 고점이 TP선(+5%)을 동시에 침 → 봉 내 도달 순서 불명이라 SL 우선(worst-case).
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val config = BacktestConfig(
            maxLossPct = 3.0, takeProfitPct = 5.0, trailingStopPct = 99.0,
            maxHoldDays = 999, useMarketFilter = false,
        )
        val candles = buildExitScenario(
            fill = listOf(10000.0, 10000.0, 10000.0, 10000.0),
            bar = listOf(10000.0, 10600.0, 9600.0, 10000.0), // 고점 +6%(TP선 10500 침), 저점 -4%(SL선 9700 침)
        )
        val result = ce.run("always_buy", candles, "KRW-BTC", config)

        assertNotNull(result)
        val first = result!!.trades.minByOrNull { it.sellIndex }
        assertNotNull(first)
        assertEquals("STOP_LOSS", first!!.reason, "SL·TP 동시 충족 봉은 worst-case 로 SL 우선이어야 한다")
    }

    @Test
    fun `intrabar same-bar new high with low breach must not phantom-trail`() = runTest {
        // 한 봉이 신고점(high, 직전 peak 갱신)과 SL선 이하 저점(low)을 동시에 찍을 때 — 봉 내 도달 순서 불명.
        // 그 봉의 high 로 트레일링을 arm 하면 SL 손실이 팬텀 트레일링 '이익'으로 오기록된다(낙관 편향, #33 역행).
        // 트레일링 arm 은 직전까지 형성된 peak 로만 해야 하므로, 직전 peak 이 진입가면 SL 이 발동해야 한다.
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val config = BacktestConfig(
            maxLossPct = 3.0, takeProfitPct = 99.0, trailingStopPct = 2.0, trailingArmPct = 0.0,
            maxHoldDays = 999, useMarketFilter = false,
        )
        val candles = buildExitScenario(
            fill = listOf(10000.0, 10000.0, 10000.0, 10000.0), // 진입봉 flat → 직전 peak = 진입가 10000
            bar = listOf(10000.0, 10600.0, 9600.0, 10000.0),    // 같은 봉 신고점 +6% & 저점 -4%(SL선 -3% 침)
        )
        val result = ce.run("always_buy", candles, "KRW-BTC", config)

        assertNotNull(result)
        val first = result!!.trades.minByOrNull { it.sellIndex }
        assertNotNull(first)
        assertEquals("STOP_LOSS", first!!.reason, "같은 봉 신고점으로는 트레일링을 arm 하지 않아 SL 이 발동해야 한다")
        assertTrue(first.sellPrice < 10000.0, "SL 손실 체결이어야 한다(팬텀 트레일링 이익 아님)")
    }

    @Test
    fun `END trade updates drawdown like a normal exit`() = runTest {
        // 시뮬레이션 종료 시 미청산 포지션은 "END" 로 강제청산된다. 그 거래가 손실이면 낙폭도 커져야 하는데,
        // closeOpenPosition 이 peak/maxDrawdown 을 갱신하지 않으면 MDD 가 과소평가된다(processExit 는 갱신한다).
        val alwaysBuy = object : TradingStrategy {
            override val name = "always_buy"
            override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = true
            override suspend fun shouldSell(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = false
        }
        // 손절·익절·트레일링·보유상한을 모두 비활성에 가깝게 두어 END 로만 끝나게 한다.
        val noExit = BacktestConfig(
            takeProfitPct = 1_000.0, maxLossPct = 1_000.0,
            trailingStopPct = 1_000.0, trailingArmPct = 1_000.0,
            maxHoldDays = 10_000,
        )
        val engine = BacktestEngine(listOf(alwaysBuy), TradingProperties())

        // 마지막 구간이 크게 하락 → END 청산이 손실로 끝난다.
        val candles = buildCrashCandles(120)
        val result = engine.run("always_buy", candles, "KRW-BTC", noExit)

        assertNotNull(result)
        result!!
        val endTrades = result.trades.filter { it.reason == "END" }
        assertTrue(endTrades.isNotEmpty(), "END 거래가 없어 검증이 성립하지 않는다")
        assertTrue(endTrades.any { it.pnlPercent < 0 }, "END 가 손실이 아니어서 낙폭 검증이 무의미하다: ${endTrades.map { it.pnlPercent }}")
        assertTrue(result.maxDrawdownPct > 0.0, "손실로 끝났는데 maxDrawdownPct 가 0 이다")
    }


    @Test
    fun `returns null at exactly the minimum candle count instead of throwing`() = runTest {
        // 가드는 size < 50 만 막는데, 정확히 50봉이면 시뮬레이션 루프가 한 번도 돌지 않은 채
        // buildResult 가 chronological[50] 을 읽어 IndexOutOfBounds 가 난다. 실질 최소 입력은 51봉이다.
        val candles = buildTrendCandles(50)

        val result = engine.run("volatility_breakout", candles, "KRW-BTC")

        assertNull(result, "신호를 낼 수 없는 입력은 예외가 아니라 null 이어야 한다")
    }

    @Test
    fun `runs with one candle above the minimum`() = runTest {
        val result = engine.run("volatility_breakout", buildTrendCandles(51), "KRW-BTC")
        assertNotNull(result, "51봉은 시뮬레이션이 가능해야 한다")
    }

}
