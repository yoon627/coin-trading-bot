package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 신규 비중첩 7국면(2020-01~2023-11)에서의 **사전고정 확증 판정**.
 *
 * 선행 3작업은 전부 사후 분석이었다 — 후보를 만든 데이터의 요약통계를 본 뒤에 통계량을 바꿨다.
 * 이 판정만 **아무도 값을 보지 않은 데이터** 위에서 규칙을 먼저 못 박고(plan `2026-09-05-regime-expansion`
 * `# Acceptance`, 커밋 `a25096d`) 돈다.
 *
 * 계기는 [LiveSemanticsArm] 이다 — 진입·청산이 모두 라이브 규약이다. 일봉으로 판정하면
 * `query/exit-resolution-verdict-2026-09` 이 방금 무효화한 계기를 새 데이터에 다시 쓰는 것이고, 되돌릴 수 없다.
 *
 * 주 통계량은 **7창을 이어붙인 pooled 격차 하나**다. 창별 부호검정("≥5/7 창")은 상관된 7개 관측 위의 검정이고,
 * 이 스레드가 마켓축(8마켓, 실효 독립 1.22)에서 이미 기각한 통계 형식이라 쓰지 않는다.
 *
 * 실행: `RUN_REGIME_EXPANSION=true ./gradlew :bot:test --tests "*RegimeExpansionTest*" --rerun-tasks`
 */
class RegimeExpansionTest {

    private val props = TradingProperties()
    private val notionalKrw = 100_000.0
    private val strategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }

    /** 후보 2개만. 새 그리드 탐색을 하지 않는다(사전고정 2). */
    private fun live() = StrategySearchGrid.baselinePoint()
    private fun variantA() = live().copy(trailingStopPct = 1.5, trailingArmPct = 0.0)
    private fun candidateE() = live().copy(
        kValue = 0.3, takeProfitPct = StrategySearchGrid.TAKE_PROFIT_OFF,
        maxLossPct = 7.0, trailingStopPct = 1.5, trailingArmPct = 0.0,
    )

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_REGIME_EXPANSION", matches = "true")
    fun `pre-registered adjudication on seven unseen regimes`() = runBlocking {
        val regimes = BacktestFixtures.EXPANSION_2020_2023
        val arms = listOf("라이브 현행" to live(), "변형 A · 트레일링 1축" to variantA(), "후보 E · 4축" to candidateE())

        // 창별로 거래를 모으되, 판정은 **전 창을 이어붙인 뒤 한 번만** 한다.
        val pooled = arms.associate { it.first to ArrayList<LiveSemanticsArm.Trade>() }
        val perWindow = LinkedHashMap<String, Map<String, List<LiveSemanticsArm.Trade>>>()

        for (r in regimes) {
            val daily = BacktestFixtures.loadAll(r)
            val intraday = IntradayFixtures.loadAll(r.dir, daily.keys)
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
                pooled.getValue(label).addAll(trades)
            }
            perWindow[r.label] = byArm
        }

        val out = StringBuilder()
        out.appendLine("# 신규 7국면 사전고정 확증 판정")
        out.appendLine()
        out.appendLine("판정 규칙은 **결과를 보기 전에** 커밋했다(plan `2026-09-05-regime-expansion` `# Acceptance`, `a25096d`).")
        out.appendLine("계기는 진입·청산 모두 라이브 규약인 `LiveSemanticsArm`, 주 통계량은 7창 pooled 격차 하나다.")
        out.appendLine()
        out.appendLine("## 주 판정 (pooled)")
        out.appendLine()
        out.appendLine("| 설정 | 거래수 | Σpnl %p | 금액 | 격차 %p | 5% 하한 | **P(격차 ≤ 0)** | 판정 |")
        out.appendLine("|---|---|---|---|---|---|---|---|")
        val baseTrades = pooled.getValue(arms.first().first)
        for ((label, _) in arms) {
            val t = pooled.getValue(label)
            val sum = t.sumOf { it.netPnlPct }
            if (label == arms.first().first) {
                out.appendLine("| %s | %d | %+.2f | %s원 | — | — | — | 기준 |".format(
                    label, t.size, sum, "%,.0f".format(sum * notionalKrw / 100.0)))
                continue
            }
            val dates = (t.map { it.exitDate } + baseTrades.map { it.exitDate }).distinct()
            val byDate = dates.associateWith { d ->
                t.filter { it.exitDate == d }.sumOf { it.netPnlPct } - baseTrades.filter { it.exitDate == d }.sumOf { it.netPnlPct }
            }
            val b = DateBlockBootstrap.of(byDate)
            val gap = sum - baseTrades.sumOf { it.netPnlPct }
            val found = b.pLeZero <= SIDAK_ALPHA && b.p05 > 0
            out.appendLine("| %s | %d | %+.2f | %s원 | %+.2f | %+.2f | **%.4f** | %s |".format(
                label, t.size, sum, "%,.0f".format(sum * notionalKrw / 100.0), gap, b.p05, b.pLeZero,
                if (found) "**발견**" else "발견 없음"))
        }
        out.appendLine()
        out.appendLine("발견 선언 기준: `P(격차 ≤ 0) ≤ %.4f`(후보 2개 Šidák) **그리고** 5%% 하한 > 0. 둘 다 만족해야 한다.".format(SIDAK_ALPHA))
        out.appendLine()

        out.appendLine("## 창별 진단 (판정에 쓰지 않는다)")
        out.appendLine()
        out.appendLine("창끼리 독립이 아니다 — 7창은 하나의 크립토 매크로 사이클이다. 창별 승수를 세는 것은")
        out.appendLine("이 스레드가 마켓축에서 이미 기각한 통계 형식이라 **표기만 하고 판정에 쓰지 않는다**.")
        out.appendLine()
        out.appendLine("| 창 | " + arms.joinToString(" | ") { it.first } + " |")
        out.appendLine("|---" + "|---".repeat(arms.size) + "|")
        for ((label, byArm) in perWindow) {
            out.appendLine("| %s | %s |".format(label, arms.joinToString(" | ") { (a, _) ->
                "%+.2f (%d건)".format(byArm.getValue(a).sumOf { it.netPnlPct }, byArm.getValue(a).size)
            }))
        }
        out.appendLine()
        out.appendLine("## 교란 진단 — 격차가 \"많이 오른 마켓\"에 몰려 있는가")
        out.appendLine()
        out.appendLine("두 후보는 **익절 5% 상한을 없앤(또는 크게 넓힌) 설정**이다. 크게 오른 마켓이 많은 표본에서는")
        out.appendLine("상한 제거가 구조적으로 이긴다 — 그리고 이 7창의 로스터는 \"2026년까지 살아남은 것 중 그 시점 상위 8\" 이라")
        out.appendLine("**생존편향이 정확히 그 방향으로 작동한다**. 사전고정에 이 통제는 들어 있지 않았으므로 진단으로 병기한다.")
        out.appendLine()
        out.appendLine("마켓별 단순보유 수익률(구간 시가→종가)과 그 마켓의 격차를 나란히 놓는다.")
        out.appendLine()
        out.appendLine("| 창 | 로스터 단순보유 중앙값 % | A 격차 %p | E 격차 %p |")
        out.appendLine("|---|---|---|---|")
        for (r in regimes) {
            val daily = BacktestFixtures.loadAll(r)
            val bh = daily.values.map { c ->
                val ch = c.reversed()
                (ch.last().tradePrice - ch.first().openingPrice) / ch.first().openingPrice * 100.0
            }.sorted()
            val median = (bh[bh.size / 2] + bh[(bh.size - 1) / 2]) / 2
            val w = perWindow.getValue(r.label)
            val base = w.getValue(arms.first().first).sumOf { it.netPnlPct }
            out.appendLine("| %s | %+.1f | %+.2f | %+.2f |".format(
                r.label, median,
                w.getValue(arms[1].first).sumOf { it.netPnlPct } - base,
                w.getValue(arms[2].first).sumOf { it.netPnlPct } - base))
        }
        out.appendLine()
        out.appendLine("| 참고: 선행 4국면(2023-11 이후, 이미 본 데이터) | 단순보유 중앙값 % | A 격차 | E 격차 |")
        out.appendLine("|---|---|---|---|")
        out.appendLine("| 상승장 2023-11~2024-06 | — | −3.06 | +78.51 |")
        out.appendLine("| 2024-06~12 | — | +21.25 | +5.16 |")
        out.appendLine("| 2025-01~07 | — | +2.27 | +4.95 |")
        out.appendLine("| yearly / 하락장 | — | +14.71 / −0.34 | −18.07 / −3.93 |")
        out.appendLine()
        out.appendLine("## 한계")
        out.appendLine()
        out.appendLine("- **생존편향은 과거로 갈수록 심해지고 측정 불가다.** 유니버스 선정이 *오늘 상장된* 마켓만 열거하므로")
        out.appendLine("  \"그 시점 상위 8\" 은 실제로 \"2026년까지 살아남은 것 중 상위 8\" 이다. 상장폐지 종목은 API 가 404 라")
        out.appendLine("  표본에 넣을 수도, 편향 크기를 추정할 수도 없다.")
        out.appendLine("- 7창은 서로 인접한 하나의 사이클이라 창끼리 독립이 아니다. pooled 부트스트랩도 그 상관을 완전히 흡수하지 못한다.")
        out.appendLine("- 240분봉이라 라이브(10초 tick)보다 성기고, 슬리피지·부분체결은 없다.")

        val path = Path.of("build/reports/seven-regime-adjudication.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[expansion] 리포트: ${path.toAbsolutePath()}")
        assertTrue(out.contains("주 판정 (pooled)"))
    }

    private companion object {
        /** 후보 2개에 대한 Šidák 보정: 1 − 0.95^(1/2). 사전고정 5. */
        const val SIDAK_ALPHA = 0.0253
    }
}
