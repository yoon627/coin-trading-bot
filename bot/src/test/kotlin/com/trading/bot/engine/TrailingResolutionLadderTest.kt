package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 트레일링 1.5/arm0 승격(2026-09-06, `trailing-arm-finding-2026-09`)의 근거를 해상도 사다리(240 → 15 → 5분봉)로 다시 잰다(#189).
 *
 * 승격 근거는 전부 240분봉 계기였다. [ExitResolutionLadderTest] 는 그 계기의 청산 축 우위가 해상도를 올릴수록 0 으로
 * 수렴함(계기 편향 서명)을 보였다 — 트레일링은 경로 의존 게이트라 같은 편향에 노출된다.
 *
 * **사전고정(결과 전 커밋 — plan `2026-09-16-trailing-ladder` Acceptance 1)**:
 * - 기준 = 구 기준선(트레일 2.0 / arm 3, `baselinePoint()`). family 3셀 = **현행 1.5/arm0(주 셀)** · 2.0/arm0(arm 만) · 1.5/arm3(폭 만).
 * - 계기·창·frame·기여·통계량은 [ExitResolutionLadderTest] 와 같다(진입일 단위 기여, 240분 공통 frame, 이동블록 5일, maxT family).
 * - 주 판정 = 5분봉, 통과 = maxT 동시 하한 > 0. 수렴 = 5분 격차/기준거래 ≥ 0.8 × 15분(15분 > 0). 단조 = 240 ≥ 15.
 * - 브래킷 = 비관 트레일링 family(고점에 이 봉 고가 포함 — 트레일링을 더 자주 걸어 좁은 트레일링(1.5)에 불리). `entryBarStopOnClose` 는 손절 축이라 무관(SL 5 동일).
 * - 후보(= 승격 유지 근거) = 통과 ∧ 수렴·단조 ∧ 비관 family 통과 ∧ 격차/기준거래 ≥ 경제 하한.
 * - 읽는 법을 미리 정한다: (a) 후보 → 승격 근거가 5분봉에서도 선다. (b) 5분 통과지만 미수렴/비단조 → 계기 편향 서명, 승격 근거 약화 — **되돌릴 근거는 아니다**(되돌림은 별도 사전고정).
 *   (c) 5분 미통과 → 5분봉에서 우위가 잡음과 분리되지 않는다 — 같은 결론. (d) 15분 격차 ≤ 0 → 수렴 미정의, (c) 와 같이 읽는다.
 * - 배관 단정: 트레일링은 진입에 영향이 없어(`keepWinnersUntilDays=0` — 양수면 `lockedProfit` 이 폭에 의존해 갈린다) 진입 집합이 기준과 같아야 한다.
 *   주 셀의 240분 7국면(EXPANSION) 부분합은 선행 값 +110.37(기준 758건)을 ±0.01 로 재현해야 한다 — 어긋나면 계기 drift 라 판정 중단.
 * - family maxT 로만 탈락한 경우(marginal 구간은 0 위)를 (b)(c) 와 구분해 보고한다 — 다중성 보정만으로 떨어진 것을 우위 소멸로 읽지 않는다.
 *
 * 실행: `RUN_TRAILING_LADDER=true BACKTEST_CACHE_DIR=~/.cache/coin-trading-bot/backtest-cache ./gradlew :bot:test --tests "*TrailingResolutionLadderTest*" --rerun-tasks`
 */
class TrailingResolutionLadderTest {

    private val props = TradingProperties()
    private val strategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }

    private data class Cell(val label: String, val point: SweepPoint)
    private enum class Arm(val pessimistic: Boolean) { PRIMARY(false), PESSIMISTIC(true) }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_TRAILING_LADDER", matches = "true")
    fun `pre-registered resolution ladder for the trailing promotion`() = runBlocking {
        val units = System.getenv("LADDER_UNITS")?.split(",")?.map { it.trim().toInt() } ?: UNITS
        val smoke = units != UNITS // 기본 사다리(240→15→5)가 아니면 배관 smoke 로 표기 — 판정 문장을 내지 않는다
        val levels = LadderWindows.load(units)
        val base240 = levels.first()
        val frame = LadderWindows.frame(base240)
        assertEquals(EXPECTED_FRAME_DAYS, frame.size, "공통 frame 일수")
        val P = levels.last().unit
        val R = levels.dropLast(1).lastOrNull()?.unit

        // ── 실행: rung × (기준 + 3셀) × 2 처리 ──
        val runs = HashMap<Triple<Int, Cell, Arm>, Map<String, List<LiveSemanticsArm.Trade>>>()
        for (level in levels) for (cell in listOf(BASE) + CELLS) for (arm in Arm.values()) {
            runs[Triple(level.unit, cell, arm)] = LadderWindows.run(level, strategy, cell.point.toConfig(), props, pessimisticTrailing = arm.pessimistic)
            println("[trailing-resolution] ${level.unit}m ${cell.label} $arm: ${runs.getValue(Triple(level.unit, cell, arm)).values.sumOf { it.size }}건")
        }
        fun trades(unit: Int, cell: Cell, arm: Arm = Arm.PRIMARY) = runs.getValue(Triple(unit, cell, arm))
        fun all(unit: Int, cell: Cell, arm: Arm = Arm.PRIMARY) = trades(unit, cell, arm).values.flatten()
        fun baseCount(unit: Int) = all(unit, BASE).size

        // ── 배관 단정: 진입 집합 동일 · 240분 7국면 부분합 = 선행 ──
        for (level in levels) for (cell in CELLS) for (arm in Arm.values()) for (w in level.windows) {
            assertEquals(
                trades(level.unit, BASE, arm).getValue(w.dir).map(LadderWindows::keyOf).toSet(),
                trades(level.unit, cell, arm).getValue(w.dir).map(LadderWindows::keyOf).toSet(),
                "${level.unit}m ${cell.label} $arm ${w.label}: 진입 집합이 기준과 다르다 — 트레일링은 진입에 영향이 없어야 한다",
            )
        }
        run {
            val expansion = BacktestFixtures.EXPANSION_2020_2023.map { it.dir }.toSet()
            val gap7 = expansion.sumOf { d -> trades(240, PRIMARY_CELL).getValue(d).sumOf { it.netPnlPct } - trades(240, BASE).getValue(d).sumOf { it.netPnlPct } }
            val base7 = expansion.sumOf { d -> trades(240, BASE).getValue(d).size }
            // 계기·fixture·옵션이 선행과 동일하므로 정확히 재현돼야 한다 — 어긋나면 계기 drift 라 판정 중단(plan # Blockers).
            assertTrue(abs(gap7 - PRIOR_GAP_7) <= 0.01, "240분 7국면 주 셀 격차 %.2f ≠ 선행 %.2f (배관 — 계기 drift, 판정 중단)".format(gap7, PRIOR_GAP_7))
            assertEquals(PRIOR_BASE_TRADES_7, base7, "240분 7국면 기준 거래수 (배관)")
        }

        // ── 기여 · 재추출(공통 draw) · family ──
        val groups = ArrayList<DoubleArray>()
        val index = HashMap<String, Int>()
        fun put(key: String, arr: DoubleArray) { index[key] = groups.size; groups += arr }
        for (level in levels) for (arm in Arm.values()) for ((i, c) in CELLS.withIndex()) {
            put("${level.unit}/$arm/$i", LadderWindows.contributions(frame, level, trades(level.unit, c, arm), trades(level.unit, BASE, arm)))
        }
        if (R != null) for (i in CELLS.indices) {
            val aP = groups[index.getValue("$P/${Arm.PRIMARY}/$i")]; val aR = groups[index.getValue("$R/${Arm.PRIMARY}/$i")]
            put("d/$i", DoubleArray(frame.size) { aP[it] / baseCount(P) - 0.8 * aR[it] / baseCount(R) })
        }
        val sums = PairedMaxTBootstrap.resampleSums(frame, groups)
        fun family(unit: Int, arm: Arm): PairedMaxTBootstrap.Family {
            val idx = CELLS.indices.map { index.getValue("$unit/$arm/$it") }
            return PairedMaxTBootstrap.maxT(DoubleArray(CELLS.size) { groups[idx[it]].sum() }, idx.map { sums[it] }.toTypedArray())
        }
        val families = levels.associate { l -> l.unit to Arm.values().associateWith { family(l.unit, it) } }
        fun cellOf(unit: Int, arm: Arm, i: Int) = families.getValue(unit).getValue(arm).cells[i]
        fun perTrade(unit: Int, i: Int) = cellOf(unit, Arm.PRIMARY, i).g / baseCount(unit)
        fun interval(key: String) = PairedMaxTBootstrap.interval(groups[index.getValue(key)].sum(), sums[index.getValue(key)])

        // ── 수렴 · 후보 · 읽는 법 ──
        data class Final(val pass: Boolean, val converged: Boolean?, val monotone: Boolean?, val candidate: Boolean, val reading: String, val dLower: Double?)
        val finals = CELLS.withIndex().associate { (i, c) ->
            val p = cellOf(P, Arm.PRIMARY, i)
            val gP = perTrade(P, i); val gR = R?.let { perTrade(it, i) }
            val converged: Boolean? = if (gR != null && gR > 0) gP >= 0.8 * gR else null
            val monotone: Boolean? = if (gR != null && gR > 0 && levels.size >= 3) perTrade(240, i) >= gR else null
            val dLower = if (R != null) interval("d/$i").ciLow else null
            val bracket = cellOf(P, Arm.PESSIMISTIC, i).pass
            val candidate = p.pass && converged == true && monotone != false && bracket && gP >= ECONOMIC_FLOOR
            val reading = when {
                smoke -> "SMOKE — 판정 아님"
                candidate -> "(a) 승격 근거가 ${P}분봉에서도 선다"
                !p.pass -> "(c) ${P}분봉에서 우위가 잡음과 분리되지 않는다 — 승격 근거 약화(되돌릴 근거는 아니다)"
                converged == null -> "(d) ${R}분 격차 ≤ 0 — 수렴 미정의, (c) 와 같이 읽는다"
                converged == false || monotone == false -> "(b) 통과했지만 미수렴/비단조 — 계기 편향 서명, 승격 근거 약화(되돌릴 근거는 아니다)"
                !bracket -> "비관 브래킷 미통과 — 좁은 트레일링의 우위가 해상도 가정에 기댄다"
                else -> "경제 하한 미달"
            }
            c to Final(p.pass, converged, monotone, candidate, reading, dLower)
        }

        // ── 리포트 ──
        val out = StringBuilder()
        if (smoke) out.appendLine("**SMOKE — 판정 아님** (`LADDER_UNITS=${units.joinToString(",")}`)").also { out.appendLine() }
        out.appendLine("# 트레일링 승격 근거의 해상도 사다리 — ${units.joinToString(" → ")}분봉")
        out.appendLine()
        out.appendLine("기준 = 구 기준선 트레일 2.0/arm3(TP5/SL5/k0.5/h1). 셀 = 현행 1.5/arm0(주) · 2.0/arm0 · 1.5/arm3. 규칙은 결과 전에 커밋(plan `2026-09-16-trailing-ladder`). 주 판정 = ${P}분봉.")
        out.appendLine("기여 = 진입일 단위, frame = 240분봉 공통 ${frame.size}일, 블록 ${PairedMaxTBootstrap.BLOCK}일, B=${PairedMaxTBootstrap.RESAMPLES}, seed ${PairedMaxTBootstrap.SEED}, draw 는 셀·rung·family 공통.")
        out.appendLine("수렴 = ${P}분 격차/기준거래 ≥ 0.8 × ${R ?: "—"}분 값(${R ?: "—"}분 값 > 0). 단조 = 240 ≥ ${R ?: "—"}. 브래킷 = 비관 트레일링 family. 경제 하한 = ${ECONOMIC_FLOOR}%p/거래.")
        out.appendLine()
        out.appendLine("## 0. rung 별 기준선")
        out.appendLine()
        out.appendLine("| 해상도 | 기준 거래 | 기준 Σpnl %p | maxT q 주 / 비관 |")
        out.appendLine("|---|---|---|---|")
        for (level in levels) {
            val b = all(level.unit, BASE); val f = families.getValue(level.unit)
            out.appendLine("| %dm | %d | %+.2f | %.3f / %.3f |".format(level.unit, b.size, b.sumOf { it.netPnlPct }, f.getValue(Arm.PRIMARY).q, f.getValue(Arm.PESSIMISTIC).q))
        }
        out.appendLine()
        out.appendLine("## 1. 사다리 — 격차/기준거래 %p · 통과 · 수렴")
        out.appendLine()
        out.appendLine("| 셀 | " + levels.joinToString(" | ") { "${it.unit}분 (격차/거래 %p)" } + " | 수렴 | 단조 | d 95% 하한 | ${P}분 동시 하한/거래 | 비관 family(${P}분, 총 %p) | 후보 | 읽는 법 |")
        out.appendLine("|---" + "|---".repeat(levels.size) + "|---|---|---|---|---|---|---|")
        for ((i, c) in CELLS.withIndex()) {
            val f = finals.getValue(c); val q = cellOf(P, Arm.PESSIMISTIC, i)
            out.appendLine("| %s | %s | %s | %s | %s | %+.3f | %s · %+.2f | %s | %s |".format(c.label,
                levels.joinToString(" | ") { l -> val p = cellOf(l.unit, Arm.PRIMARY, i); "%+.3f%s".format(p.g / baseCount(l.unit), if (p.pass) " **통과**" else "") },
                when (f.converged) { true -> "수렴"; false -> "**미수렴**"; null -> "미정의" }, when (f.monotone) { true -> "단조"; false -> "**비단조**"; null -> "—" },
                f.dLower?.let { "%+.4f".format(it) } ?: "—", cellOf(P, Arm.PRIMARY, i).lowerBound / baseCount(P),
                if (q.pass) "통과" else "—", q.g, if (f.candidate) "**후보**" else "—", f.reading))
        }
        out.appendLine()
        out.appendLine("## 2. ${P}분봉 판정표")
        out.appendLine()
        out.appendLine("| 셀 | 거래 | 격차 %p | 격차/기준거래 | se | T | 동시95%하한 | 한계p | 통과 | marginal 95% 구간(양측 percentile) | 비관 family 격차 · T · 통과 |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for ((i, c) in CELLS.withIndex()) {
            val p = cellOf(P, Arm.PRIMARY, i); val q = cellOf(P, Arm.PESSIMISTIC, i); val m = interval("$P/${Arm.PRIMARY}/$i")
            // "marginal(단일 셀) 로는 통과했을 것" — 판정과 같은 studentized 단측 기준(marginalP < α)으로 라벨을 붙인다.
            val multiplicityOnly = !p.pass && p.marginalP < PairedMaxTBootstrap.ALPHA
            out.appendLine("| %s | %d | %+.2f | %+.3f | %.2f | %.2f | %+.2f | %.4f | %s | [%+.2f, %+.2f]%s | %+.2f · %.2f · %s |".format(
                c.label, all(P, c).size, p.g, p.g / baseCount(P), p.se, p.t, p.lowerBound, p.marginalP, if (p.pass) "**통과**" else "—",
                m.ciLow, m.ciHigh, if (multiplicityOnly) " (family 다중성으로만 탈락)" else "", q.g, q.t, if (q.pass) "통과" else "—"))
        }
        out.appendLine()
        out.appendLine("## 3. 청산 사유 구성")
        out.appendLine()
        out.appendLine("| 해상도 · 셀 | " + REASONS.joinToString(" | ") + " |")
        out.appendLine("|---" + "|---".repeat(REASONS.size) + "|")
        for (level in levels) for (c in listOf(BASE) + CELLS) {
            val t = all(level.unit, c)
            out.appendLine("| %dm %s | %s |".format(level.unit, c.label, REASONS.joinToString(" | ") { r -> val g = t.filter { it.reason == r }; "%d건 %+.1f".format(g.size, g.sumOf { it.netPnlPct }) }))
        }
        out.appendLine()
        out.appendLine("## 4. ${P}분봉 10창 × 3셀 격차 %p (판정에 쓰지 않는다)")
        out.appendLine()
        val lvP = levels.last()
        out.appendLine("| 창 | 기준 Σpnl (건) | " + CELLS.joinToString(" | ") { it.label } + " |")
        out.appendLine("|---|---" + "|---".repeat(CELLS.size) + "|")
        for (w in lvP.windows) {
            val b = trades(P, BASE).getValue(w.dir)
            out.appendLine("| %s | %+.2f (%d) | ".format(w.label, b.sumOf { it.netPnlPct }, b.size) +
                CELLS.joinToString(" | ") { c -> "%+.2f".format(trades(P, c).getValue(w.dir).sumOf { it.netPnlPct } - b.sumOf { it.netPnlPct }) } + " |")
        }
        out.appendLine()
        out.appendLine("## 240분 배관 확인")
        out.appendLine()
        val expansion = BacktestFixtures.EXPANSION_2020_2023.map { it.dir }.toSet()
        out.appendLine("주 셀의 240분 7국면(EXPANSION) 격차 합 = %+.2f (선행 `trailing-arm-finding-2026-09` %+.2f, 허용 ±0.01) · 7국면 기준 거래 %d (선행 %d).".format(
            expansion.sumOf { d -> trades(240, PRIMARY_CELL).getValue(d).sumOf { it.netPnlPct } - trades(240, BASE).getValue(d).sumOf { it.netPnlPct } }, PRIOR_GAP_7,
            expansion.sumOf { d -> trades(240, BASE).getValue(d).size }, PRIOR_BASE_TRADES_7))
        out.appendLine()
        out.appendLine("## 한계")
        out.appendLine()
        out.appendLine("- ${P}분봉도 라이브(10초 tick)보다 성기다. 수렴은 편향의 대리 검사이지 소멸 증명이 아니다 — 비관 트레일링 family 가 불리한 쪽 브래킷이다.")
        out.appendLine("- 10창은 승격 판정(7창)과 선행 판정(3창)에 이미 쓰였다 — 확증이 아니라 같은 표본의 해상도 재측정이다.")
        out.appendLine("- 창별 stratified 재추출이라 창 간 변동은 se 에 없다. 생존편향·슬리피지·호가 마찰은 계기 한계 그대로.")

        val path = Path.of("build/reports/trailing-resolution.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[trailing-resolution] 리포트: ${path.toAbsolutePath()}")
        println(out)
    }

    private companion object {
        val UNITS = listOf(240, 15, 5)
        val BASE = Cell("트레일 2.0/arm3 (구 기준선)", StrategySearchGrid.baselinePoint())
        val PRIMARY_CELL = Cell("트레일 1.5/arm0 (현행)", StrategySearchGrid.currentLivePoint())
        val CELLS = listOf(
            PRIMARY_CELL,
            Cell("트레일 2.0/arm0 (arm 만)", StrategySearchGrid.baselinePoint().copy(trailingArmPct = 0.0)),
            Cell("트레일 1.5/arm3 (폭 만)", StrategySearchGrid.baselinePoint().copy(trailingStopPct = 1.5)),
        )
        /** [ExitResolutionLadderTest] 와 같은 하한 — 선행 240분 값(+0.146%p/거래)을 보고 낮추면 사전고정이 아니다. */
        const val ECONOMIC_FLOOR = 0.10
        const val EXPECTED_FRAME_DAYS = 10 * LadderWindows.EXPECTED_DAYS_PER_WINDOW
        /** `trailing-arm-finding-2026-09` 변형 A 의 7국면 pooled 격차·기준 거래수 — 계기·fixture 동일이라 240분 rung 이 정확히 재현해야 한다. */
        const val PRIOR_GAP_7 = 110.37
        const val PRIOR_BASE_TRADES_7 = 758
        val REASONS = listOf("TRAILING_STOP", "TAKE_PROFIT", "STOP_LOSS", "TIME_EXIT", "END")
    }
}
