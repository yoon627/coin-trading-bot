package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.ExitGates
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * "익일 09:00 에 **꼭 전량**이어야 하나" 를 240분봉에서 재판정한다.
 *
 * 선행 [ResetPolicySweepTest] 가 같은 축을 13정책으로 쟀지만 **일봉**이었다. 그런데 부분 청산·조건부는
 * 봉 내 경로에 의존하는 게이트이고, 일봉 백테는 그 축에서 라이브와 다른 정책을 잰다는 것이
 * `query/exit-resolution-verdict-2026-09` 의 실측이다. 그래서 같은 질문을 해상도만 올려 다시 묻는다.
 *
 * 진입은 D1 현행 정책의 일정에 고정한다 — 따라서 **연장·폐지가 다음 진입 기회를 막는 기회비용은 이 표에 없다**
 * (선행 스윕이 부분 청산을 기각한 주된 이유가 그 채널이었다). 여기서 재는 것은 **같은 거래를 다르게 청산했을 때의 차이**뿐이다.
 *
 * 실행: `RUN_HOLD_POLICY_INTRADAY=true ./gradlew :bot:test --tests "*HoldLimitPolicyIntradayTest*" --rerun-tasks`
 */
class HoldLimitPolicyIntradayTest {

    private val props = TradingProperties()
    private val notionalKrw = 100_000.0

    private data class Policy(val name: String, val config: BacktestConfig)
    private data class Window(val label: String, val dir: String, val daily: Map<String, List<Candle>>, val segment: StrategySearch.Segment)

    private fun base() = StrategySearchGrid.baselinePoint().toConfig()

    private fun policies() = listOf(
        Policy("전량 h1 (현행)", base()),
        Policy("조건부 — 손실이면 넘김", base().copy(holdLimitOnlyWhenProfitable = true)),
        Policy("부분 30% + 익일 잔여", base().copy(holdLimitSellFraction = 0.3, holdLimitRemainderBoundaryDays = 1)),
        Policy("부분 50% + 익일 잔여", base().copy(holdLimitSellFraction = 0.5, holdLimitRemainderBoundaryDays = 1)),
        Policy("부분 70% + 익일 잔여", base().copy(holdLimitSellFraction = 0.7, holdLimitRemainderBoundaryDays = 1)),
        Policy("연장 2일", base().copy(maxHoldDays = 2)),
        Policy("연장 3일", base().copy(maxHoldDays = 3)),
        Policy("연장 5일", base().copy(maxHoldDays = 5)),
        Policy("연장 10일", base().copy(maxHoldDays = 10)),
        Policy("폐지 — 가격 게이트만", base().copy(maxHoldDays = 365)),
    )

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_HOLD_POLICY_INTRADAY", matches = "true")
    fun `re-adjudicate the boundary policy on intraday bars`() = runBlocking {
        val windows = buildList {
            add(Window("1년 전체 (yearly)", "yearly", YearlyFixtures.loadAll(), StrategySearch.Segment("전체", 0..364)))
            for (r in BacktestFixtures.TIME_INDEPENDENT) add(Window(r.label, r.dir, BacktestFixtures.loadAll(r), StrategySearch.REGIME))
            BacktestFixtures.Regime.BEAR.let { add(Window(it.label, it.dir, BacktestFixtures.loadAll(it), StrategySearch.REGIME)) }
        }
        val policies = policies()

        val out = StringBuilder()
        out.appendLine("# 경계에서 **꼭 전량**이어야 하나 — 240분봉 재판정")
        out.appendLine()
        out.appendLine("base 는 **라이브 현행 하나**다. 선행 스윕은 탐색 생존 후보를 두 번째 base 로 뒀지만,")
        out.appendLine("그 후보는 청산 해상도를 올리면 우위가 남지 않는다(`query/exit-resolution-verdict-2026-09`) — 라이브 대안이 아니다.")
        out.appendLine()
        out.appendLine("**이 표에 없는 것**: 진입을 D1 현행 일정에 고정했으므로 **연장·폐지가 같은 마켓의 다음 진입을 막는 기회비용**이 빠져 있다.")
        out.appendLine("선행 일봉 스윕이 부분·연장을 기각한 주된 채널이 그것이므로, 여기서 연장·폐지가 좋아 보여도 그 값은 **상한**이다.")
        out.appendLine("반대로 **부분 청산은 잔여를 하루 더 들고 있을 뿐이라 그 채널의 영향이 가장 작다** — 이 표가 가장 잘 답하는 정책이다.")
        out.appendLine()

        for (w in windows) {
            val intraday = IntradayFixtures.loadAll(w.dir, w.daily.keys)
            val rows = collect(w, intraday, policies)
            val full = rows.getValue(policies.first().name)
            out.appendLine("## ${w.label}")
            out.appendLine()
            out.appendLine("| 정책 | Σpnl %p | 금액 | 격차 %p | 5% 하한 | P(격차 ≤ 0) | 구간끝 청산 |")
            out.appendLine("|---|---|---|---|---|---|---|")
            for (p in policies) {
                val r = rows.getValue(p.name)
                val sum = r.sumOf { it.pnl }
                val gap = sum - full.sumOf { it.pnl }
                val cells = if (p.name == policies.first().name) "— | — | —" else {
                    val dates = (r.map { it.date } + full.map { it.date }).distinct()
                    val byDate = dates.associateWith { d ->
                        r.filter { it.date == d }.sumOf { it.pnl } - full.filter { it.date == d }.sumOf { it.pnl }
                    }
                    val b = DateBlockBootstrap.of(byDate)
                    "%+.2f | %+.2f | **%.3f**".format(gap, b.p05, b.pLeZero)
                }
                out.appendLine("| %s | %+.2f | %s원 | %s | %d/%d |".format(
                    p.name, sum, "%,.0f".format(sum * notionalKrw / 100.0), cells, r.count { it.forcedClose }, r.size))
            }
            out.appendLine()
        }

        out.appendLine("## 읽는 법")
        out.appendLine()
        out.appendLine("- `구간끝 청산` = 구간 안에서 게이트가 안 걸려 마지막 봉 종가로 닫은 거래 수. 연장·폐지에서만 생긴다.")
        out.appendLine("  이 수가 크면 그 정책의 수치는 구간 경계에 좌우된다.")
        out.appendLine("- 격차는 **같은 거래집합**에서 청산만 바꾼 값이다 — 정책이 거래 수를 바꾸는 효과는 들어 있지 않다.")
        out.appendLine("- 불확실성은 청산 달력일 블록 부트스트랩(${DateBlockBootstrap.RESAMPLES}회). 마켓이 아니라 날짜가 재추출 단위다")
        out.appendLine("  — `yearly` 8마켓의 실효 독립 표본은 1.22 이기 때문이다(`backtest/README.md`).")

        val path = Path.of("build/reports/hold-limit-policy-intraday.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[policy] 리포트: ${path.toAbsolutePath()}")
        assertTrue(out.contains("전량 h1 (현행)"))
    }

    private data class Row(val date: String, val pnl: Double, val forcedClose: Boolean)

    /** 진입은 현행 정책의 D1 일정에 고정하고, 각 정책의 청산만 240분봉에서 replay 한다. */
    private suspend fun collect(
        w: Window,
        intraday: Map<String, List<Candle>>,
        policies: List<Policy>,
    ): Map<String, List<Row>> {
        val acc = policies.associate { it.name to ArrayList<Row>() }
        val entryConfig = policies.first().config
        val feePct = entryConfig.feeRate * 2 * 100

        for ((market, newestFirst) in w.daily) {
            val chronological = newestFirst.reversed()
            val input = chronological.subList(w.segment.inputRange.first, w.segment.inputRange.last + 1)
            val engine = BacktestEngine(listOf(YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }), props)
            val m = SwingMetrics.measure(engine, "combined", market, input, BacktestEngine.MIN_CANDLES, entryConfig)
            val bars = input.drop(BacktestEngine.MIN_CANDLES)
            val minute = intraday.getValue(market).reversed()
            // 경계는 라이브 09:00 KST 격자(= UTC 00:00)만이다. 봉마다 재평가하면 조건부가 다른 정책이 된다.
            val dailyBoundaries = minute.map { LocalDateTime.parse(it.candleDateTimeUtc) }.filter { it.hour == 0 }

            for (t in m.trades) {
                val entryUtc = LocalDateTime.parse(bars[t.buyIndex].candleDateTimeKst.substring(0, 10) + "T00:00:00")
                val slice = minute.filter { !LocalDateTime.parse(it.candleDateTimeUtc).isBefore(entryUtc) }
                if (slice.isEmpty()) continue
                for (p in policies) {
                    val limit = ExitGates.effectiveMaxHoldDays(p.config.maxHoldDays)
                    val boundaries = dailyBoundaries.filter { !it.isBefore(entryUtc.plusDays(limit.toLong())) }.toSet()
                    val r = M1ReplayEngine.replayPolicy(t.buyPrice, entryUtc, boundaries, slice, p.config)
                    val exit = r.exit
                    // 구간 안에서 청산이 안 나면 마지막 봉 종가로 닫는다(엔진의 closeOpenPosition 과 같은 규약).
                    // 제외하면 오래 버틴 거래만 빠져 정책 순위가 생존편향 산물이 된다.
                    val row = if (exit != null) {
                        Row(exit.exitUtc.substring(0, 10), exit.netPnlPct, false)
                    } else {
                        val last = slice.last()
                        Row(last.candleDateTimeUtc.substring(0, 10),
                            (last.tradePrice - t.buyPrice) / t.buyPrice * 100.0 - feePct, true)
                    }
                    acc.getValue(p.name).add(row)
                }
            }
        }
        return acc
    }
}
