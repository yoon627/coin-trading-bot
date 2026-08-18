package com.trading.bot.engine

import com.trading.common.config.TradingProperties
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
 * 실제 Upbit 일봉(8마켓 × 200봉)으로 9개 전략을 비교한다.
 *
 * **이 테스트는 승격 판정이 아니라 관찰 기록이다.** 표본이 검정력을 못 낸다 — 마켓 간 수익률 상관이 평균 0.49라
 * 실효 독립 표본은 2개 남짓이고, 구간이 하락장 한 국면이며, 전략당 out-of-sample 거래는 10건 안팎이다.
 * 통과 조건은 "좋은 숫자"가 아니라 **수치를 재현 가능하게 얻는 것**이다.
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
        sample: (List<com.trading.common.domain.Candle>) -> List<com.trading.common.domain.Candle>,
        config: BacktestConfig,
    ): List<Row> {
        val pooled = mutableMapOf<String, MutableList<Double>>()
        val endCount = mutableMapOf<String, Int>()
        val wins = mutableMapOf<String, Int>()

        for ((market, candles) in BacktestFixtures.loadAll()) {
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
    fun `compares all strategies across markets and prints the record`() = runTest {
        val report = buildString {
            appendLine("무릎 전략 백테 관찰 기록 — 8마켓 × 200봉 (2026-01-31 ~ 2026-08-18, 하락장)")
            appendLine("주의: 마켓 상관 0.49 → 실효 독립 표본 약 2개. 판정이 아니라 기록이다.")
            appendLine()
            appendLine(render("IN-SAMPLE / LIVE_DEFAULT (maxHold=1, chartExit=off)", aggregate(BacktestFixtures::inSample, liveDefault)))
            appendLine(render("OUT-OF-SAMPLE / LIVE_DEFAULT", aggregate(BacktestFixtures::outOfSample, liveDefault)))
            appendLine(render("IN-SAMPLE / SWING (maxHold=10, chartExit=on)", aggregate(BacktestFixtures::inSample, swing)))
            appendLine(render("OUT-OF-SAMPLE / SWING", aggregate(BacktestFixtures::outOfSample, swing)))
        }
        println(report)

        // 관찰 기록이 실제로 만들어졌는지만 고정한다 — 특정 전략이 이겨야 한다고 박지 않는다.
        val inDefault = aggregate(BacktestFixtures::inSample, liveDefault)
        assertEquals(strategies.size, inDefault.size)
        listOf("knee_reversal", "knee_pullback").forEach { name ->
            assertTrue(
                inDefault.any { it.strategy == name && it.trades > 0 },
                "$name 이 in-sample 에서 한 번도 진입하지 않아 비교가 성립하지 않는다",
            )
        }
    }

    @Test
    fun `in and out of sample trades never overlap`() = runTest {
        // 한 마켓·한 전략만 보면 그 조합의 신호가 0건일 때 단언이 공허 통과한다. 전 조합을 돌고,
        // 거래가 있는 조합이 실제로 존재했는지까지 확인해야 분할 로직을 뒤집었을 때 잡힌다.
        var checked = 0

        for ((market, candles) in BacktestFixtures.loadAll()) {
            for (strategy in strategies) {
                val inTrades = engine.run(strategy.name, BacktestFixtures.inSample(candles), market, liveDefault)
                    ?.trades?.map { it.buyIndex }?.toSet().orEmpty()
                // out 구간은 원본 80번째 봉부터 시작하므로 시간축으로 되돌려 비교한다.
                val outTrades = engine.run(strategy.name, BacktestFixtures.outOfSample(candles), market, liveDefault)
                    ?.trades?.map { it.buyIndex + 80 }?.toSet().orEmpty()
                if (inTrades.isEmpty() && outTrades.isEmpty()) continue

                // 체결은 신호 다음 봉이라 하한이 워밍업 +1 이다 — 범위를 넓게 잡으면 off-by-one 을 놓친다.
                assertTrue(inTrades.all { it in 51..129 }, "$market/${strategy.name} in 진입이 구간 밖: $inTrades")
                assertTrue(outTrades.all { it in 131..199 }, "$market/${strategy.name} out 진입이 워밍업 침범: $outTrades")
                assertEquals(emptySet<Int>(), inTrades intersect outTrades, "$market/${strategy.name} 두 구간 진입이 겹친다")
                checked++
            }
        }

        assertTrue(checked > 0, "거래가 있는 조합이 하나도 없어 검증이 공허하다")
    }

    @Test
    fun `aggregation totals match the underlying backtest results`() = runTest {
        // 집계 코드가 조용히 틀려도 리포트 숫자만 바뀔 뿐이라, 원본 결과와의 항등식으로 고정한다.
        val rows = aggregate(BacktestFixtures::inSample, liveDefault).associateBy { it.strategy }

        var totalTrades = 0
        var totalEnd = 0
        for ((market, candles) in BacktestFixtures.loadAll()) {
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
        for ((market, candles) in BacktestFixtures.loadAll()) {
            for (result in engine.compareAll(BacktestFixtures.inSample(candles), market, liveDefault)) {
                assertTrue(
                    result.trades.none { it.reason == "CHART_EXIT" },
                    "$market/${result.strategyName}: 기본 설정인데 CHART_EXIT 이 나왔다",
                )
            }
        }
    }
}
