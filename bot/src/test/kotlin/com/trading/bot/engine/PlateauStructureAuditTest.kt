package com.trading.bot.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * "G3 는 단일 축 효과에 구조적으로 불리하다" 는 변호를 **그리드 전체 통계로** 검증한다 (#181 후속).
 *
 * [PlateauAliasAuditTest] 가 A 한 좌표의 이웃을 봤다면, 여기는 세 가지를 더 본다.
 * 1. 통과한 생존 3좌표의 이웃 내역 — 그들도 효과 축 되돌림 이웃을 잃는가(잃는데 통과했다면 그 변호는 A 를 가르지 못한다).
 * 2. 축·방향별 전역 통과율 — "보유 연장·MA50 은 원래 나쁜 방향" 이 A 국소 사실인가 그리드 전역 사실인가.
 * 3. 이웃 수(분모)별 G3 통과율 — 격자 모서리 좌표가 분모가 작아 더 쉽게 통과하는가.
 *
 * 추가로 효과 축(트레일링)을 **그리드 밖 해상도**로 재본다 — A 가 능선인지 칼날인지는 1.5 양옆을 봐야 안다.
 *
 * 실행: `RUN_PLATEAU_STRUCTURE=true ./gradlew :bot:test --tests "*PlateauStructureAuditTest*" --rerun-tasks`
 */
class PlateauStructureAuditTest {

    private val search = StrategySearch()

    private fun live() = PlateauAuditPoints.live()
    private fun variantA() = PlateauAuditPoints.variantA()
    private fun survivors() = PlateauAuditPoints.survivors()

    /** 이웃이 어느 축으로 어느 방향인지 — 전역 집계 키. */
    private fun axisDir(a: SweepPoint, n: SweepPoint): String = when {
        n.kValue != a.kValue -> "k " + dir(n.kValue > a.kValue)
        n.takeProfitPct != a.takeProfitPct -> "TP " + dir(n.takeProfitPct > a.takeProfitPct)
        n.maxLossPct != a.maxLossPct -> "SL " + dir(n.maxLossPct > a.maxLossPct)
        n.trailingStopPct != a.trailingStopPct -> "트레일 " + dir(n.trailingStopPct > a.trailingStopPct)
        n.trailingArmPct != a.trailingArmPct -> "arm " + dir(n.trailingArmPct > a.trailingArmPct)
        n.maxHoldDays != a.maxHoldDays -> "보유 " + dir(n.maxHoldDays > a.maxHoldDays)
        n.marketFilterMa != a.marketFilterMa -> "필터 " + dir(n.marketFilterMa > a.marketFilterMa)
        else -> "(동일)"
    }

    private fun dir(up: Boolean) = if (up) "↑" else "↓"

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_PLATEAU_STRUCTURE", matches = "true")
    fun `is the plateau gate biased by axis direction or by neighbour count`() = runBlocking {
        val yearly = YearlyFixtures.loadAll()
        val grid = StrategySearchGrid.stageA()
        val baseline = live()

        // 효과 축을 그리드 밖 해상도로 — 능선인지 칼날인지.
        val trailScan = listOf(1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0).map { t ->
            t to variantA().copy(trailingStopPct = t)
        }

        val all = (grid.points + baseline + trailScan.map { it.second }).distinct()
        println("[structure] 좌표 ${all.size} 측정 시작")
        val select = search.measure(yearly, StrategySearch.SELECT, all)
        val base = select.getValue(baseline)

        fun deltas(p: SweepPoint) = StrategySearchGates.pairedDeltas(select.getValue(p).returnByMarket, base.returnByMarket)
        fun g1g5(p: SweepPoint): Boolean {
            val m = select.getValue(p)
            return StrategySearchGates.g5(m.trades, m.zeroTradeMarkets) && StrategySearchGates.g1(deltas(p))
        }

        val g1Pass = grid.points.filter { g1g5(it) }.toHashSet()
        println("[structure] G1+G5 통과 ${g1Pass.size}")

        val out = StringBuilder()
        out.appendLine("# G3 구조 감사 — 단일 축 변호를 그리드 전체로 검증")
        out.appendLine()
        out.appendLine("G1+G5 통과 좌표 ${g1Pass.size} / 명목 ${grid.points.size}")
        out.appendLine()

        // 1. A 와 생존 3좌표의 이웃 내역 — 효과 축(트레일 ↑) 이웃을 누가 잃는가.
        out.appendLine("## 1. A vs 생존 후보 — 이웃 내역")
        out.appendLine()
        out.appendLine("| 좌표 | 이웃 | 통과 | 비율 | G3 | 트레일↑ 이웃 | 그 이웃 G1 | 실패 이웃(축) |")
        out.appendLine("|---|---|---|---|---|---|---|---|")
        for ((name, p) in listOf("변형 A" to variantA()) + survivors()) {
            val ns = grid.neighbours(p)
            val passing = ns.count { it in g1Pass }
            val ratio = passing.toDouble() / ns.size
            val trailUp = ns.filter { it.trailingStopPct > p.trailingStopPct }
            val failed = ns.filter { it !in g1Pass }.joinToString("; ") { axisDir(p, it) }
            out.appendLine(
                "| %s | %d | %d | %.1f%% | %s | %d | %s | %s |".format(
                    name, ns.size, passing, ratio * 100,
                    if (ratio >= StrategySearchGates.PLATEAU_MIN_RATIO) "통과" else "**탈락**",
                    trailUp.size,
                    trailUp.joinToString(",") { if (it in g1Pass) "통과" else "탈락" }.ifEmpty { "—" },
                    failed.ifEmpty { "없음" },
                ),
            )
        }
        out.appendLine()

        // 생존 후보 이웃 전개 — 자기별칭 여부까지.
        for ((name, p) in survivors()) {
            out.appendLine("### $name 이웃 전개")
            out.appendLine()
            out.appendLine("| 이웃 | 중앙 %p | 양수 | 거래 | G1+G5 | 자기별칭 |")
            out.appendLine("|---|---|---|---|---|---|")
            val fp = select.getValue(p).fingerprint
            val d0 = deltas(p)
            out.appendLine("| **자신** | %+.2f | %d/8 | %d | — | — |".format(SwingMetrics.median(d0), d0.count { it > 0 }, select.getValue(p).trades))
            for (n in grid.neighbours(p)) {
                val d = deltas(n)
                out.appendLine(
                    "| %s | %+.2f | %d/8 | %d | %s | %s |".format(
                        axisDir(p, n), SwingMetrics.median(d), d.count { it > 0 }, select.getValue(n).trades,
                        if (n in g1Pass) "통과" else "**탈락**",
                        if (select.getValue(n).fingerprint == fp) "예" else "—",
                    ),
                )
            }
            out.appendLine()
        }

        // 2. 축·방향별 전역 통과율 — 출발 좌표가 G1+G5 를 통과한 경우로 한정한다.
        out.appendLine("## 2. 축·방향별 전역 통과율 (출발 좌표가 G1+G5 통과인 이웃만)")
        out.appendLine()
        out.appendLine("\"보유 연장·MA50 은 원래 나쁜 방향\" 이 A 국소 사실인지 그리드 전역 사실인지 가른다.")
        out.appendLine()
        out.appendLine("> ⚠️ **↑ 과 ↓ 의 통과율을 서로 비교하지 말 것.** 출발점을 G1+G5 통과 집합으로 한정하고 도착점의 통과를 세므로,")
        out.appendLine("> 양 끝이 모두 통과인 간선이 ↑ 로 한 번·↓ 로 한 번 세어져 **분자가 항등적으로 같아진다**(아래 표에서 확인된다).")
        out.appendLine("> 따라서 ↑/↓ 차이는 방향의 좋고 나쁨이 아니라 **통과 집합이 축의 어느 끝에 몰려 있는지**를 나타낸다.")
        out.appendLine("> 유효한 읽기는 **한 방향의 감쇠율**이다 — 예: 필터↑ 576 → 2 (0.3%), 보유↑ 507 → 269 (53.1%).")
        out.appendLine()
        val axisTotal = HashMap<String, Int>()
        val axisPass = HashMap<String, Int>()
        for (p in g1Pass) {
            for (n in grid.neighbours(p)) {
                val key = axisDir(p, n)
                axisTotal[key] = (axisTotal[key] ?: 0) + 1
                if (n in g1Pass) axisPass[key] = (axisPass[key] ?: 0) + 1
            }
        }
        out.appendLine("| 축·방향 | 이웃 수 | G1+G5 통과 | 통과율 |")
        out.appendLine("|---|---|---|---|")
        for (key in axisTotal.keys.sorted()) {
            val t = axisTotal.getValue(key)
            val c = axisPass[key] ?: 0
            out.appendLine("| %s | %d | %d | %.1f%% |".format(key, t, c, c.toDouble() / t * 100))
        }
        out.appendLine()

        // 3. 이웃 수(분모)별 G3 통과율 — 모서리 좌표가 유리한가.
        out.appendLine("## 3. 이웃 수(분모)별 G3 통과율 — G1+G5 통과 좌표만")
        out.appendLine()
        val byN = g1Pass.groupBy { grid.neighbours(it).size }
        out.appendLine("| 이웃 수 | 좌표 수 | G3 통과 | 통과율 | 평균 plateau 비율 |")
        out.appendLine("|---|---|---|---|---|")
        for (n in byN.keys.sorted()) {
            val ps = byN.getValue(n)
            val ratios = ps.map { p -> grid.neighbours(p).count { it in g1Pass }.toDouble() / n }
            val passed = ratios.count { it >= StrategySearchGates.PLATEAU_MIN_RATIO }
            out.appendLine("| %d | %d | %d | %.1f%% | %.3f |".format(n, ps.size, passed, passed.toDouble() / ps.size * 100, ratios.average()))
        }
        out.appendLine()
        run {
            val ratios = g1Pass.map { p -> grid.neighbours(p).let { ns -> if (ns.isEmpty()) 0.0 else ns.count { it in g1Pass }.toDouble() / ns.size } }
            val passed = ratios.count { it >= StrategySearchGates.PLATEAU_MIN_RATIO }
            out.appendLine("전체: G1+G5 통과 ${ratios.size} 중 G3 통과 $passed (%.1f%%), plateau 비율 중앙 %.3f".format(passed.toDouble() / ratios.size * 100, SwingMetrics.median(ratios)))
            out.appendLine()
        }

        // 4. 효과 축 해상도 — 그리드 밖 트레일링 값에서 A 의 우위가 매끄럽게 변하는가.
        out.appendLine("## 4. 효과 축(트레일링) 스캔 — 능선인가 칼날인가")
        out.appendLine()
        out.appendLine("A 의 나머지 축(k0.5/TP5/SL5/arm0/h1/필터off)을 고정하고 트레일링만 바꾼다. **그리드 밖 값 포함**.")
        out.appendLine()
        out.appendLine("| 트레일 | 실효 arm %  | 중앙 %p | 양수 | 거래 | G1 |")
        out.appendLine("|---|---|---|---|---|---|")
        for ((t, p) in trailScan) {
            val d = deltas(p)
            val m = select.getValue(p)
            out.appendLine(
                "| %.2f | %.3f | %+.2f | %d/8 | %d | %s |".format(
                    t, t / (1 - t / 100.0), SwingMetrics.median(d), d.count { it > 0 }, m.trades,
                    if (StrategySearchGates.g1(d)) "통과" else "탈락",
                ),
            )
        }
        out.appendLine()

        val path = Path.of("build/reports/plateau-structure-audit.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[structure] 리포트: ${path.toAbsolutePath()}")
        assertTrue(out.isNotEmpty())
    }
}
