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
        Policy("부분 30%") { it.copy(holdLimitSellFraction = 0.3) },
        Policy("부분 50%") { it.copy(holdLimitSellFraction = 0.5) },
        Policy("부분 70%") { it.copy(holdLimitSellFraction = 0.7) },
        Policy("연장 2일") { it.copy(maxHoldDays = 2) },
        Policy("연장 3일") { it.copy(maxHoldDays = 3) },
        Policy("연장 5일") { it.copy(maxHoldDays = 5) },
        Policy("연장 10일") { it.copy(maxHoldDays = 10) },
        Policy("폐지 — 가격 게이트만") { it.copy(maxHoldDays = 365) },
        Policy("재진입 1봉 쿨다운") { it.copy(reentryCooldownBars = 1) },
        Policy("재진입 2봉 쿨다운") { it.copy(reentryCooldownBars = 2) },
        Policy("재진입 legacy(2봉 공백)") { it.copy(reentryMode = ReentryMode.LEGACY_NEXT_BAR, reentryCooldownBars = 0) },
    )

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_RESET_POLICY", matches = "true")
    fun `sweep every hold-limit policy across bases and regimes`() = runBlocking {
        val yearly = YearlyFixtures.loadAll()
        val windows = buildList {
            add(Triple("1년 전체", yearly, StrategySearch.Segment("전체", 0..364)))
            add(Triple("선택창", yearly, StrategySearch.SELECT))
            add(Triple("검증창", yearly, StrategySearch.VALIDATE))
            for (regime in BacktestFixtures.Regime.entries) {
                add(Triple(regime.label, BacktestFixtures.loadAll(regime), StrategySearch.REGIME))
            }
        }

        val md = StringBuilder()
        val tsv = StringBuilder("window\tbase\tpolicy\tmedian_pct\tsum_pct\tkrw\ttrades\texposure\tworst_mdd\tdelta_median\tdelta_positive\n")
        md.appendLine("# 보유상한(익일 09:00) 정책 스윕")
        md.appendLine()
        md.appendLine("고정 노셔널 ${"%,.0f".format(notionalKrw)}원/마켓. `Δ중앙`·`Δ+마켓` 은 **같은 base 의 현행 전량 정책 대비** 마켓별 paired delta 다.")
        md.appendLine("부분 청산 정책의 잔여는 보유상한을 다시 받지 않고 가격 게이트로만 나간다. 백테는 일봉이라 09:00 **시각 자체**는 검증 대상이 아니다(M1 fixture 부재 — #143).")
        md.appendLine()

        for ((windowName, fixtures, segment) in windows) {
            md.appendLine("## $windowName (거래봉 ${segment.inputRange.count() - BacktestEngine.MIN_CANDLES}, 마켓 ${fixtures.size})")
            md.appendLine()
            md.appendLine("| base | 정책 | 중앙값 % | 합계 % | 금액 | Δ중앙 %p | Δ+마켓 | 거래수 | 노출 | 최악 MDD %p |")
            md.appendLine("|---|---|---|---|---|---|---|---|---|---|")
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
                    md.appendLine(
                        "| %s | %s | %.2f | %.2f | %s원 | %+.2f | %d/%d | %d | %.2f | %.1f |".format(
                            base.name, policy.name, SwingMetrics.median(values), sum,
                            "%,.0f".format(sum * notionalKrw / 100.0), dMedian, dPositive, deltas.size,
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

        val out = Path.of("build/reports/reset-policy.md")
        Files.createDirectories(out.parent)
        Files.writeString(out, md.toString())
        Files.writeString(Path.of("build/reports/reset-policy.tsv"), tsv.toString())
        println("[reset] 리포트: ${out.toAbsolutePath()}")
        assertTrue(md.contains("1년 전체"))
    }

    /** 좌표는 식별자로만 쓰인다(config 가 진실) — 정책마다 겹치지 않는 더미 값을 준다. */
    private fun marker(index: Int) = StrategySearchGrid.baselinePoint().copy(maxHoldDays = 1000 + index)
}
