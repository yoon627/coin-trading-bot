package com.trading.bot.engine

import com.trading.bot.engine.BacktestFixtures.Regime
import com.trading.common.config.AccumulateProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.LadderParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccumulateBacktestTest {

    private val backtest = AccumulateBacktest()
    private val params = LadderParams(budgetKrw = 100_000.0, maxRungs = 5, stepDownPct = 3.0, stepUpPct = 3.0)
    private val noFee = AccumulateBacktestConfig(params = params, feeRate = 0.0)

    private fun bar(open: Double, high: Double, low: Double, close: Double) =
        Candle(openingPrice = open, highPrice = high, lowPrice = low, tradePrice = close)

    /** 시간순으로 적고 최신순으로 넘긴다(fixture 규약). */
    private fun newestFirst(vararg chronological: Candle) = chronological.toList().reversed()

    @Test
    fun `backtest defaults match live accumulate defaults`() {
        assertEquals(AccumulateProperties().ladderParams(), AccumulateBacktestConfig().params)
    }

    @Test
    fun `enters on a pullback from the previous bars peak, not the same bar high`() {
        // 봉1: 고점 100 관측(진입 없음 — flatPeak 는 봉 뒤에 반영). 봉2: low 97 ≤ 100×0.97 → 1단.
        val result = backtest.run(newestFirst(bar(90.0, 100.0, 90.0, 100.0), bar(100.0, 100.0, 97.0, 98.0)), "T", noFee)
        assertEquals(1, result.buys)
        assertEquals(1, result.finalRungs)
        assertEquals(20_000.0, result.maxInvestedKrw)
    }

    @Test
    fun `adds rungs on each stepDown and sells one rung on stepUp above average`() {
        val result = backtest.run(
            newestFirst(
                bar(100.0, 100.0, 100.0, 100.0), // flatPeak=100
                bar(100.0, 100.0, 97.0, 97.0),   // 1단 @97
                bar(97.0, 97.0, 94.0, 94.0),     // 2단 @94.09 (97×0.97)
                bar(94.0, 94.0, 94.0, 94.0),     // 대기
                bar(94.0, 99.0, 94.0, 99.0),     // 평단 ≈95.5 → ×1.03 ≈98.4 ≤ high 99 → 1단 매도
            ),
            "T", noFee,
        )
        assertEquals(2, result.buys)
        assertEquals(1, result.sells)
        assertEquals(1, result.finalRungs)
        assertTrue(result.realizedPnlKrw > 0.0, "sold above average: ${result.realizedPnlKrw}")
    }

    @Test
    fun `gap through the trigger fills at the open`() {
        // flatPeak=100, 다음 봉이 90 에 열림 → 트리거 97 이 아니라 90 에 체결.
        val result = backtest.run(newestFirst(bar(100.0, 100.0, 100.0, 100.0), bar(90.0, 91.0, 89.0, 90.0)), "T", noFee)
        assertEquals(1, result.buys)
        // 20,000 원을 90 에 샀으니 종가 90 기준 미실현 0, equity = 예산.
        assertEquals(0.0, result.netReturnPct, 1e-9)
    }

    @Test
    fun `one action per bar by default, one direction per bar when more are allowed`() {
        val candles = newestFirst(
            bar(100.0, 100.0, 100.0, 100.0),
            bar(100.0, 100.0, 80.0, 80.0), // 97·94.09·91.27… 여러 단이 한 봉 안에 닿는다
        )
        assertEquals(1, backtest.run(candles, "T", noFee).buys)
        val multi = backtest.run(candles, "T", noFee.copy(maxActionsPerBar = 10))
        assertEquals(5, multi.buys)
        assertEquals(0, multi.sells) // 같은 봉의 high 로 되파는 왕복은 look-ahead — 금지
    }

    @Test
    fun `budget caps investment and drawdown is measured on equity`() {
        val fall = generateSequence(100.0) { it * 0.95 }.take(30).map { bar(it, it, it * 0.95, it * 0.95) }.toList()
        val result = backtest.run(fall.reversed(), "T", noFee)
        assertEquals(5, result.finalRungs)
        assertTrue(result.maxInvestedKrw <= params.budgetKrw + 1.0)
        assertTrue(result.maxDrawdownPct > 0.0 && result.maxDrawdownPct < 100.0)
        assertTrue(result.avgInvestedFraction in 0.0..1.0)
    }

    /**
     * 사전 등록 채택 규칙(plan Decision 2) — 데이터를 보기 전에 고정했다. 격자는 민감도 관찰용이고,
     * 판정 대상은 **후보 기본값 5/3/3 하나**다. 격자 최적값을 기본값으로 올리지 않는다(과적합).
     */
    @Test
    fun `grid sweep over major fixtures with pre-registered adoption rule`() {
        val majors = setOf("KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL")
        val fixtures = Regime.entries.flatMap { regime ->
            BacktestFixtures.markets(regime).filter { it in majors }.map { market -> Triple(regime, market, BacktestFixtures.load(regime, market)) }
        }
        assertEquals(7, fixtures.size)

        val candidate = AccumulateProperties().ladderParams()
        val grid = listOf(4, 5, 8).flatMap { rungs -> listOf(2.0, 3.0, 5.0).flatMap { down -> listOf(2.0, 3.0, 5.0).map { up -> LadderParams(100_000.0, rungs, down, up) } } }
        val sb = StringBuilder()
        val candidateCells = listOf(1, 10).map { actionsPerBar ->
            sb.appendLine("## maxActionsPerBar=$actionsPerBar")
            sb.appendLine("| rungs | down | up | bear median % | bear B&H median % | bull median % | bull B&H median % | worst MDD % | avg exposure | pass |")
            val cells = grid.map { p ->
                val results = fixtures.map { (regime, market, candles) ->
                    regime to backtest.run(candles, market, AccumulateBacktestConfig(p, maxActionsPerBar = actionsPerBar))
                }
                GridCell(p, results)
            }
            cells.forEach { c ->
                sb.appendLine(
                    "| ${c.params.maxRungs} | ${c.params.stepDownPct} | ${c.params.stepUpPct} | %.2f | %.2f | %.2f | %.2f | %.1f | %.2f | %s%s |"
                        .format(c.bearMedian, c.bearBuyHoldMedian, c.bullMedian, c.bullBuyHoldMedian, c.worstMdd, c.avgExposure, if (c.passes) "✓" else "✗", if (c.params == candidate) " ← 후보" else ""),
                )
            }
            val candidateCell = cells.first { it.params == candidate }
            sb.appendLine("통과 ${cells.count { it.passes }}/${cells.size}; 후보 5/3/3 ${if (candidateCell.passes) "채택" else "기준 미달 — '근거 없음' 표기로 유지"}")
            sb.appendLine("per-fixture(후보): " + candidateCell.perFixture)
            candidateCell
        }
        println(sb)
        // 판정 자체는 결과에 따라 갈리므로 여기서 단언하지 않는다 — 결과표·판정문을 plan 에 기록한다.
        assertTrue(candidateCells.size == 2)
    }

    private class GridCell(val params: LadderParams, results: List<Pair<Regime, AccumulateBacktestResult>>) {
        private val bear = results.filter { it.first == Regime.BEAR }.map { it.second }
        private val bull = results.filter { it.first == Regime.BULL }.map { it.second }
        val bearMedian = bear.map { it.netReturnPct }.median()
        val bearBuyHoldMedian = bear.map { it.buyAndHoldPct }.median()
        val bullMedian = bull.map { it.netReturnPct }.median()
        val bullBuyHoldMedian = bull.map { it.buyAndHoldPct }.median()
        val worstMdd = results.maxOf { it.second.maxDrawdownPct }
        val avgExposure = results.map { it.second.avgInvestedFraction }.average()
        val passes = bullMedian > 0.0 && bearMedian > bearBuyHoldMedian && worstMdd <= 40.0
        val perFixture = results.joinToString(" / ") { (regime, r) ->
            "${regime.name.lowercase()} ${r.ticker}: net %.2f (B&H %.2f, MDD %.1f, buys %d, sells %d, exp %.2f)".format(r.netReturnPct, r.buyAndHoldPct, r.maxDrawdownPct, r.buys, r.sells, r.avgInvestedFraction)
        }
    }
}

private fun List<Double>.median(): Double {
    val s = sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}
