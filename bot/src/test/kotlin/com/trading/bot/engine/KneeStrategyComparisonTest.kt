package com.trading.bot.engine

import com.trading.bot.engine.BacktestFixtures.Regime
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.BollingerBounce
import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.GoldenCross
import com.trading.common.strategy.KneePullback
import com.trading.common.strategy.KneeReversal
import com.trading.common.strategy.MacdCross
import com.trading.common.strategy.MeanReversion
import com.trading.common.strategy.RsiBounce
import com.trading.common.strategy.TradingStrategy
import com.trading.common.strategy.VolatilityBreakout
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 실제 Upbit 일봉으로 9개 전략을 비교한다.
 *
 * **승격 판정이 아니라 관찰 기록이다.** 표본이 검정력을 못 낸다 — 마켓 간 수익률 상관이 높아 실효 독립
 * 표본이 몇 개 되지 않고, 전략당 out-of-sample 거래는 10건 안팎이다. 통과 조건은 "좋은 숫자"가 아니라
 * **수치를 재현 가능하게 얻는 것**이다.
 *
 * 국면을 둘 두는 이유는 하락장 하나만 보면 "이 전략이 원래 나쁜지, 이 장에서만 나쁜지"를 못 가르기
 * 때문이다. 특히 [BacktestFixtures.PAIRED_MARKETS] 는 두 국면에 모두 있어 **마켓 효과를 통제**한다.
 *
 * 리포트는 stdout 으로 나가는데 이 프로젝트는 `testLogging.showStandardStreams` 를 켜지 않아 기본
 * `./gradlew test` 콘솔에는 보이지 않는다. 표를 보려면 `-i` 를 붙이거나
 * `bot/build/test-results/test/TEST-com.trading.bot.engine.KneeStrategyComparisonTest.xml` 을 연다.
 */
class KneeStrategyComparisonTest {

    private val strategies: List<TradingStrategy> = listOf(
        VolatilityBreakout(), GoldenCross(), BollingerBounce(), MeanReversion(),
        RsiBounce(), MacdCross(), CombinedStrategy(), KneeReversal(), KneePullback(),
    )

    private val engine = BacktestEngine(strategies, TradingProperties())

    /** 라이브 현재 설정 그대로. 어깨 청산은 maxHoldDays=1 이라 평가되지 않는다. */
    private val liveDefault = BacktestConfig(useMarketFilter = false)

    /** 라이브 설정에서 보유상한만 늘리고 차트 청산을 켠 조합 — 한 축만 바꿔 자의성을 줄였다. */
    private val swing = BacktestConfig(maxHoldDays = 10, chartExitEnabled = true, useMarketFilter = false)

    private data class Row(
        val strategy: String,
        val trades: Int,
        val avgNetPnl: Double,
        val winRate: Double,
        val endTrades: Int,
    )

    private suspend fun aggregate(
        markets: Map<String, List<Candle>>,
        sample: (List<Candle>) -> List<Candle>,
        config: BacktestConfig,
    ): List<Row> {
        val pooled = mutableMapOf<String, MutableList<Double>>()
        val endCount = mutableMapOf<String, Int>()
        val wins = mutableMapOf<String, Int>()

        for ((market, candles) in markets) {
            for (result in engine.compareAll(sample(candles), market, config)) {
                val bucket = pooled.getOrPut(result.strategyName) { mutableListOf() }
                result.trades.forEach { trade ->
                    bucket += trade.pnlPercent
                    if (trade.pnlPercent > 0) wins.merge(result.strategyName, 1, Int::plus)
                    if (trade.reason == "END") endCount.merge(result.strategyName, 1, Int::plus)
                }
            }
        }

        return strategies.map { s ->
            val pnls = pooled[s.name].orEmpty()
            Row(
                strategy = s.name,
                trades = pnls.size,
                avgNetPnl = if (pnls.isEmpty()) Double.NaN else pnls.average(),
                winRate = if (pnls.isEmpty()) Double.NaN else 100.0 * (wins[s.name] ?: 0) / pnls.size,
                endTrades = endCount[s.name] ?: 0,
            )
        }
    }

    private fun render(title: String, rows: List<Row>): String = buildString {
        appendLine("--- $title")
        appendLine(String.format("%-20s %7s %12s %9s %6s", "strategy", "trades", "avgNet%", "win%", "END"))
        rows.sortedByDescending { if (it.avgNetPnl.isNaN()) Double.NEGATIVE_INFINITY else it.avgNetPnl }
            .forEach {
                val avg = if (it.avgNetPnl.isNaN()) "N/A" else String.format("%+.3f", it.avgNetPnl)
                val win = if (it.winRate.isNaN()) "N/A" else String.format("%.1f", it.winRate)
                appendLine(String.format("%-20s %7d %12s %9s %6d", it.strategy, it.trades, avg, win, it.endTrades))
            }
    }

    @Test
    fun `compares all strategies across both regimes and prints the record`() = runTest {
        val report = buildString {
            appendLine("무릎 전략 백테 관찰 기록 — pooled per-trade net pnl%")
            appendLine("BEAR ${Regime.BEAR.label} 8마켓 / BULL ${Regime.BULL.label} 4마켓")
            appendLine("주의: 마켓 상관이 높아 실효 독립 표본은 몇 개 되지 않는다. 판정이 아니라 기록이다.")
            appendLine()
            for (regime in Regime.entries) {
                val all = BacktestFixtures.loadAll(regime)
                appendLine(render("[$regime] IN / LIVE_DEFAULT", aggregate(all, BacktestFixtures::inSample, liveDefault)))
                appendLine(render("[$regime] OUT / LIVE_DEFAULT", aggregate(all, BacktestFixtures::outOfSample, liveDefault)))
                appendLine(render("[$regime] IN / SWING", aggregate(all, BacktestFixtures::inSample, swing)))
                appendLine(render("[$regime] OUT / SWING", aggregate(all, BacktestFixtures::outOfSample, swing)))
            }
        }
        println(report)

        val inDefault = aggregate(BacktestFixtures.loadAll(Regime.BEAR), BacktestFixtures::inSample, liveDefault)
        assertEquals(strategies.size, inDefault.size)
        listOf("knee_reversal", "knee_pullback").forEach { name ->
            assertTrue(
                inDefault.any { it.strategy == name && it.trades > 0 },
                "$name 이 in-sample 에서 한 번도 진입하지 않아 비교가 성립하지 않는다",
            )
        }
    }

    @Test
    fun `paired comparison isolates regime from market selection`() = runTest {
        // 같은 4마켓을 두 국면에서 돌린다. 마켓 구성이 동일하므로 차이는 국면에서만 온다 —
        // 서로 다른 마켓 집합을 비교하면 국면 효과와 종목 효과가 섞인다.
        val report = buildString {
            appendLine("국면 paired 비교 — 같은 4마켓(${BacktestFixtures.PAIRED_MARKETS.joinToString(", ")})")
            appendLine()
            for (regime in Regime.entries) {
                val paired = BacktestFixtures.loadPaired(regime)
                appendLine(render("[$regime] OUT / LIVE_DEFAULT", aggregate(paired, BacktestFixtures::outOfSample, liveDefault)))
                appendLine(render("[$regime] OUT / SWING", aggregate(paired, BacktestFixtures::outOfSample, swing)))
            }
        }
        println(report)

        for (regime in Regime.entries) {
            val rows = aggregate(BacktestFixtures.loadPaired(regime), BacktestFixtures::outOfSample, liveDefault)
            assertEquals(strategies.size, rows.size, "$regime: 전략 수가 맞지 않는다")
        }
    }

    @Test
    fun `in and out of sample trades never overlap`() = runTest {
        // 한 마켓·한 전략만 보면 그 조합의 신호가 0건일 때 단언이 공허 통과한다. 전 조합을 돌고,
        // 거래가 있는 조합이 실제로 존재했는지까지 확인해야 분할 로직을 뒤집었을 때 잡힌다.
        var checked = 0

        for (regime in Regime.entries) {
            for ((market, candles) in BacktestFixtures.loadAll(regime)) {
                for (strategy in strategies) {
                    val inTrades = engine.run(strategy.name, BacktestFixtures.inSample(candles), market, liveDefault)
                        ?.trades?.map { it.buyIndex }?.toSet().orEmpty()
                    // out 구간은 원본 80번째 봉부터 시작하므로 시간축으로 되돌려 비교한다.
                    val outTrades = engine.run(strategy.name, BacktestFixtures.outOfSample(candles), market, liveDefault)
                        ?.trades?.map { it.buyIndex + 80 }?.toSet().orEmpty()
                    if (inTrades.isEmpty() && outTrades.isEmpty()) continue

                    // 체결은 신호 다음 봉이라 하한이 워밍업 +1 이다 — 범위를 넓게 잡으면 off-by-one 을 놓친다.
                    assertTrue(inTrades.all { it in 51..129 }, "$regime/$market/${strategy.name} in 진입이 구간 밖: $inTrades")
                    assertTrue(outTrades.all { it in 131..199 }, "$regime/$market/${strategy.name} out 진입이 워밍업 침범: $outTrades")
                    assertEquals(emptySet<Int>(), inTrades intersect outTrades, "$regime/$market/${strategy.name} 두 구간 진입이 겹친다")
                    checked++
                }
            }
        }

        assertTrue(checked > 0, "거래가 있는 조합이 하나도 없어 검증이 공허하다")
    }

    @Test
    fun `aggregation totals match the underlying backtest results`() = runTest {
        // 집계 코드가 조용히 틀려도 리포트 숫자만 바뀔 뿐이라, 원본 결과와의 항등식으로 고정한다.
        val markets = BacktestFixtures.loadAll(Regime.BEAR)
        val rows = aggregate(markets, BacktestFixtures::inSample, liveDefault).associateBy { it.strategy }

        var totalTrades = 0
        var totalEnd = 0
        for ((market, candles) in markets) {
            for (result in engine.compareAll(BacktestFixtures.inSample(candles), market, liveDefault)) {
                totalTrades += result.trades.size
                totalEnd += result.trades.count { it.reason == "END" }
            }
        }

        assertEquals(totalTrades, rows.values.sumOf { it.trades }, "pooled 거래수가 원본 합과 다르다")
        assertEquals(totalEnd, rows.values.sumOf { it.endTrades }, "END 집계가 원본과 다르다")
        rows.values.forEach {
            assertTrue(it.endTrades <= it.trades, "${it.strategy}: END 가 전체 거래수를 넘는다")
        }
    }

    @Test
    fun `chart exit never fires under the live default config`() = runTest {
        // maxHoldDays=1 이면 atHoldLimit 이 CHART_EXIT 을 먼저 차단한다(IntrabarExitModel).
        // SWING 조합과의 차이가 이 게이트에서 온다는 전제를 고정한다.
        for (regime in Regime.entries) {
            for ((market, candles) in BacktestFixtures.loadAll(regime)) {
                for (result in engine.compareAll(BacktestFixtures.inSample(candles), market, liveDefault)) {
                    assertTrue(
                        result.trades.none { it.reason == "CHART_EXIT" },
                        "$regime/$market/${result.strategyName}: 기본 설정인데 CHART_EXIT 이 나왔다",
                    )
                }
            }
        }
    }
}
