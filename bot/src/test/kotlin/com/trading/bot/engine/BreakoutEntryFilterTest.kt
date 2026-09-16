package com.trading.bot.engine

import com.trading.bot.engine.LiveSemanticsArm.EntryFilter
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
 * #208 — `combined` 진입 조건 재판정. 5분봉 전용 진입(되밀린 돌파, `entry-set-decomposition-2026-09` 의 (b) 709건 −882%p)을 줄이는
 * 진입 필터 family 를 **결과 전에 고정**하고 5분봉 [LiveSemanticsArm] 10창·진입일 기여·maxT 로 판정한다. 라이브 변경은 하지 않는다.
 *
 * ## 사전고정 (결과 전 커밋)
 * - 계기 = [LiveSemanticsArm], 기준 = 현행 라이브(k0.5/TP5/SL5/트레일1.5/arm0/h1, [StrategySearchGrid.currentLivePoint]) 의 기존 경로([EntryFilter.NONE]).
 *   창 = 10창([LadderWindows.regimes]), 240분봉 공통 frame 1,500일(240 은 frame·무결성 단정용 로딩만 — 진입 모집단이 달라 판정 rung 이 아니다, #190),
 *   **주 판정 = 5분봉**, 15분봉은 같은 분 단위 규칙의 수렴 검사(5분 ≥ 0.8 × 15분, 15분 > 0 — 선행 사다리 규약).
 * - family 9셀([CELLS]): 확인 지연 5·10·15분(봉 수 = ceil(분/봉길이) — 5분 rung 1·2·3봉, 15분 rung 전부 1봉) · 지연 5분 + 되밀림 포기 ·
 *   돌파선 마진 0.25·0.5·1.0% · 진입 마감 12h·18h(봉 시작, 00:00 UTC 기준). 시가 규칙([EntryFilter] KDoc)이라 봉 안 경로 가정이 없다.
 *   시가 체결은 라이브 tick 체결보다 보수적이다(편향 방향: 셀에 불리).
 * - 기여 = **진입일 단위**(셀 진입일 손익 합 − 기준 진입일 손익 합, [LadderWindows.contributions]) — 셀과 기준의 진입 집합이 달라 거래 페어링이 안 된다.
 *   분모 = 기준 거래수. 창별 이동블록 5일, B=20,000, draw 는 셀·팔·rung 공통, 단일단계 maxT(FWER 5%), rung·팔 별 family.
 * - 브래킷 = 비관 트레일링([LiveSemanticsArm.run] `pessimisticTrailing`) — 청산 편향의 반대쪽. 진입 브래킷은 두지 않는다(경로 가정 없음).
 * - **통과** = maxT 동시 95% 하한 > 0. **후보** = 통과 ∧ 격차/기준거래 ≥ [ECONOMIC_FLOOR] ∧ 비관 브래킷 family 통과 ∧ 수렴. 다중성으로만 탈락(marginal p < 0.05 ∧ 미통과)은
 *   marginal 로 구분. 창별 격차는 보고만(판정에 안 씀). 결과 전 예측: 지연·마감 셀은 (b) 유형 손실을 줄여 양수, 마진 셀은 공통 진입의 체결가 이득(+819%p)을 깎아
 *   음수 또는 0 근처, 되밀림 포기는 5분 지연보다 진입이 적고 격차는 비슷. 통과 셀의 라이브 번역형: 지연 M분 = "돌파 tick 뒤 M분 경과 시점에도 현재가 > 돌파선이면 진입",
 *   마진 m = 돌파선×(1+m), 마감 c = 거래일 시작 + c분 이후 신규 진입 금지. **통과가 나와도 라이브 변경은 별도 승인.**
 * - 배관 단정: 기준 5분·15분 = `entry-set-decomposition-2026-09` 기준선([PRIOR_BASELINE], 불일치 → 판정 중단) · 중립 필터(`EntryFilter()`)의 진입 집합 = NONE(`combined` 는 시가 ≤ 돌파선 봉을
 *   거부하므로 시가 규칙 ≡ 기존 규칙 — 깨지면 체결 규칙 이해가 틀린 것) · frame 1,500일 · 셀별 (마켓, 진입일) 유일성.
 * - 한계(사전고정): 라이브 진입 76건 중 24건은 어떤 해상도의 계기도 재현하지 않고 그중 13건이 09:00 KST 직후다(`entry-resolution-vs-live-2026-09`, #209) —
 *   시각 축 셀(마감 12h·18h)은 이 제약 하에서만 읽는다. 5분 계기 선택은 F1 동률 유보를 승계한다(같은 페이지).
 *
 * 실행: `RUN_ENTRY_FILTER=true BACKTEST_CACHE_DIR=~/.cache/coin-trading-bot/backtest-cache ./gradlew :bot:test --tests "*BreakoutEntryFilterTest*" --rerun-tasks`
 * smoke: `LADDER_UNITS=240,15` — 판정 문장을 내지 않는다.
 */
class BreakoutEntryFilterTest {

    private val props = TradingProperties()
    private val strategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }

    private data class Cell(val label: String, val filter: EntryFilter)
    private enum class Arm(val pessimistic: Boolean) { PRIMARY(false), PESSIMISTIC(true) }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_ENTRY_FILTER", matches = "true")
    fun `judge breakout entry filters on the 5-minute instrument with pre-registered rules`() = runBlocking {
        val units = System.getenv("LADDER_UNITS")?.split(",")?.map { it.trim().toInt() } ?: UNITS
        val smoke = units != UNITS
        val levels = LadderWindows.load(units)
        val frame = LadderWindows.frame(levels.first())
        assertEquals(EXPECTED_FRAME_DAYS, frame.size, "공통 frame 일수")
        val rungs = levels.filter { it.unit != 240 }
        val P = rungs.last().unit
        val R = rungs.dropLast(1).lastOrNull()?.unit
        val config = StrategySearchGrid.currentLivePoint().toConfig()

        // ── 실행: rung × (기준 + 중립 + 9셀) × 2 처리 ──
        val runs = HashMap<Triple<Int, Cell, Arm>, Map<String, List<LiveSemanticsArm.Trade>>>()
        var armRuns = 0
        for (level in rungs) for (cell in listOf(BASE, NEUTRAL) + CELLS) for (arm in Arm.values()) {
            runs[Triple(level.unit, cell, arm)] = LadderWindows.run(level, strategy, config, props, pessimisticTrailing = arm.pessimistic, entryFilter = cell.filter)
            armRuns += level.windows.sumOf { it.daily.size }
            println("[entry-filter] ${level.unit}m ${cell.label} $arm: ${runs.getValue(Triple(level.unit, cell, arm)).values.sumOf { it.size }}건")
        }
        fun trades(unit: Int, cell: Cell, arm: Arm = Arm.PRIMARY) = runs.getValue(Triple(unit, cell, arm))
        fun all(unit: Int, cell: Cell, arm: Arm = Arm.PRIMARY) = trades(unit, cell, arm).values.flatten()
        fun baseCount(unit: Int) = all(unit, BASE).size

        // ── 배관 단정 ──
        for ((unit, expected) in PRIOR_BASELINE) {
            if (rungs.none { it.unit == unit }) continue
            val t = all(unit, BASE)
            assertEquals(expected.first, t.size, "$unit 분 기준 거래수가 선행 분해와 다르다 — 계기 drift, 판정 중단")
            assertTrue(abs(t.sumOf { it.netPnlPct } - expected.second) <= 0.05, "$unit 분 기준 Σpnl %.2f ≠ 선행 %.2f — 판정 중단".format(t.sumOf { it.netPnlPct }, expected.second))
        }
        for (level in rungs) for (arm in Arm.values()) for (w in level.windows) {
            assertEquals(
                trades(level.unit, BASE, arm).getValue(w.dir).map(LadderWindows::keyOf).toSet(),
                trades(level.unit, NEUTRAL, arm).getValue(w.dir).map(LadderWindows::keyOf).toSet(),
                "${level.unit}m $arm ${w.label}: 중립 필터(시가 규칙)의 진입 집합이 기존 경로와 다르다 — combined 는 시가 ≤ 돌파선 봉을 거부해야 한다",
            )
        }
        for (level in rungs) for (cell in listOf(BASE) + CELLS) for (arm in Arm.values()) {
            val t = all(level.unit, cell, arm)
            assertEquals(t.size, t.map { it.market to it.entryDate }.toSet().size, "${level.unit}m ${cell.label} $arm: (마켓, 진입일) 이 유일하지 않다")
        }
        // 계기는 봉 길이를 연속 봉 최소 간격으로 추론한다 — 격자 밖 타임스탬프가 섞이면 지연 봉 수가 조용히 커지므로 rung 단위와 같은지 단정한다.
        for (level in rungs) for ((market, bars) in level.windows.flatMap { w -> w.intraday.entries }) {
            val sorted = bars.map { java.time.LocalDateTime.parse(it.candleDateTimeUtc) }.sorted()
            val minGap = sorted.zipWithNext { a, b -> java.time.Duration.between(a, b).toMinutes() }.filter { it > 0 }.min()
            assertEquals(level.unit.toLong(), minGap, "${level.unit}m $market: 연속 봉 최소 간격 ${minGap}분 ≠ 단위 — 지연 봉 수 추론이 어긋난다")
        }

        // ── 기여 · 공통 draw · family ──
        val groups = ArrayList<DoubleArray>()
        val index = HashMap<String, Int>()
        fun put(key: String, arr: DoubleArray) { index[key] = groups.size; groups += arr }
        for (level in rungs) for (arm in Arm.values()) for ((i, c) in CELLS.withIndex()) {
            put("${level.unit}/$arm/$i", LadderWindows.contributions(frame, level, trades(level.unit, c, arm), trades(level.unit, BASE, arm)))
        }
        val sums = PairedMaxTBootstrap.resampleSums(frame, groups)
        fun family(unit: Int, arm: Arm): PairedMaxTBootstrap.Family {
            val idx = CELLS.indices.map { index.getValue("$unit/$arm/$it") }
            return PairedMaxTBootstrap.maxT(DoubleArray(CELLS.size) { groups[idx[it]].sum() }, idx.map { sums[it] }.toTypedArray())
        }
        val families = rungs.associate { l -> l.unit to Arm.values().associateWith { family(l.unit, it) } }
        fun cellOf(unit: Int, arm: Arm, i: Int) = families.getValue(unit).getValue(arm).cells[i]
        fun perTrade(unit: Int, i: Int) = cellOf(unit, Arm.PRIMARY, i).g / baseCount(unit)

        data class Final(val converged: Boolean?, val candidate: Boolean, val reading: String)
        val finals = CELLS.withIndex().associate { (i, c) ->
            val p = cellOf(P, Arm.PRIMARY, i)
            val gP = perTrade(P, i); val gR = R?.let { perTrade(it, i) }
            val converged: Boolean? = if (gR != null && gR > 0) gP >= 0.8 * gR else null
            val bracket = cellOf(P, Arm.PESSIMISTIC, i).pass
            val candidate = p.pass && converged == true && bracket && gP >= ECONOMIC_FLOOR
            val reading = when {
                smoke -> "SMOKE — 판정 아님"
                candidate -> "후보 — 라이브 번역형 승인 대상(별도)"
                !p.pass -> if (p.marginalP < PairedMaxTBootstrap.ALPHA) "미통과(다중성으로만 — marginal)" else "미통과 — ${P}분봉에서 우위가 잡음과 분리되지 않는다"
                converged == null -> "${R}분 격차 ≤ 0 — 수렴 미정의"
                converged == false -> "통과했지만 미수렴 — 계기 편향 서명"
                !bracket -> "비관 브래킷 미통과 — 우위가 트레일링 해상도 가정에 기댄다"
                else -> "경제 하한 미달"
            }
            c to Final(converged, candidate, reading)
        }

        // ── 리포트 ──
        val out = StringBuilder()
        out.appendLine("# combined 진입 필터 사전고정 판정 — 되밀린 돌파를 막는 필터는 5분봉에서 우위가 있나 (#208)")
        out.appendLine()
        out.appendLine("기준 = 현행 라이브(k0.5/TP5/SL5/트레일1.5/arm0/h1) 기존 경로. 10창, frame ${frame.size}일, 진입일 기여, maxT B=%d 블록 %d일. 주 판정 %d분봉, 수렴 검사 %s분봉. 시가 규칙(봉 시작 값만). 규칙은 이 테스트 KDoc.".format(PairedMaxTBootstrap.RESAMPLES, PairedMaxTBootstrap.BLOCK, P, R?.toString() ?: "—"))
        out.appendLine()
        out.appendLine("## §0 rung 기준선 · 셀별 진입 수 (PRIMARY)")
        out.appendLine()
        out.appendLine("| rung | 기준 N | 기준 Σpnl | " + CELLS.joinToString(" | ") { it.label } + " |")
        out.appendLine("|---|---|---|" + CELLS.joinToString("") { "---|" })
        for (l in rungs) out.appendLine("| ${l.unit}분 | ${baseCount(l.unit)} | %+.1f | ".format(all(l.unit, BASE).sumOf { it.netPnlPct }) + CELLS.joinToString(" | ") { "${all(l.unit, it).size} (%+.1f)".format(all(l.unit, it).sumOf { t -> t.netPnlPct }) } + " |")
        out.appendLine()
        out.appendLine("## §1 ${P}분봉 판정표 (family ${CELLS.size}셀, q = %.3f · 비관 q = %.3f)".format(families.getValue(P).getValue(Arm.PRIMARY).q, families.getValue(P).getValue(Arm.PESSIMISTIC).q))
        out.appendLine()
        out.appendLine("| 셀 | 격차 Σ%%p | /기준거래 | T | 동시 95%% 하한 | marginal p | 통과 | 비관 브래킷 격차 / 통과 | ${R ?: "—"}분 /기준거래 | 수렴 | 경제 하한 ≥ %.2f | 후보 | 읽기 |".format(ECONOMIC_FLOOR))
        out.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for ((i, c) in CELLS.withIndex()) {
            val p = cellOf(P, Arm.PRIMARY, i); val b = cellOf(P, Arm.PESSIMISTIC, i); val f = finals.getValue(c)
            out.appendLine("| %s | %+.1f | %+.3f | %.2f | %+.1f | %.4f | %s | %+.1f / %s | %s | %s | %s | **%s** | %s |".format(
                c.label, p.g, perTrade(P, i), p.t, p.lowerBound, p.marginalP, if (p.pass) "통과" else "—", b.g, if (b.pass) "통과" else "—",
                R?.let { "%+.3f".format(perTrade(it, i)) } ?: "—", when (f.converged) { true -> "수렴"; false -> "미수렴"; null -> "미정의" },
                if (perTrade(P, i) >= ECONOMIC_FLOOR) "충족" else "미달", if (f.candidate) "후보" else "—", f.reading))
        }
        out.appendLine()
        if (R != null) out.appendLine("${R}분 rung 의 지연 셀은 봉 수 = ceil(분/${R}) 이라 5·10·15분이 전부 1봉(${R}분)으로 같은 규칙이 된다 — 세 셀의 ${R}분 값이 같은 이유. marginal p 는 4자리(사전고정 경계 `p < 0.05` 는 엄격 부등호).")
        out.appendLine()
        out.appendLine("## §2 셀별 진입 집합 분해 (${P}분 PRIMARY, 키 = (마켓, 진입일))")
        out.appendLine()
        out.appendLine("| 셀 | 기준 진입 중 제거 (건 · Σpnl 기준 · 건당) | 같은 날 유지 (건 · 체결가 평균 Δ% · Σpnl 셀−기준) | 셀 전용 진입 (건 · Σpnl) | 합 = 격차 |")
        out.appendLine("|---|---|---|---|---|")
        val baseP = all(P, BASE).associateBy { it.market to it.entryDate }
        for (c in CELLS) {
            val cellP = all(P, c).associateBy { it.market to it.entryDate }
            val removed = baseP.filterKeys { it !in cellP }.values
            val kept = cellP.filterKeys { it in baseP }
            val added = cellP.filterKeys { it !in baseP }.values
            val keptDelta = kept.entries.sumOf { (k, t) -> t.netPnlPct - baseP.getValue(k).netPnlPct }
            val avgFill = if (kept.isEmpty()) 0.0 else kept.entries.map { (k, t) -> (t.entryPrice - baseP.getValue(k).entryPrice) / baseP.getValue(k).entryPrice * 100 }.average()
            val gap = -removed.sumOf { it.netPnlPct } + keptDelta + added.sumOf { it.netPnlPct }
            out.appendLine("| %s | %d · %+.1f · %+.2f | %d · %+.3f · %+.1f | %d · %+.1f | %+.1f |".format(c.label, removed.size, removed.sumOf { it.netPnlPct }, if (removed.isEmpty()) 0.0 else removed.sumOf { it.netPnlPct } / removed.size, kept.size, avgFill, keptDelta, added.size, added.sumOf { it.netPnlPct }, gap))
        }
        out.appendLine()
        out.appendLine("## §3 청산 사유 구성 · 진입 봉 청산 (${P}분 PRIMARY)")
        out.appendLine()
        out.appendLine("| 셀 | " + REASONS.joinToString(" | ") + " | 진입 봉 청산 (건 · Σpnl) |")
        out.appendLine("|---|" + REASONS.joinToString("") { "---|" } + "---|")
        for (c in listOf(BASE) + CELLS) {
            val t = all(P, c)
            out.appendLine("| ${c.label} | " + REASONS.joinToString(" | ") { r -> "${t.count { it.reason == r }} (%+.1f)".format(t.filter { it.reason == r }.sumOf { it.netPnlPct }) } + " | %d · %+.1f |".format(t.count { it.exitOnEntryBar }, t.filter { it.exitOnEntryBar }.sumOf { it.netPnlPct }))
        }
        out.appendLine()
        out.appendLine("## §4 창별 격차 (${P}분 PRIMARY, Σ%p — 판정에 쓰지 않는다)")
        out.appendLine()
        val levelP = rungs.last()
        out.appendLine("| 셀 | " + levelP.windows.joinToString(" | ") { it.dir } + " | 음수 창 | 최악 창 |")
        out.appendLine("|---|" + levelP.windows.joinToString("") { "---|" } + "---|---|")
        for (c in CELLS) {
            val perWin = levelP.windows.map { w -> trades(P, c).getValue(w.dir).sumOf { it.netPnlPct } - trades(P, BASE).getValue(w.dir).sumOf { it.netPnlPct } }
            val worst = perWin.withIndex().minBy { it.value }
            out.appendLine("| ${c.label} | " + perWin.joinToString(" | ") { "%+.1f".format(it) } + " | ${perWin.count { it < 0 }}/${perWin.size} | ${levelP.windows[worst.index].dir} %+.1f |".format(worst.value))
        }
        out.appendLine()
        out.appendLine("## §5 지연 셀 진단 (${P}분 PRIMARY) — 체결 봉 − 돌파 봉 B 의 봉 수 × ${P}분(결측 봉은 세지 않으므로 벽시계의 하한; 되밀림 후 재돌파 대기와 신호 재확인이 섞여 있다)")
        out.appendLine()
        out.appendLine("| 셀 | 진입 | 지연 분 최소/중앙/최대 | 지연 0 비율 | 신호 거부로 밀린 후보 합 | 밀림 있는 진입 |")
        out.appendLine("|---|---|---|---|---|---|")
        for (c in CELLS) {
            val t = all(P, c); if (t.isEmpty()) continue
            val mins = t.map { it.entryDelayBars * P.toDouble() }.sorted()
            out.appendLine("| %s | %d | %.0f / %.0f / %.0f | %.2f | %d | %d |".format(c.label, t.size, mins.first(), mins[mins.size / 2], mins.last(), t.count { it.entryDelayBars == 0 }.toDouble() / t.size, t.sumOf { it.entrySignalDeferrals }, t.count { it.entrySignalDeferrals > 0 }))
        }
        out.appendLine()
        out.appendLine("## 배관 확인")
        out.appendLine()
        for ((unit, e) in PRIOR_BASELINE) if (rungs.any { it.unit == unit }) out.appendLine("- ${unit}분 기준 ${all(unit, BASE).size}건 Σ%+.1f = 선행 ${e.first}건 %+.1f ✅ · 중립 필터 진입 집합 = 기존 경로 ✅".format(all(unit, BASE).sumOf { it.netPnlPct }, e.second))
        out.appendLine("- frame ${frame.size}일 · 셀별 (마켓, 진입일) 유일 ✅ · 계기 실행 ${armRuns}회(마켓×창×셀×팔) · 공통 draw B=${PairedMaxTBootstrap.RESAMPLES}.")
        out.appendLine()
        out.appendLine("## 한계 (사전고정)")
        out.appendLine()
        out.appendLine("- 라이브 진입 76건 중 24건은 어떤 해상도의 계기도 재현하지 않고 그중 13건이 09:00 KST 직후다(#209 가설) — 시각 축 셀(마감 12h·18h)은 계기 안에서만 성립하는 판정이다.")
        out.appendLine("- 5분 계기 선택은 F1 동률 유보의 실용 판단을 승계한다(`entry-resolution-vs-live-2026-09`). 필터 셀의 시가 체결은 라이브 tick 체결보다 늦고 높다(셀에 불리한 편향).")
        out.appendLine("- 5분봉 결측(창 안 1.7%, AVAX 급 8%)은 지연 셀의 '봉 수' 를 벽시계보다 길게 만들 수 있다 — §5 는 봉 수 기준이라 그 크기를 보이지 못한다(하한). 마감 셀의 시각은 거래일 시작(09:00 KST) 기준 — 12h = 21:00 KST, 18h = 03:00 KST 이후 진입 금지. 생존편향·슬리피지·호가 마찰은 계기 한계 그대로.")
        if (smoke) out.appendLine("\n**SMOKE 실행(${units}) — 판정 아님.**")

        val path = Path.of("build/reports/breakout-entry-filters.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println(out)
        println("리포트: ${path.toAbsolutePath()}")
    }

    private companion object {
        val UNITS = listOf(240, 15, 5)
        val BASE = Cell("기준(기존 경로)", EntryFilter.NONE)
        val NEUTRAL = Cell("중립(시가 규칙)", EntryFilter())
        val CELLS = listOf(
            Cell("지연 5분", EntryFilter(confirmDelayMinutes = 5)),
            Cell("지연 10분", EntryFilter(confirmDelayMinutes = 10)),
            Cell("지연 15분", EntryFilter(confirmDelayMinutes = 15)),
            Cell("지연 5분+되밀림 포기", EntryFilter(confirmDelayMinutes = 5, abandonOnPullback = true)),
            Cell("마진 0.25%", EntryFilter(marginPct = 0.25)),
            Cell("마진 0.5%", EntryFilter(marginPct = 0.5)),
            Cell("마진 1.0%", EntryFilter(marginPct = 1.0)),
            Cell("마감 12h", EntryFilter(entryCutoffMinutes = 12 * 60)),
            Cell("마감 18h", EntryFilter(entryCutoffMinutes = 18 * 60)),
        )
        const val ECONOMIC_FLOOR = 0.10
        const val EXPECTED_FRAME_DAYS = 10 * LadderWindows.EXPECTED_DAYS_PER_WINDOW
        /** `entry-set-decomposition-2026-09` 기준선 — 계기 drift 검출. */
        val PRIOR_BASELINE = mapOf(15 to (1_659 to -124.8), 5 to (1_767 to -219.4))
        val REASONS = listOf("TRAILING_STOP", "TAKE_PROFIT", "STOP_LOSS", "TIME_EXIT", "END")
    }
}
