package com.trading.bot.engine

import com.trading.bot.engine.CompletedBarCombined.Indicator
import com.trading.bot.engine.CompletedBarCombined.Mode
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * #27 J 후반 — `combined` 의 MA5>MA20·RSI14∈[30,70] 을 **완결 일봉만으로** 계산하는 변형 3셀 **사전고정** 판정.
 * 규칙 원문은 plan `2026-09-23-partial-bar-signals` `# Acceptance` 1~12(로컬, `.claude/` 아래라 git 추적 제외)이고 **이 KDoc 이 결과 전에 커밋된 사전고정 사본**이다.
 * 결과 후에는 배관 결함만 고친다(판정 수치 불변을 재실행으로 보인다). 라이브 변경 없음.
 *
 * ## 사전고정 (결과 전 커밋)
 * 1. 계기 [LiveSemanticsArm], 10창([LadderWindows.regimes]), 240분봉 공통 frame 1,500일. **주 판정 5분봉**, 15분봉 수렴, 240분 표기만.
 *    기준 = 현행 라이브(k0.5/TP5/SL5/트레일1.5/arm0/h1). 처리 = 기본 청산 팔 + 비관 트레일링 브래킷(기준도 같은 팔끼리 비교).
 *    기여 = 진입일 단위(셀 − 기준), 분모 = 기준 거래수. 이동블록 5일·B=20,000·seed 20260909·draw 공통([PairedMaxTBootstrap.resampleSums] 1회)·단일단계 maxT.
 * 2. family 3셀([CELLS]): MA 완결 · RSI 완결 · 둘 다 완결. 돌파선은 항상 부분봉 포함 window.
 * 3. **판정 null** = 셀이 완결로 바꾸는 지표 자리에서 부분 조건을 `D(m, d⊕k_s)` 로 XOR — `D = [C(d) ≠ C(d+1)]`(전이일 마스크; 마지막 평가일은 다음 평가일이 없어 전이를 정의하지 않고 false),
 *    `C` = 일봉 d−49..d−1 완결 조건([CompletedBarGate.build]), `d⊕k` = 같은 마켓 150일 목록 순환 이동, `k_s = 17+3(s−1)`(s=1..20), BOTH 는 같은 k.
 *    5분봉에서 20×3 = 60 (seed, cell), seed 별 3셀 maxT → 통과 수 `N0` > [NULL_MAX_PASSES](6) 이면 계기 무효·후보 0.
 *    **진단 null**(판정 외) = 완결 조건 값을 k_s 이동해 그대로 쓴다(수준 이동).
 * 4. 통과 = 5분 maxT 동시 95% 하한 > 0.
 * 5. 후보 = (a) 통과 (b) 15분 격차 > 0 ∧ g5 ≥ 0.8·g15 (c) g5 ≥ [ECONOMIC_FLOOR] %p/기준거래 (d) 5분 기여 1위 이름(관측, 동률은 이름순)을 셀·기준 양쪽에서 뺀 기여의
 *    `PairedMaxTBootstrap.interval(..).lowerBound` = g − q₁·se > 0 (q₁ = 1셀 T 의 95% 분위 — **단측** 95% 하한, 같은 draw)
 *    (e) 노출 정규화(Σpnl/Σ보유일) 격차 부호 = 격차 부호 (f) 비관 브래킷 5분 maxT 통과 (g) N0 ≤ 6. marginal = marginal p < 0.05 ∧ 미통과.
 * 6. 결과 전 예측(판정 외): MA 완결 진입 감소, RSI 완결 진입 증가, BOTH ≈ 합. 검정력 낮음 — 후보 0 이 가장 가능성 높다.
 * 7. 배관(깨지면 중단): (a) 기준 핀 (b) 항등 셀 = 기준 (c) 셀이 계산한 완결 값 == 사전계산 C, 같은 날 값 불변 (d) 전부-false 마스크 null = 기준
 *    (e) 이동 마스크 true 수 보존 (f) (마켓, 진입일) 유일 (g) 원형 이동량 ≥ 15 (h) seed 별 마스크 일치율·판정 변경 수 기록.
 *
 * 실행: `RUN_COMPLETED_BAR=true BACKTEST_CACHE_DIR=~/.cache/coin-trading-bot/backtest-cache ./gradlew :bot:test --tests "*CompletedBarSignalTest*" --rerun-tasks`.
 * smoke: `CB_UNITS=240` — 통과·null 유효·판정 문장을 내지 않는다(사전고정 커밋 뒤에만 쓴다).
 */
class CompletedBarSignalTest {

    private val props = TradingProperties()
    private val base: TradingStrategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }
    private val config = StrategySearchGrid.currentLivePoint().toConfig()

    private data class Cell(val label: String, val ma: Mode, val rsi: Mode) {
        val completed get() = listOfNotNull(Indicator.MA.takeIf { ma == Mode.COMPLETED }, Indicator.RSI.takeIf { rsi == Mode.COMPLETED })
        fun asNull(mode: Mode) = (if (ma == Mode.COMPLETED) mode else Mode.PARTIAL) to (if (rsi == Mode.COMPLETED) mode else Mode.PARTIAL)
    }

    private enum class Arm(val pessimistic: Boolean) { PRIMARY(false), PESSIMISTIC(true) }

    private class Run(val byWindow: Map<String, List<LiveSemanticsArm.Trade>>, val strategies: List<CompletedBarCombined> = emptyList()) {
        val all get() = byWindow.values.flatten()
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_COMPLETED_BAR", matches = "true")
    fun `pre-registered completed-bar MA and RSI variants on the 5-minute instrument`() = runBlocking {
        val units = System.getenv("CB_UNITS")?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim().toInt() } ?: UNITS
        val smoke = units != UNITS
        val levels = LadderWindows.load(if (units.first() == 240) units else listOf(240) + units)
        val frame = LadderWindows.frame(levels.first())
        assertEquals(EXPECTED_FRAME_DAYS, frame.size, "공통 frame 일수")
        val P = levels.last().unit
        val R = levels.dropLast(1).lastOrNull { it.unit != 240 }?.unit
        val lvP = levels.last()

        // ── 사전계산 게이트: (창 dir, 마켓) → 150일 완결 조건 ──
        val gates = HashMap<Pair<String, String>, CompletedBarGate.MarketGate>()
        for (w in lvP.windows) for ((market, newestFirst) in w.daily) {
            val g = CompletedBarGate.build(newestFirst.reversed())
            assertEquals(LadderWindows.EXPECTED_DAYS_PER_WINDOW, g.days.size, "${w.label} $market 평가 가능 거래일")
            gates[w.dir to market] = g
        }
        for (k in CompletedBarGate.SHIFTS) assertTrue(minOf(k % 150, 150 - k % 150) >= 15, "원형 이동량 $k < 15 (7g)")

        // ── 실행 ──
        suspend fun execute(level: LadderWindows.Level, arm: Arm, strategyFor: (LadderWindows.Window) -> TradingStrategy): Run {
            val byWindow = LinkedHashMap<String, List<LiveSemanticsArm.Trade>>()
            val used = ArrayList<CompletedBarCombined>()
            for (w in level.windows) {
                val s = strategyFor(w)
                if (s is CompletedBarCombined) used += s
                val out = ArrayList<LiveSemanticsArm.Trade>()
                for ((market, newestFirst) in w.daily) {
                    out += LiveSemanticsArm.run(market, s, newestFirst.reversed(), w.intraday.getValue(market).reversed(), config, props, pessimisticTrailing = arm.pessimistic)
                }
                byWindow[w.dir] = out
            }
            return Run(byWindow, used)
        }
        fun gate(w: LadderWindows.Window, market: String) = gates.getValue(w.dir to market)
        /** 이동한 마스크 조회 — [valueOf] 가 (게이트, 지표) → 150일 배열을 준다. */
        fun lookup(w: LadderWindows.Window, k: Int, valueOf: (CompletedBarGate.MarketGate, Indicator) -> BooleanArray): (Indicator, String, String) -> Boolean {
            val cache = HashMap<Pair<String, Indicator>, BooleanArray>()
            return { ind, market, day ->
                val g = gate(w, market)
                val arr = cache.getOrPut(market to ind) { CompletedBarGate.shifted(valueOf(g, ind), k) }
                arr[g.index(day) ?: error("${w.label} $market $day: 평가 가능 거래일 밖 — 배관 오류")]
            }
        }
        val transitionOf: (CompletedBarGate.MarketGate, Indicator) -> BooleanArray = { g, ind -> CompletedBarGate.transitions(g.values.getValue(ind)) }
        val valueOf: (CompletedBarGate.MarketGate, Indicator) -> BooleanArray = { g, ind -> g.values.getValue(ind) }

        // 기준 먼저 — 핀이 깨지면 나머지 실행 전에 멈춘다(7a).
        val baseRuns = HashMap<Pair<Int, Arm>, Run>()
        for (level in levels) for (arm in Arm.values()) baseRuns[level.unit to arm] = execute(level, arm) { base }
        fun baseOf(unit: Int, arm: Arm = Arm.PRIMARY) = baseRuns.getValue(unit to arm)
        for ((unit, pin) in PRIOR_BASELINE) {
            if (levels.none { it.unit == unit }) continue
            val t = baseOf(unit).all
            assertEquals(pin.first, t.size, "$unit 분 기준 거래수가 핀과 다르다 — 계기 drift, 판정 중단 (7a)")
            assertTrue(abs(t.sumOf { it.netPnlPct } - pin.second) <= 0.05, "$unit 분 기준 Σpnl %.2f ≠ 핀 %.2f (7a)".format(t.sumOf { it.netPnlPct }, pin.second))
        }
        // 7(b) 항등 셀 · 7(d) 실제 null 조회 경로(lookup → gate → index → shifted)를 타는 전부-false 마스크 — 둘 다 기준과 같아야 한다.
        assertEquals(baseOf(P).byWindow, execute(lvP, Arm.PRIMARY) { CompletedBarCombined(Mode.PARTIAL, Mode.PARTIAL) }.byWindow, "항등 셀 ≠ 기준 (7b)")
        val allFalseOf: (CompletedBarGate.MarketGate, Indicator) -> BooleanArray = { g, _ -> BooleanArray(g.days.size) }
        assertEquals(baseOf(P).byWindow, execute(lvP, Arm.PRIMARY) { w -> CompletedBarCombined(Mode.FLIPPED, Mode.FLIPPED, lookup(w, 0, allFalseOf)) }.byWindow, "전부-false 마스크 null ≠ 기준 (7d)")

        val cellRuns = HashMap<Triple<Int, Cell, Arm>, Run>()
        for (level in levels) for (arm in Arm.values()) for (c in CELLS) {
            cellRuns[Triple(level.unit, c, arm)] = execute(level, arm) { CompletedBarCombined(c.ma, c.rsi) }
            println("[completed-bar] ${level.unit}m ${c.label} $arm: ${cellRuns.getValue(Triple(level.unit, c, arm)).all.size}건")
        }
        // 7(c) 셀이 계산한 완결 값 == 사전계산 C. 진입한 거래는 그날 완결 지표를 반드시 평가했어야 하므로 키 존재도 단정한다(공허한 통과 방지).
        var crossChecked = 0
        for (level in levels) for (c in CELLS) for (arm in Arm.values()) {
            val run = cellRuns.getValue(Triple(level.unit, c, arm))
            assertEquals(level.windows.size, run.strategies.size, "${level.unit}m ${c.label}: 창별 전략 인스턴스 수 (7c)")
            for ((w, s) in level.windows.zip(run.strategies)) {
                assertEquals(0, s.inconsistent, "${level.unit}m ${c.label} ${w.label}: 같은 날 완결 값이 바뀌었다 (7c)")
                for ((key, v) in s.observed) {
                    val (market, day, ind) = key
                    val g = gates.getValue(w.dir to market)
                    assertEquals(g.values.getValue(ind)[g.index(day) ?: error("$day 평가일 밖 (7c)")], v, "${level.unit}m ${c.label} $key: 사전계산 C 불일치 (7c)")
                    crossChecked++
                }
                for (t in run.byWindow.getValue(w.dir)) for (ind in c.completed) {
                    assertTrue(Triple(t.market, t.entryDate, ind) in s.observed, "${level.unit}m ${c.label} ${t.market} ${t.entryDate}: 진입했는데 $ind 완결 값 기록 없음 (7c)")
                }
            }
            for ((dir, ts) in run.byWindow) assertEquals(ts.size, ts.map { it.market to it.entryDate }.toSet().size, "${level.unit}m ${c.label} $dir: (마켓, 진입일) 중복 (7f)")
        }
        assertTrue(crossChecked > 0, "7(c) 대조가 0건 — 기록 경로 회귀")
        for (g in gates.values) for (ind in Indicator.values()) {
            val d = CompletedBarGate.transitions(g.values.getValue(ind))
            for (k in CompletedBarGate.SHIFTS) assertEquals(d.count { it }, CompletedBarGate.shifted(d, k).count { it }, "이동 마스크 true 수 (7e)")
        }

        val nullRuns = HashMap<Pair<Int, Cell>, Run>()
        val diagRuns = HashMap<Pair<Int, Cell>, Run>()
        for ((s0, k) in CompletedBarGate.SHIFTS.withIndex()) {
            val s = s0 + 1
            for (c in CELLS) {
                val (nm, nr) = c.asNull(Mode.FLIPPED)
                nullRuns[s to c] = execute(lvP, Arm.PRIMARY) { w -> CompletedBarCombined(nm, nr, lookup(w, k, transitionOf)) }
                val (dm, dr) = c.asNull(Mode.SUPPLIED)
                diagRuns[s to c] = execute(lvP, Arm.PRIMARY) { w -> CompletedBarCombined(dm, dr, lookup(w, k, valueOf)) }
            }
            println("[completed-bar] null seed $s (k=$k) 완료")
        }

        // ── 기여·부트스트랩 ──
        fun baseCount(unit: Int) = baseOf(unit).all.size
        fun contrib(level: LadderWindows.Level, run: Run, arm: Arm = Arm.PRIMARY, filter: (LadderWindows.Window) -> Boolean = { true }, dropMarket: String? = null): DoubleArray {
            fun strip(m: Map<String, List<LiveSemanticsArm.Trade>>) = if (dropMarket == null) m else m.mapValues { (_, ts) -> ts.filter { it.market != dropMarket } }
            return LadderWindows.contributions(frame, level, strip(run.byWindow), strip(baseOf(level.unit, arm).byWindow), filter)
        }
        fun marketGaps(run: Run): Map<String, Double> {
            val gap = HashMap<String, Double>()
            for (t in run.all) gap.merge(t.market, t.netPnlPct, Double::plus)
            for (t in baseOf(P).all) gap.merge(t.market, -t.netPnlPct, Double::plus)
            return gap
        }
        val baseNegWindows = lvP.windows.filter { w -> baseOf(P).byWindow.getValue(w.dir).sumOf { it.netPnlPct } < 0 }.map { it.dir }.toSet()
        val groups = ArrayList<DoubleArray>()
        val index = HashMap<String, Int>()
        fun put(key: String, arr: DoubleArray) { index[key] = groups.size; groups += arr }
        val topName = HashMap<Cell, String>()
        for (level in levels) for ((i, c) in CELLS.withIndex()) for (arm in Arm.values()) {
            put("${level.unit}/$arm/$i", contrib(level, cellRuns.getValue(Triple(level.unit, c, arm)), arm))
        }
        for ((i, c) in CELLS.withIndex()) {
            val run = cellRuns.getValue(Triple(P, c, Arm.PRIMARY))
            val top = marketGaps(run).entries.sortedBy { it.key }.maxBy { it.value }.key
            topName[c] = top
            put("lono/$i", contrib(lvP, run, dropMarket = top))
            put("neg/$i", contrib(lvP, run, filter = { it.dir in baseNegWindows }))
            put("pos/$i", contrib(lvP, run, filter = { it.dir !in baseNegWindows }))
            if (R != null) {
                val aP = groups[index.getValue("$P/${Arm.PRIMARY}/$i")]; val aR = groups[index.getValue("$R/${Arm.PRIMARY}/$i")]
                put("d/$i", DoubleArray(frame.size) { aP[it] / baseCount(P) - 0.8 * aR[it] / baseCount(R) })
            }
        }
        for (s in 1..CompletedBarGate.SHIFTS.size) for ((i, c) in CELLS.withIndex()) {
            put("null/$s/$i", contrib(lvP, nullRuns.getValue(s to c)))
            put("diag/$s/$i", contrib(lvP, diagRuns.getValue(s to c)))
        }
        val sums = PairedMaxTBootstrap.resampleSums(frame, groups)
        fun family(keys: List<String>): PairedMaxTBootstrap.Family {
            val idx = keys.map { index.getValue(it) }
            return PairedMaxTBootstrap.maxT(DoubleArray(idx.size) { groups[idx[it]].sum() }, idx.map { sums[it] }.toTypedArray())
        }
        fun interval(key: String) = PairedMaxTBootstrap.interval(groups[index.getValue(key)].sum(), sums[index.getValue(key)])
        val families = HashMap<Pair<Int, Arm>, PairedMaxTBootstrap.Family>()
        for (level in levels) for (arm in Arm.values()) families[level.unit to arm] = family(CELLS.indices.map { "${level.unit}/$arm/$it" })
        val nullFamilies = (1..CompletedBarGate.SHIFTS.size).associateWith { s -> family(CELLS.indices.map { "null/$s/$it" }) }
        val diagFamilies = (1..CompletedBarGate.SHIFTS.size).associateWith { s -> family(CELLS.indices.map { "diag/$s/$it" }) }
        val n0 = nullFamilies.values.sumOf { f -> f.cells.count { it.pass } }
        val n0diag = diagFamilies.values.sumOf { f -> f.cells.count { it.pass } }
        val nullValid = n0 <= NULL_MAX_PASSES
        fun cellOf(unit: Int, i: Int, arm: Arm = Arm.PRIMARY) = families.getValue(unit to arm).cells[i]
        fun perTrade(unit: Int, i: Int) = cellOf(unit, i).g / baseCount(unit)

        // ── 진단: 노출·진입 집합 분해·판정 변경 수·불일치율 ──
        fun heldDays(t: LiveSemanticsArm.Trade, w: LadderWindows.Window) = maxOf(1, w.tradingDays.indexOf(t.exitDate) - w.tradingDays.indexOf(t.entryDate))
        fun exposure(run: Run): Double {
            var pnl = 0.0; var held = 0
            for (w in lvP.windows) for (t in run.byWindow.getValue(w.dir)) { pnl += t.netPnlPct; held += heldDays(t, w) }
            return if (held == 0) 0.0 else pnl / held
        }
        data class Decomp(val removed: Int, val removedPnl: Double, val added: Int, val addedPnl: Double, val refilled: Int, val refillDelta: Double, val refillPnlChange: Double, val kept: Int)
        fun decompose(run: Run): Decomp {
            val b = baseOf(P).all.associateBy { it.market to it.entryDate }
            val c = run.all.associateBy { it.market to it.entryDate }
            val removed = b.keys - c.keys; val added = c.keys - b.keys
            val both = b.keys intersect c.keys
            val refilled = both.filter { abs(b.getValue(it).entryPrice - c.getValue(it).entryPrice) > 1e-12 || b.getValue(it).netPnlPct != c.getValue(it).netPnlPct }
            return Decomp(
                removed.size, removed.sumOf { b.getValue(it).netPnlPct }, added.size, added.sumOf { c.getValue(it).netPnlPct },
                refilled.size, if (refilled.isEmpty()) 0.0 else refilled.map { (c.getValue(it).entryPrice - b.getValue(it).entryPrice) / b.getValue(it).entryPrice * 100 }.average(),
                refilled.sumOf { c.getValue(it).netPnlPct - b.getValue(it).netPnlPct }, both.size - refilled.size,
            )
        }
        fun changes(run: Run): Int {
            val b = baseOf(P).all.map { it.market to it.entryDate }.toSet(); val c = run.all.map { it.market to it.entryDate }.toSet()
            return (b - c).size + (c - b).size
        }
        fun maskAgreement(k: Int, cell: Cell): Double {
            var same = 0; var total = 0
            for (g in gates.values) for (ind in cell.completed) {
                val d = CompletedBarGate.transitions(g.values.getValue(ind)); val sd = CompletedBarGate.shifted(d, k)
                for (i in d.indices) { total++; if (d[i] == sd[i]) same++ }
            }
            return same.toDouble() / total
        }

        // ── 후보 판정 5 — 기준별 값은 통과 여부와 무관하게 모든 셀에 계산해 보고한다 ──
        data class Criteria(
            val pass: Boolean, val converged: Boolean?, val gP: Double, val gR: Double?, val floor: Boolean,
            val lonoLower: Double, val exposureGap: Double, val exposureAgrees: Boolean, val bracket: Boolean,
        ) {
            val candidate get() = pass && converged == true && floor && lonoLower > 0 && exposureAgrees && bracket
        }
        val criteria = CELLS.withIndex().associate { (i, c) ->
            val p = cellOf(P, i); val run = cellRuns.getValue(Triple(P, c, Arm.PRIMARY))
            val gP = perTrade(P, i); val gR = R?.let { perTrade(it, i) }
            val eg = exposure(run) - exposure(baseOf(P))
            c to Criteria(
                pass = p.pass, converged = if (gR == null || gR <= 0) null else gP >= 0.8 * gR, gP = gP, gR = gR, floor = gP >= ECONOMIC_FLOOR,
                lonoLower = interval("lono/$i").lowerBound, exposureGap = eg, exposureAgrees = (eg > 0) == (p.g > 0), bracket = cellOf(P, i, Arm.PESSIMISTIC).pass,
            )
        }
        fun candidate(c: Cell) = criteria.getValue(c).candidate && nullValid
        fun mark(b: Boolean?) = when (b) { true -> "✓"; false -> "✗"; null -> "미정의" }

        // ── 리포트 9 ──
        val out = StringBuilder()
        out.appendLine("# 완결 봉 MA/RSI 변형 사전고정 판정 (#27 J 후반)")
        out.appendLine()
        if (smoke) out.appendLine("**SMOKE — 판정 아님** (`CB_UNITS=${units.joinToString(",")}`)").also { out.appendLine() }
        out.appendLine("계기 `LiveSemanticsArm`, 10창, frame ${frame.size}일, 기여 = 진입일 단위, 이동블록 5일, B=20000, 단일단계 maxT. 규칙은 KDoc·plan `# Acceptance`.")
        out.appendLine()
        out.appendLine("## 1. 기준선")
        out.appendLine("| rung | 기본 팔 거래 | Σpnl | 비관 팔 거래 | Σpnl |")
        out.appendLine("|---|---|---|---|---|")
        for (level in levels) out.appendLine("| ${level.unit}m | ${baseOf(level.unit).all.size} | %+.2f | ${baseOf(level.unit, Arm.PESSIMISTIC).all.size} | %+.2f |".format(baseOf(level.unit).all.sumOf { it.netPnlPct }, baseOf(level.unit, Arm.PESSIMISTIC).all.sumOf { it.netPnlPct }))
        out.appendLine()
        out.appendLine("배관: 항등 셀·전부-false null(실제 조회 경로) = 기준 ✅ · 완결 값 대조 ${crossChecked}건 불일치 0·진입 거래 기록 존재 ✅ · (마켓, 진입일) 유일 ✅ · 이동량 ≥ 15 ✅")
        out.appendLine()
        out.appendLine("## 2. 셀 — 격차 Σ%p · /기준거래 · 동시 하한 · marginal p · 통과")
        out.appendLine("| 셀 | rung | 진입 N | 격차 Σ | /기준거래 | 동시 95% 하한 | marginal p | 통과 | 비관 격차 | 비관 통과 |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        for ((i, c) in CELLS.withIndex()) for (level in levels) {
            val p = cellOf(level.unit, i); val pp = cellOf(level.unit, i, Arm.PESSIMISTIC)
            out.appendLine("| ${c.label} | ${level.unit}m | ${cellRuns.getValue(Triple(level.unit, c, Arm.PRIMARY)).all.size} | %+.1f | %+.3f | %+.1f | %.4f | %s | %+.1f | %s |".format(
                p.g, p.g / baseCount(level.unit), p.lowerBound, p.marginalP, if (smoke) "(smoke)" else if (p.pass) "**통과**" else "—", pp.g, if (smoke) "(smoke)" else if (pp.pass) "통과" else "—"))
        }
        out.appendLine()
        for (level in levels) out.appendLine("family q (${level.unit}분 기본 팔) = %.3f · 비관 팔 = %.3f".format(families.getValue(level.unit to Arm.PRIMARY).q, families.getValue(level.unit to Arm.PESSIMISTIC).q))
        out.appendLine()
        out.appendLine("### 창별 5분 격차 Σ%p (보고만 — 판정에 쓰지 않음)")
        out.appendLine("| 창 | 기준 Σ | " + CELLS.joinToString(" | ") { it.label } + " |")
        out.appendLine("|---|---|" + CELLS.joinToString("") { "---|" })
        for (w in lvP.windows) {
            val b = baseOf(P).byWindow.getValue(w.dir).sumOf { it.netPnlPct }
            out.appendLine("| ${w.label} | %+.1f | ".format(b) + CELLS.joinToString(" | ") { "%+.1f".format(cellRuns.getValue(Triple(P, it, Arm.PRIMARY)).byWindow.getValue(w.dir).sumOf { t -> t.netPnlPct } - b) } + " |")
        }
        out.appendLine()
        out.appendLine("## 3. 5분 진입 집합 분해 (기준 대비, (마켓, 진입일) 키)")
        out.appendLine("| 셀 | 제거 N · 기준 Σpnl | 추가 N · Σpnl | 같은 날 다른 체결 N · 체결가 Δ% 평균 · Σpnl 변화 | 유지 N |")
        out.appendLine("|---|---|---|---|---|")
        for (c in CELLS) {
            val d = decompose(cellRuns.getValue(Triple(P, c, Arm.PRIMARY)))
            out.appendLine("| ${c.label} | ${d.removed} · %+.1f | ${d.added} · %+.1f | ${d.refilled} · %+.3f · %+.1f | ${d.kept} |".format(d.removedPnl, d.addedPnl, d.refillDelta, d.refillPnlChange))
        }
        out.appendLine()
        out.appendLine("## 4. 진단 (판정 외)")
        out.appendLine("완결 vs 부분 불일치율 = 그 지표가 (마켓, 날) 에서 **처음 평가된 봉**의 부분 조건 ≠ 완결 조건 비율. `combined` 은 MA 통과 뒤에만 RSI 를 보므로 RSI 의 첫 평가는 MA 를 통과한 첫 봉이다.")
        out.appendLine()
        out.appendLine("| 셀 | 노출 격차(%/보유일) | 이름 +/전체(참고) | 음수 기준 창(${baseNegWindows.size}) 격차 | 양수 창 격차 | 불일치율 MA | 불일치율 RSI | 15분 수렴 d 2.5% 백분위 |")
        out.appendLine("|---|---|---|---|---|---|---|---|")
        for ((i, c) in CELLS.withIndex()) {
            val run = cellRuns.getValue(Triple(P, c, Arm.PRIMARY))
            val gaps = marketGaps(run)
            fun disagree(ind: Indicator): String {
                val first = run.strategies.flatMap { s -> s.firstEvaluation.filterKeys { it.third == ind }.values }
                return if (first.isEmpty()) "—" else "%.3f (n=${first.size})".format(first.count { it.first != it.second }.toDouble() / first.size)
            }
            out.appendLine("| ${c.label} | %+.4f | ${gaps.values.count { it > 0 }}/${gaps.size} | %+.1f | %+.1f | ${disagree(Indicator.MA)} | ${disagree(Indicator.RSI)} | %s |".format(
                criteria.getValue(c).exposureGap, groups[index.getValue("neg/$i")].sum(), groups[index.getValue("pos/$i")].sum(),
                if (R != null) "%+.4f".format(interval("d/$i").ciLow) else "—"))
        }
        out.appendLine()
        out.appendLine("## 5. null 대조군 (5분)")
        out.appendLine(if (smoke) "(smoke — null 판정 생략)" else "판정 null(전이일 마스크 XOR) **N0 = $n0 / ${nullFamilies.size * CELLS.size}** (한도 $NULL_MAX_PASSES) → ${if (nullValid) "유효" else "**계기 무효**"} · 진단 null(수준 이동) N0 = $n0diag")
        out.appendLine()
        out.appendLine("| seed | k | 판정 null q | 통과 셀 | 진입 집합 대칭차(MA·RSI·BOTH) | 마스크 일치율(MA·RSI·BOTH) | 진단 null q | 진단 통과 | 진단 대칭차 |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|")
        for ((s0, k) in CompletedBarGate.SHIFTS.withIndex()) {
            val s = s0 + 1
            val f = nullFamilies.getValue(s); val fd = diagFamilies.getValue(s)
            out.appendLine("| $s | $k | %.3f | %s | %s | %s | %.3f | %s | %s |".format(f.q,
                f.cells.withIndex().filter { it.value.pass }.joinToString(",") { CELLS[it.index].label }.ifEmpty { "—" },
                CELLS.joinToString("·") { changes(nullRuns.getValue(s to it)).toString() },
                CELLS.joinToString("·") { "%.3f".format(maskAgreement(k, it)) },
                fd.q,
                fd.cells.withIndex().filter { it.value.pass }.joinToString(",") { CELLS[it.index].label }.ifEmpty { "—" },
                CELLS.joinToString("·") { changes(diagRuns.getValue(s to it)).toString() }))
        }
        out.appendLine()
        out.appendLine("셀 진입 집합 대칭차(MA·RSI·BOTH): " + CELLS.joinToString("·") { changes(cellRuns.getValue(Triple(P, it, Arm.PRIMARY))).toString() })
        out.appendLine()
        out.appendLine("## 6. 후보 판정 (a)~(g)")
        out.appendLine("| 셀 | (a) 5분 통과 | (b) 수렴 g5 / g15 | (c) g5 ≥ 0.10 | (d) ${""}1위 이름 제외 단측 하한 | (e) 노출 부호 | (f) 비관 통과 | (g) null 유효 | 결과 |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|")
        for ((i, c) in CELLS.withIndex()) {
            val k = criteria.getValue(c); val p = cellOf(P, i)
            val result = when { smoke -> "(smoke)"; candidate(c) -> "**후보**"; p.pass -> "통과·후보 아님"; p.marginalP < 0.05 -> "marginal"; else -> "—" }
            out.appendLine("| ${c.label} | ${if (smoke) "(smoke)" else mark(k.pass)} | ${mark(k.converged)} %+.3f / %s | ${mark(k.floor)} | ${topName[c]} %+.1f | ${mark(k.exposureAgrees)} | ${if (smoke) "(smoke)" else mark(k.bracket)} | ${if (smoke) "(smoke)" else mark(nullValid)} | $result |".format(
                k.gP, k.gR?.let { "%+.3f".format(it) } ?: "—", k.lonoLower))
        }
        out.appendLine()
        val candidates = CELLS.filter { candidate(it) }
        if (!smoke) out.appendLine(if (candidates.isEmpty()) "**후보 없음 — 현행 유지.**" else "**후보: ${candidates.joinToString { it.label }}** — 라이브 변경은 별도 승인.")
        val path = Path.of("build/reports/completed-bar-indicators.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println(out)
    }

    private companion object {
        val UNITS = listOf(240, 15, 5)
        val CELLS = listOf(
            Cell("MA_COMPLETED", Mode.COMPLETED, Mode.PARTIAL),
            Cell("RSI_COMPLETED", Mode.PARTIAL, Mode.COMPLETED),
            Cell("BOTH_COMPLETED", Mode.COMPLETED, Mode.COMPLETED),
        )
        const val NULL_MAX_PASSES = 6
        const val ECONOMIC_FLOOR = 0.10
        const val EXPECTED_FRAME_DAYS = 1_500
        /** 기준 핀 — `entry-set-decomposition-2026-09`·외부 게이트 재실행(2026-09-23) 과 같은 값. */
        val PRIOR_BASELINE = mapOf(240 to (1_058 to 235.93), 15 to (1_659 to -124.84), 5 to (1_767 to -219.36))
    }
}
