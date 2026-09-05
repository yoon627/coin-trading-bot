package com.trading.bot.engine

import com.trading.common.domain.Candle
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 라이브 현행 설정 vs 탐색 생존 설정을 **금액까지** 나란히 놓고, 보유상한(`maxHoldDays`)만 따로 흔들어 본다.
 *
 * 스윕 리포트는 baseline 대비 delta 만 보여줘서 "그래서 얼마 버는가" 에 답하지 못한다. 여기서는 각 설정의
 * **절대 수익률과 원화 환산**을 낸다 — 라이브가 `maxInvestAmount` 10만원 고정 노셔널이므로
 * `Σpnl% × 100,000 / 100` 이 그 마켓에서 실제로 남는 금액이다.
 *
 * 실행: `RUN_CANDIDATE_COMPARE=true ./gradlew :bot:test --tests "*CandidateComparisonTest*" --rerun-tasks`
 */
class CandidateComparisonTest {

    private val search = StrategySearch()

    /** 라이브가 한 마켓에 넣는 1회 매수 상한 — `TradingProperties.maxInvestAmount` 기본값. */
    private val notionalKrw = 100_000.0

    private data class Row(val label: String, val point: SweepPoint)

    private fun live() = StrategySearchGrid.baselinePoint()

    /** 탐색 생존 계열 — k0.3 / 익절 off / 손절 7 / 트레일링 1.5·arm 0. 보유상한만 바꿔가며 본다. */
    private fun candidate(hold: Int) = live().copy(
        kValue = 0.3,
        takeProfitPct = StrategySearchGrid.TAKE_PROFIT_OFF,
        maxLossPct = 7.0,
        trailingStopPct = 1.5,
        trailingArmPct = 0.0,
        maxHoldDays = hold,
    )

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_CANDIDATE_COMPARE", matches = "true")
    fun `compare live against the surviving candidate and sweep the hold limit`() = runBlocking {
        val yearly = YearlyFixtures.loadAll()
        val windows = buildList {
            add("1년 전체" to (yearly to StrategySearch.Segment("전체", 0..364)))
            add("선택창" to (yearly to StrategySearch.SELECT))
            add("검증창" to (yearly to StrategySearch.VALIDATE))
            // `Regime.entries` 가 아니라 고정 목록이다 — 2026-09-05 에 국면이 7개 추가돼, entries 를 쓰면
            // 이미 wiki 에 인용된 수치(`query/parameter-search-2026-09` 의 금액 비교)의 모집단이 11국면으로 조용히 바뀐다.
            for (regime in BacktestFixtures.PUBLISHED_FOUR) {
                add(regime.label to (BacktestFixtures.loadAll(regime) to StrategySearch.REGIME))
            }
        }

        val rows = buildList {
            add(Row("라이브 현행 (TP5/SL5/트레일2·arm3/h1/k0.5)", live()))
            for (hold in HOLD_DAYS) add(Row("생존 후보 · 보유 ${holdLabel(hold)}", candidate(hold)))
            // 보유상한만 바꾼 라이브 — "익절·트레일링은 그대로 두고 하루 제한만 늘리면?" 에 답한다.
            for (hold in listOf(2, 3, 5)) add(Row("라이브 현행 · 보유 ${holdLabel(hold)}만 변경", live().copy(maxHoldDays = hold)))
        }

        val report = StringBuilder()
        report.appendLine("# 라이브 현행 vs 생존 후보 — 절대 수익과 보유상한 민감도")
        report.appendLine()
        report.appendLine("고정 노셔널 ${"%,.0f".format(notionalKrw)}원/마켓 기준. 수익률은 `Σ 거래별 net pnl%`(왕복 수수료 차감), 금액은 그 값 × 노셔널.")
        report.appendLine("**8마켓 합계는 8종을 동시에 굴렸을 때의 합**이고, 실제 계좌 수익률은 그 합을 총 투입 자본으로 나눈 값이다.")
        report.appendLine()

        for ((windowName, spec) in windows) {
            val (fixtures, segment) = spec
            val metrics = search.measure(fixtures, segment, rows.map { it.point }.distinct())
            report.appendLine("## $windowName (거래봉 ${segment.inputRange.count() - BacktestEngine.MIN_CANDLES}, 마켓 ${fixtures.size})")
            report.appendLine()
            report.appendLine("| 설정 | 마켓 중앙값 % | 8마켓 합계 % | 합계 금액 | 거래수 | 노출 | 최악 MDD %p |")
            report.appendLine("|---|---|---|---|---|---|---|")
            for (row in rows) {
                val m = metrics.getValue(row.point)
                val values = m.returnByMarket.values
                val sum = values.sum()
                report.appendLine(
                    "| %s | %.2f | %.2f | %s원 | %d | %.2f | %.1f |".format(
                        row.label, SwingMetrics.median(values.toList()), sum,
                        "%,.0f".format(sum * notionalKrw / 100.0), m.trades, m.exposure, m.worstMdd,
                    ),
                )
            }
            report.appendLine()
        }

        report.appendLine("## 한계")
        report.appendLine()
        report.appendLine("- 백테는 일봉 1개만 본다 — 실거래는 10초 tick 이라 **트레일링 1.5% 는 여기보다 자주 걸린다**(이 표가 생존 후보를 과대평가하는 방향).")
        report.appendLine("- 슬리피지·부분체결·호가 없음. 거래가 잦은 설정일수록 이 낙관이 커진다.")
        report.appendLine("- `1년 전체` 를 뺀 나머지 창은 선택/검증/국면 분할이라 서로 겹치거나 기간이 다르다 — 창끼리 금액을 더하지 말 것.")
        report.appendLine("- 마켓 로스터가 국면마다 다르다(시점 중립 선정). 국면 간 금액 비교는 같은 종목의 성과 비교가 아니다.")

        val out = Path.of("build/reports/candidate-comparison.md")
        Files.createDirectories(out.parent)
        Files.writeString(out, report.toString())
        println("[compare] 리포트 기록: ${out.toAbsolutePath()}")
        assertTrue(report.contains("1년 전체"))
    }

    private fun holdLabel(hold: Int) = if (hold >= 365) "무제한" else "${hold}일"

    private companion object {
        val HOLD_DAYS = listOf(1, 2, 3, 5, 10, 365)
    }
}
