package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 트레일링 **폭** 재판정 (#184) — 승격값 1.5 는 탐색 격자의 하한이고 D1 yearly 선택창에서는 1.0 쪽이 단조로 좋았다.
 * 그 단조성이 라이브 의미론 계기([LiveSemanticsArm], 240분봉)에서도 남는지 **사전고정**으로 판정한다
 * (plan `2026-09-08-algorithm-improve` `# Acceptance`, 결과 보기 전에 커밋).
 *
 * 가설이 yearly D1 에서 나왔으므로 yearly(와 그 안에 든 bear)는 주 판정에서 빼고 진단으로만 표기한다.
 * 주 통계량은 10창 pooled 격차 하나 — 창별 부호검정은 상관된 관측 위의 검정이라 쓰지 않는다([RegimeExpansionTest] 와 같은 이유).
 *
 * 실행: `RUN_TRAILING_WIDTH=true ./gradlew :bot:test --tests "*TrailingWidthIntradayTest*" --rerun-tasks`
 */
class TrailingWidthIntradayTest {

    private val props = TradingProperties()
    private val notionalKrw = 100_000.0
    private val strategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }

    private fun current() = StrategySearchGrid.currentLivePoint()
    private fun width(t: Double) = current().copy(trailingStopPct = t, trailingArmPct = 0.0)

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_TRAILING_WIDTH", matches = "true")
    fun `pre-registered trailing width adjudication on ten windows`() = runBlocking {
        val primary = BacktestFixtures.TIME_INDEPENDENT + BacktestFixtures.EXPANSION_2020_2023
        val arms = listOf(
            "현행 1.50" to current(),
            "후보 0.75" to width(0.75),
            "후보 1.00" to width(1.00),
            "후보 1.25" to width(1.25),
            "참고 2.00/arm3 (구 라이브)" to StrategySearchGrid.baselinePoint(),
        )
        val candidates = setOf("후보 0.75", "후보 1.00", "후보 1.25")
        require(arms.map { it.first }.containsAll(candidates)) { "후보 라벨이 arms 에 없다 — 조용히 '참고' 로 빠진다" }

        val pooled = arms.associate { it.first to ArrayList<LiveSemanticsArm.Trade>() }
        val perWindow = LinkedHashMap<String, Map<String, List<LiveSemanticsArm.Trade>>>()

        suspend fun runWindow(dir: String, daily: Map<String, List<com.trading.common.domain.Candle>>): Map<String, List<LiveSemanticsArm.Trade>> {
            val intraday = IntradayFixtures.loadAll(dir, daily.keys)
            val byArm = LinkedHashMap<String, List<LiveSemanticsArm.Trade>>()
            for ((label, point) in arms) {
                val trades = ArrayList<LiveSemanticsArm.Trade>()
                for ((market, newestFirst) in daily) {
                    trades += LiveSemanticsArm.run(
                        market, strategy, newestFirst.reversed(),
                        intraday.getValue(market).reversed(), point.toConfig(), props,
                    )
                }
                byArm[label] = trades
            }
            return byArm
        }

        for (r in primary) {
            val byArm = runWindow(r.dir, BacktestFixtures.loadAll(r))
            perWindow[r.label] = byArm
            byArm.forEach { (label, t) -> pooled.getValue(label).addAll(t) }
        }
        val yearly = runWindow("yearly", YearlyFixtures.loadAll())

        val out = StringBuilder()
        out.appendLine("# 트레일링 폭 사전고정 판정 — 10창, 라이브 의미론 240분봉")
        out.appendLine()
        out.appendLine("판정 규칙은 **결과를 보기 전에** 커밋했다(plan `2026-09-08-algorithm-improve` `# Acceptance`).")
        out.appendLine("기준은 현행 라이브(트레일 1.5 / arm 0). 가설이 yearly D1 에서 나왔으므로 yearly·bear 는 주 판정에서 뺐다.")
        out.appendLine()
        out.appendLine("## 주 판정 (10창 pooled)")
        out.appendLine()
        out.appendLine("| 설정 | 거래수 | Σpnl %p | 금액 | 격차 %p | 격차/거래 %p | 5% 하한 | **P(격차 ≤ 0)** | 판정 |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|")
        val baseTrades = pooled.getValue(arms.first().first)
        val baseSum = baseTrades.sumOf { it.netPnlPct }
        for ((label, _) in arms) {
            val t = pooled.getValue(label)
            val sum = t.sumOf { it.netPnlPct }
            if (label == arms.first().first) {
                out.appendLine("| %s | %d | %+.2f | %s원 | — | — | — | — | 기준 |".format(
                    label, t.size, sum, "%,.0f".format(sum * notionalKrw / 100.0)))
                continue
            }
            val b = bootstrap(t, baseTrades)
            val gap = sum - baseSum
            val verdict = when {
                label !in candidates -> "참고"
                b.pLeZero <= SIDAK_ALPHA && b.p05 > 0 -> "**발견**"
                else -> "발견 없음"
            }
            out.appendLine("| %s | %d | %+.2f | %s원 | %+.2f | %+.3f | %+.2f | **%.4f** | %s |".format(
                label, t.size, sum, "%,.0f".format(sum * notionalKrw / 100.0), gap, gap / t.size, b.p05, b.pLeZero, verdict))
        }
        out.appendLine()
        out.appendLine("발견 선언 기준: `P(격차 ≤ 0) ≤ %.4f`(후보 3개 Šidák) **그리고** 5%% 하한 > 0. 둘 다 만족해야 한다.".format(SIDAK_ALPHA))
        out.appendLine("여러 후보가 통과하면 P 가 아니라 **격차/거래** 가 큰 것을 후보로 보고한다(사전고정 4).")
        out.appendLine()

        out.appendLine("## 창별 진단 (판정에 쓰지 않는다)")
        out.appendLine()
        out.appendLine("| 창 | " + arms.joinToString(" | ") { it.first } + " |")
        out.appendLine("|---" + "|---".repeat(arms.size) + "|")
        fun row(label: String, byArm: Map<String, List<LiveSemanticsArm.Trade>>) {
            val base = byArm.getValue(arms.first().first)
            out.appendLine("| %s | %s |".format(label, arms.joinToString(" | ") { (a, _) ->
                val t = byArm.getValue(a)
                if (a == arms.first().first) "%+.2f (%d건)".format(t.sumOf { it.netPnlPct }, t.size)
                else "%+.2f (%d건)".format(t.sumOf { it.netPnlPct } - base.sumOf { it.netPnlPct }, t.size)
            }))
        }
        for ((label, byArm) in perWindow) row(label, byArm)
        row("(진단) 1년 전체 yearly — 가설 생성 창", yearly)
        out.appendLine()
        out.appendLine("기준 열은 Σpnl, 나머지 열은 기준 대비 격차다.")
        out.appendLine()

        out.appendLine("## 청산 사유 구성 (10창 pooled)")
        out.appendLine()
        out.appendLine("| 설정 | " + REASONS.joinToString(" | ") + " |")
        out.appendLine("|---" + "|---".repeat(REASONS.size) + "|")
        for ((label, _) in arms) {
            val t = pooled.getValue(label)
            out.appendLine("| %s | %s |".format(label, REASONS.joinToString(" | ") { r ->
                val g = t.filter { it.reason == r }
                "%d건 %+.1f".format(g.size, g.sumOf { it.netPnlPct })
            }))
        }
        out.appendLine()
        out.appendLine("## 한계")
        out.appendLine()
        out.appendLine("- **240분봉은 조인 트레일링에 유리한 방향으로 편향된다** — 봉 안의 신고점을 다음 봉에나 보므로 트레일링이 덜 걸리고,")
        out.appendLine("  라이브(10초 tick)는 더 자주 건다(`exit-resolution-verdict-2026-09` §4). 통과해도 상한이지 승격 근거가 아니다.")
        out.appendLine("- 슬리피지·부분체결·호가 스프레드 없음. 격차/거래 가 편도 몇 bp 인지가 실제 여유다.")
        out.appendLine("- 10창 중 7창은 하나의 크립토 사이클이라 창끼리 독립이 아니고, 생존편향은 과거로 갈수록 심하다.")

        val path = Path.of("build/reports/trailing-width-intraday.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[trailing-width] 리포트: ${path.toAbsolutePath()}")
        assertTrue(out.contains("주 판정 (10창 pooled)"))
        // 트레일링 폭만 바꾸면 진입은 불변이다 — 거래수가 갈리면 계기가 다른 것을 재고 있다.
        val counts = pooled.values.map { it.size }.distinct()
        assertTrue(counts.size == 1, "arm 간 거래수가 다르다: $counts")
    }

    private fun bootstrap(t: List<LiveSemanticsArm.Trade>, base: List<LiveSemanticsArm.Trade>): DateBlockBootstrap.Result {
        val dates = (t.map { it.exitDate } + base.map { it.exitDate }).distinct()
        val byDate = dates.associateWith { d ->
            t.filter { it.exitDate == d }.sumOf { it.netPnlPct } - base.filter { it.exitDate == d }.sumOf { it.netPnlPct }
        }
        return DateBlockBootstrap.of(byDate)
    }

    private companion object {
        /** 후보 3개 Šidák: 1 − 0.95^(1/3). 사전고정 4. */
        const val SIDAK_ALPHA = 0.0170
        val REASONS = listOf("TRAILING_STOP", "TAKE_PROFIT", "STOP_LOSS", "TIME_EXIT", "END")
    }
}
