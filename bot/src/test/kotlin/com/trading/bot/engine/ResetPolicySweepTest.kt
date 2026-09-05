package com.trading.bot.engine

import com.trading.common.domain.Candle
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * "익일 09:00 전량매도가 최선인가" 를 정면으로 재는 스윕.
 *
 * 축은 **경계에서 무엇을 하는가** 다 — 전량(현행) / 조건부(손실이면 넘김) / 부분(f 만) / 연장(N일) / 폐지(무제한),
 * 그리고 청산 직후 **재진입 공백**. 각 정책을 두 base 설정(라이브 현행 · 탐색 생존 후보) 위에서 7개 창에 돌린다.
 *
 * 비교 기준은 **같은 base 의 현행 전량 정책**이다 — 정책만 바꿨을 때의 차이를 봐야 하므로 다른 base 와 섞지 않는다.
 *
 * 실행: `RUN_RESET_POLICY=true ./gradlew :bot:test --tests "*ResetPolicySweepTest*" --rerun-tasks`
 */
class ResetPolicySweepTest {

    private val search = StrategySearch()
    private val notionalKrw = 100_000.0

    private data class Base(val name: String, val config: BacktestConfig)
    private data class Policy(val name: String, val apply: (BacktestConfig) -> BacktestConfig)

    private val bases = listOf(
        Base("라이브 현행", StrategySearchGrid.baselinePoint().toConfig()),
        Base(
            "생존 후보",
            StrategySearchGrid.baselinePoint().copy(
                kValue = 0.3,
                takeProfitPct = StrategySearchGrid.TAKE_PROFIT_OFF,
                maxLossPct = 7.0,
                trailingStopPct = 1.5,
                trailingArmPct = 0.0,
            ).toConfig(),
        ),
    )

    private val policies = listOf(
        Policy("전량 리셋 h1 (현행)") { it },
        Policy("조건부 — 손실이면 넘김") { it.copy(holdLimitOnlyWhenProfitable = true) },
        // 2단 사다리 — 경계에서 f, 다음 경계에서 잔여 전량. f→1 이면 현행, f→0 이면 연장 2일로 수렴한다.
        Policy("부분 30% + 익일 잔여") { it.copy(holdLimitSellFraction = 0.3, holdLimitRemainderBoundaryDays = 1) },
        Policy("부분 50% + 익일 잔여") { it.copy(holdLimitSellFraction = 0.5, holdLimitRemainderBoundaryDays = 1) },
        Policy("부분 70% + 익일 잔여") { it.copy(holdLimitSellFraction = 0.7, holdLimitRemainderBoundaryDays = 1) },
        // 잔여를 상한에서 놓아주는 변종 — 위 사다리와 달리 사실상 '폐지 + 조기 일부 실현' 이다(거래수가 폐지와 같다).
        Policy("부분 50% + 잔여 방목") { it.copy(holdLimitSellFraction = 0.5) },
        Policy("연장 2일") { it.copy(maxHoldDays = 2) },
        Policy("연장 3일") { it.copy(maxHoldDays = 3) },
        Policy("연장 5일") { it.copy(maxHoldDays = 5) },
        Policy("연장 10일") { it.copy(maxHoldDays = 10) },
        Policy("폐지 — 가격 게이트만") { it.copy(maxHoldDays = 365) },
        Policy("재진입 1봉 쿨다운") { it.copy(reentryCooldownBars = 1) },
        Policy("재진입 2봉 쿨다운") { it.copy(reentryCooldownBars = 2) },
    )

    /**
     * 왕복 비용 수준 — 백테는 슬리피지·부분체결을 0 으로 두는데, **거래수가 가장 많은 현행 정책이 그 누락으로
     * 가장 크게 과대평가된다**. 비용을 올려가며 정책 순위가 언제 뒤집히는지 본다.
     */
    private val feeLevels = listOf(0.0005 to "왕복 0.10%(현행 가정)", 0.001 to "왕복 0.20%", 0.0015 to "왕복 0.30%", 0.002 to "왕복 0.40%")

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_RESET_POLICY", matches = "true")
    fun `sweep every hold-limit policy across bases and regimes`() = runBlocking {
        val yearly = YearlyFixtures.loadAll()
        val windows = buildList {
            add(Triple("1년 전체", yearly, StrategySearch.Segment("전체", 0..364)))
            add(Triple("선택창", yearly, StrategySearch.SELECT))
            add(Triple("검증창", yearly, StrategySearch.VALIDATE))
            // `Regime.entries` 가 아니라 고정 목록이다 — 2026-09-05 에 국면이 7개 추가돼, entries 를 쓰면
            // 이미 wiki 에 인용된 수치(`query/hold-limit-policy-2026-09` 의 7창)의 모집단이 11국면으로 조용히 바뀐다.
            for (regime in BacktestFixtures.PUBLISHED_FOUR) {
                add(Triple(regime.label, BacktestFixtures.loadAll(regime), StrategySearch.REGIME))
            }
        }

        val md = StringBuilder()
        val tsv = StringBuilder("window\tbase\tpolicy\tmedian_pct\tsum_pct\tkrw\ttrades\texposure\tworst_mdd\tdelta_median\tdelta_positive\n")
        md.appendLine("# 보유상한(익일 09:00) 정책 스윕")
        md.appendLine()
        md.appendLine("고정 노셔널 ${"%,.0f".format(notionalKrw)}원/마켓. `Δ중앙`·`Δ+마켓` 은 **같은 base 의 현행 전량 정책 대비** 마켓별 paired delta 다.")
        md.appendLine("부분 청산은 **2단 사다리**(경계에서 f, 다음 경계에서 잔여 전량)라 f→1 이면 현행, f→0 이면 연장 2일로 수렴한다. `잔여 방목` 변종만 잔여가 상한을 벗어나며, 그 행은 거래수가 `폐지` 와 같아진다(측정 아티팩트 대조군으로 남겨 둔다). 백테는 일봉이라 09:00 **시각 자체**는 검증 대상이 아니다(M1 fixture 부재 — #143).")
        md.appendLine()

        for ((windowName, fixtures, segment) in windows) {
            md.appendLine("## $windowName (거래봉 ${segment.inputRange.count() - BacktestEngine.MIN_CANDLES}, 마켓 ${fixtures.size})")
            md.appendLine()
            md.appendLine("| base | 정책 | 중앙값 % | 합계 % | 금액 | Δ중앙 %p | **Δ합계 %p** | Δ+마켓 | 거래수 | 노출 | 최악 MDD %p |")
            md.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
            for (base in bases) {
                val points = policies.mapIndexed { i, p -> p to marker(i) }
                val configs = points.associate { (policy, point) -> point to policy.apply(base.config) }
                val metrics = search.measureWithConfigs(fixtures, segment, configs)
                val reference = metrics.getValue(points.first().second).returnByMarket

                for ((policy, point) in points) {
                    val m = metrics.getValue(point)
                    val values = m.returnByMarket.values.toList()
                    val sum = values.sum()
                    val deltas = StrategySearchGates.pairedDeltas(m.returnByMarket, reference)
                    val dMedian = SwingMetrics.median(deltas)
                    val dPositive = deltas.count { it > 0 }
                    // Δ중앙만 보면 소수 마켓에서만 갈리는 정책이 구조적으로 0 으로 찍힌다(8마켓의 4·5번째 값만 반영).
                    val dSum = sum - reference.values.sum()
                    md.appendLine(
                        "| %s | %s | %.2f | %.2f | %s원 | %+.2f | %+.2f | %d/%d | %d | %.2f | %.1f |".format(
                            base.name, policy.name, SwingMetrics.median(values), sum,
                            "%,.0f".format(sum * notionalKrw / 100.0), dMedian, dSum, dPositive, deltas.size,
                            m.trades, m.exposure, m.worstMdd,
                        ),
                    )
                    tsv.append(
                        "%s\t%s\t%s\t%.4f\t%.4f\t%.0f\t%d\t%.4f\t%.4f\t%.4f\t%d\n".format(
                            windowName, base.name, policy.name, SwingMetrics.median(values), sum,
                            sum * notionalKrw / 100.0, m.trades, m.exposure, m.worstMdd, dMedian, dPositive,
                        ),
                    )
                }
            }
            md.appendLine()
        }

        // 비용 민감도 — 현행이 거래수 최다이므로 비용이 오르면 가장 먼저 손해를 본다.
        // ⚠️ 창 평균으로 집계하면 한 창의 큰 열세가 다른 창의 역전을 상쇄해 **가린다**(실제로 그렇게 놓쳤다).
        // 창별 승수와 최악 창을 함께 낸다.
        md.appendLine("## 비용 민감도 (전 7창)")
        md.appendLine()
        md.appendLine("백테는 슬리피지·부분체결을 0 으로 둔다. **현행은 거래수가 가장 많은 정책**이라 그 누락으로 가장 크게 과대평가된다.")
        md.appendLine("각 칸 = `현행을 이긴 창 수/7 (독립 3창 중 승수) · 최악 창 Δ중앙`. 독립 3창 = 상승장 2023-11~2024-06 · 2024-06~12 · 2025-01~07.")
        md.appendLine()
        val independent = BacktestFixtures.TIME_INDEPENDENT.map { it.label }.toSet()
        for (base in bases) {
            md.appendLine("### ${base.name}")
            md.appendLine()
            md.appendLine("| 정책 | " + feeLevels.joinToString(" | ") { it.second } + " |")
            md.appendLine("|---|" + feeLevels.joinToString("") { "---|" })
            val points = policies.mapIndexed { i, p -> p to marker(i) }
            val cells = LinkedHashMap<String, MutableList<String>>()
            for ((fee, _) in feeLevels) {
                val perPolicy = HashMap<String, MutableList<Pair<String, Double>>>()
                for ((windowName, fixtures, segment) in windows) {
                    val configs = points.associate { (policy, point) -> point to policy.apply(base.config).copy(feeRate = fee) }
                    val metrics = search.measureWithConfigs(fixtures, segment, configs)
                    val reference = metrics.getValue(points.first().second).returnByMarket
                    for ((policy, point) in points) {
                        val m = metrics.getValue(point)
                        val d = SwingMetrics.median(StrategySearchGates.pairedDeltas(m.returnByMarket, reference))
                        perPolicy.getOrPut(policy.name) { ArrayList() }.add(windowName to d)
                        tsv.append(
                            "%s@fee%.2f\t%s\t%s\t%.4f\t%.4f\t%.0f\t%d\t%.4f\t%.4f\t%.4f\t%d\n".format(
                                windowName, fee * 2 * 100, base.name, policy.name,
                                SwingMetrics.median(m.returnByMarket.values.toList()), m.returnByMarket.values.sum(),
                                m.returnByMarket.values.sum() * notionalKrw / 100.0, m.trades, m.exposure, m.worstMdd,
                                d, StrategySearchGates.pairedDeltas(m.returnByMarket, reference).count { it > 0 },
                            ),
                        )
                    }
                }
                for ((name, values) in perPolicy) {
                    val wins = values.count { it.second > 0 }
                    val indepWins = values.count { it.first in independent && it.second > 0 }
                    val worst = values.minByOrNull { it.second }!!
                    cells.getOrPut(name) { ArrayList() }
                        .add("%d/7 (독립 %d/3) · 최악 %s %+.2f".format(wins, indepWins, worst.first, worst.second))
                }
            }
            for ((name, row) in cells) md.appendLine("| $name | " + row.joinToString(" | ") + " |")
            md.appendLine()
        }

        val out = Path.of("build/reports/hold-limit-policy.md")
        Files.createDirectories(out.parent)
        Files.writeString(out, md.toString())
        Files.writeString(Path.of("build/reports/hold-limit-policy.tsv"), tsv.toString())
        println("[reset] 리포트: ${out.toAbsolutePath()}")
        assertTrue(md.contains("1년 전체"))
    }

    /** 좌표는 식별자로만 쓰인다(config 가 진실) — 정책마다 겹치지 않는 더미 값을 준다. */
    private fun marker(index: Int) = StrategySearchGrid.baselinePoint().copy(maxHoldDays = 1000 + index)
}
