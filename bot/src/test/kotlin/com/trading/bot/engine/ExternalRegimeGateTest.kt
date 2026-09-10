package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 외부 레짐 게이트 13셀 **사전명세** 판정 — plan `2026-09-10-external-regime-gate` `# Acceptance` 1~12.
 * 계기·창·통계량은 [ExitResolutionLadderTest] 과 같다(주 판정 5분봉, 15분 수렴, 240분 표기). 처리는 `Arm.PRIMARY` 만 — 게이트는 진입만 바꾼다.
 * 셀 = [DateGatedStrategy] 로 `combined` 를 감싼 것. null 대조군 = 위상 이동 시계열(사전고정 5), 주 판정 rung 에서만.
 *
 * 실행: `RUN_EXTERNAL_GATE=true BACKTEST_CACHE_DIR=... ./gradlew :bot:test --tests "*ExternalRegimeGateTest*" --rerun-tasks`.
 * `GATE_UNITS=240` 은 배관 smoke 전용이며 산출물에 "SMOKE — 판정 아님" 이 찍힌다.
 */
class ExternalRegimeGateTest {

    private val props = TradingProperties()
    private val base: TradingStrategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == StrategySearchGrid.BASELINE_STRATEGY }
    private val config = StrategySearchGrid.currentLivePoint().toConfig()

    private class WindowData(val label: String, val dir: String, val daily: Map<String, List<Candle>>, val intraday: Map<String, List<Candle>>) {
        val tradingDays: List<String>
        val zeroBarMarketDays: Int
        val buyAndHoldMedian: Double
        init {
            val days = sortedSetOf<String>()
            var zero = 0
            val bh = ArrayList<Double>()
            for ((market, newestFirst) in daily) {
                val ch = newestFirst.reversed()
                val byDay = intraday.getValue(market).groupBy { it.candleDateTimeUtc.substring(0, 10) }
                for (i in BacktestEngine.MIN_CANDLES until ch.size) {
                    val d = ch[i].candleDateTimeKst.substring(0, 10)
                    days += d
                    if (byDay[d] == null) zero++
                }
                val start = ch[BacktestEngine.MIN_CANDLES]
                bh += (ch.last().tradePrice - start.openingPrice) / start.openingPrice * 100.0
            }
            tradingDays = days.toList(); zeroBarMarketDays = zero
            val s = bh.sorted(); buyAndHoldMedian = (s[s.size / 2] + s[(s.size - 1) / 2]) / 2
        }
        fun key(date: String) = "$dir/$date"
    }

    private class Level(val unit: Int, val windows: List<WindowData>)

    /** 한 (rung, 셀) 실행 결과 — 창별 거래와 게이트 카운터. */
    private class Run(val byWindow: Map<String, List<LiveSemanticsArm.Trade>>, val blocked: Int, val allowed: Int) {
        val all get() = byWindow.values.flatten()
        val blockRatio get() = if (blocked + allowed == 0) 0.0 else blocked.toDouble() / (blocked + allowed)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_EXTERNAL_GATE", matches = "true")
    fun `pre-registered external regime gate on combined entries`() = runBlocking {
        val regimes = BacktestFixtures.EXPANSION_2020_2023 + BacktestFixtures.TIME_INDEPENDENT
        val smoke = System.getenv("GATE_UNITS") != null
        val units = System.getenv("GATE_UNITS")?.split(",")?.map { it.trim().toInt() } ?: UNITS
        require(units.first() == 240) { "frame 은 240분봉 기준이라 목록은 240 으로 시작해야 한다" }
        for (unit in units.filter { it != 240 }) for (r in regimes) {
            require(IntradayCache.available(unit, r.dir, BacktestFixtures.markets(r))) { "${unit}분봉 캐시 부재: ${r.dir} — 부분 캐시로는 판정하지 않는다(사전고정 1)" }
        }
        val levels = units.map { unit ->
            Level(unit, regimes.map { r ->
                val daily = BacktestFixtures.loadAll(r)
                val intraday = if (unit == 240) IntradayFixtures.loadAll(r.dir, daily.keys) else IntradayCache.loadAll(unit, r.dir, daily.keys)
                WindowData(r.label, r.dir, daily, intraday)
            })
        }
        val base240 = levels.first()
        val P = levels.last().unit
        val R = levels.dropLast(1).lastOrNull()?.unit
        val regime = ExternalRegime.load()

        // ── frame(1)·단순보유 분류·결측(8b) ──
        base240.windows.forEach { assertEquals(EXPECTED_DAYS_PER_WINDOW, it.tradingDays.size, "${it.label}: 240분 frame 일수") }
        assertEquals(0, base240.windows.sumOf { it.zeroBarMarketDays }, "240분 결측 마켓-일")
        val frame = PairedMaxTBootstrap.Frame(base240.windows.map { w -> w.tradingDays.map { w.key(it) } })
        assertEquals(EXPECTED_FRAME_DAYS, frame.size, "공통 frame 일수")
        val bhNeg = base240.windows.filter { it.buyAndHoldMedian < 0 }.map { it.dir }.toSet()
        assertEquals(BH_NEG, bhNeg, "단순보유 창 분류가 선행 표와 다르다")
        val incomplete = base240.windows.flatMap { w -> w.tradingDays.filter { !regime.complete(LocalDate.parse(it)) }.map { w.key(it) } }
        assertTrue(incomplete.isEmpty(), "외부 시계열 결측(이월 ${ExternalSeries.MAX_CARRY}일 초과) ${incomplete.size}일: ${incomplete.take(5)} (8b)")
        for (s in 1..NULL_SEEDS) {
            val shifted = regime.shifted(s * NULL_SHIFT_STEP)
            assertEquals(regime.kimp.dates, shifted.kimp.dates, "위상 이동 날짜 집합 (8d)")
            assertEquals(regime.kimp.dates.map { regime.kimp.lagged(it.plusDays(1))!! }.sorted(), shifted.kimp.dates.map { shifted.kimp.lagged(it.plusDays(1))!! }.sorted(), "위상 이동 값 다중집합 (8d)")
        }

        // ── 실행 ──
        suspend fun execute(level: Level, strategy: TradingStrategy): Map<String, List<LiveSemanticsArm.Trade>> {
            val byWindow = LinkedHashMap<String, List<LiveSemanticsArm.Trade>>()
            for (w in level.windows) {
                val out = ArrayList<LiveSemanticsArm.Trade>()
                for ((market, newestFirst) in w.daily) {
                    out += LiveSemanticsArm.run(market, strategy, newestFirst.reversed(), w.intraday.getValue(market).reversed(), config, props)
                }
                byWindow[w.dir] = out
            }
            return byWindow
        }
        suspend fun gated(level: Level, allow: (LocalDate) -> Boolean): Run {
            val g = DateGatedStrategy(base, allow)
            return Run(execute(level, g), g.blockedDays.size, g.allowedDays.size)
        }
        val baseRuns = HashMap<Int, Run>()
        val allPassRuns = HashMap<Int, Run>()
        val cellRuns = HashMap<Pair<Int, ExternalRegime.Cell>, Run>()
        for (level in levels) {
            baseRuns[level.unit] = Run(execute(level, base), 0, 0)
            allPassRuns[level.unit] = gated(level) { true }
            for (cell in CELLS) {
                cellRuns[level.unit to cell] = gated(level) { regime.allows(cell, it) }
                println("[gate] ${level.unit}m ${cell.label}: ${cellRuns.getValue(level.unit to cell).all.size}건, 차단 ${"%.1f".format(100 * cellRuns.getValue(level.unit to cell).blockRatio)}%")
            }
        }
        val lvP = levels.last()
        val nullRuns = HashMap<Pair<Int, ExternalRegime.Cell>, Run>()   // (seed, cell) — 주 판정 rung 만
        for (s in 1..NULL_SEEDS) {
            val shifted = regime.shifted(s * NULL_SHIFT_STEP)
            for (cell in CELLS) nullRuns[s to cell] = gated(lvP) { shifted.allows(cell, it) }
            println("[gate] null seed $s 완료")
        }

        // ── 배관 단정 8a·8e ──
        for (level in levels) assertEquals(baseRuns.getValue(level.unit).byWindow, allPassRuns.getValue(level.unit).byWindow, "${level.unit}m ALL_PASS 거래가 기준과 다르다 (8a)")
        if (P == 5) assertEquals(PRIOR_BASE_TRADES_5M, baseRuns.getValue(5).all.size, "5분봉 기준 거래수가 선행 사다리와 다르다 (8e)")

        // ── 기여(진입일 단위) · 부트스트랩 ──
        fun contributions(level: Level, run: Run, filter: (WindowData) -> Boolean = { true }): DoubleArray {
            val byKey = HashMap<String, Double>()
            val b = baseRuns.getValue(level.unit)
            for (w in level.windows.filter(filter)) {
                for (t in run.byWindow.getValue(w.dir)) byKey.merge(w.key(t.entryDate), t.netPnlPct, Double::plus)
                for (t in b.byWindow.getValue(w.dir)) byKey.merge(w.key(t.entryDate), -t.netPnlPct, Double::plus)
            }
            return frame.align(byKey)
        }
        fun baseCount(unit: Int) = baseRuns.getValue(unit).all.size
        val n = CELLS.size
        val groups = ArrayList<DoubleArray>()
        val index = HashMap<String, Int>()
        fun put(key: String, arr: DoubleArray) { index[key] = groups.size; groups += arr }
        for (level in levels) for ((i, c) in CELLS.withIndex()) put("${level.unit}/$i", contributions(level, cellRuns.getValue(level.unit to c)))
        for ((i, c) in CELLS.withIndex()) put("$P/neg/$i", contributions(lvP, cellRuns.getValue(P to c)) { it.dir in bhNeg })
        if (R != null) for (i in CELLS.indices) {
            val aP = groups[index.getValue("$P/$i")]; val aR = groups[index.getValue("$R/$i")]
            put("d/$i", DoubleArray(frame.size) { aP[it] / baseCount(P) - 0.8 * aR[it] / baseCount(R) })
        }
        for (s in 1..NULL_SEEDS) for ((i, c) in CELLS.withIndex()) put("null/$s/$i", contributions(lvP, nullRuns.getValue(s to c)))
        val sums = PairedMaxTBootstrap.resampleSums(frame, groups)
        fun family(keys: List<String>): PairedMaxTBootstrap.Family {
            val idx = keys.map { index.getValue(it) }
            return PairedMaxTBootstrap.maxT(DoubleArray(idx.size) { groups[idx[it]].sum() }, idx.map { sums[it] }.toTypedArray())
        }
        val families = levels.associate { l -> l.unit to family(CELLS.indices.map { "${l.unit}/$it" }) }
        val family7 = levels.associate { l -> l.unit to family(REFERENCE_7.map { "${l.unit}/${CELLS.indexOf(it)}" }) }
        val nullFamilies = (1..NULL_SEEDS).associateWith { s -> family(CELLS.indices.map { "null/$s/$it" }) }
        val n0 = nullFamilies.values.sumOf { f -> f.cells.count { it.pass } }
        val nullValid = n0 <= NULL_MAX_PASSES
        fun cellOf(unit: Int, i: Int) = families.getValue(unit).cells[i]
        fun perTrade(unit: Int, i: Int) = cellOf(unit, i).g / baseCount(unit)
        fun interval(key: String) = PairedMaxTBootstrap.interval(groups[index.getValue(key)].sum(), sums[index.getValue(key)])

        // ── 진단: 보유일·노출·마켓 부호 ──
        fun heldDays(t: LiveSemanticsArm.Trade, w: WindowData): Int = maxOf(1, w.tradingDays.indexOf(t.exitDate) - w.tradingDays.indexOf(t.entryDate))
        fun windowOf(level: Level, t: LiveSemanticsArm.Trade) = level.windows.first { w -> w.daily.containsKey(t.market) && t.entryDate in w.tradingDays }
        fun exposure(level: Level, run: Run): Double { val ts = run.all; return if (ts.isEmpty()) 0.0 else ts.sumOf { it.netPnlPct } / ts.sumOf { heldDays(it, windowOf(level, it)) } }
        fun exposureGap(level: Level, run: Run) = exposure(level, run) - exposure(level, baseRuns.getValue(level.unit))
        fun marketSigns(run: Run): Pair<Int, Int> {
            val gap = HashMap<String, Double>()
            for (w in lvP.windows) {
                for (t in run.byWindow.getValue(w.dir)) gap.merge(t.market, t.netPnlPct, Double::plus)
                for (t in baseRuns.getValue(P).byWindow.getValue(w.dir)) gap.merge(t.market, -t.netPnlPct, Double::plus)
                for (m in w.daily.keys) gap.putIfAbsent(m, 0.0)
            }
            return gap.values.count { it > 0 } to gap.size
        }

        // ── 후보 판정(6) ──
        data class Final(val pass: Boolean, val candidate: Boolean, val notes: List<String>, val converged: Boolean?, val dLower: Double?, val signs: Pair<Int, Int>, val exposure: Double, val block: Double)
        val finals = CELLS.withIndex().associate { (i, c) ->
            val p = cellOf(P, i); val run = cellRuns.getValue(P to c)
            val gP = perTrade(P, i); val gR = R?.let { perTrade(it, i) }
            val converged: Boolean? = if (gR != null && gR > 0) gP >= 0.8 * gR else null
            val signs = marketSigns(run); val eg = exposureGap(lvP, run); val block = run.blockRatio
            val notes = ArrayList<String>()
            var cand = p.pass
            if (p.pass && converged != true) { notes += when { R == null -> "사다리 없음(smoke)"; converged == null -> "수렴 미정의(${R}분 격차 ≤ 0)"; else -> "미수렴" }; cand = false }
            if (p.pass && gP < ECONOMIC_FLOOR) { notes += "경제 하한 미달"; cand = false }
            if (p.pass && signs.first < ceil(MARKET_POSITIVE_SHARE * signs.second).toInt()) { notes += "마켓 다수결 미달 ${signs.first}/${signs.second}"; cand = false }
            if (p.pass && (eg > 0) != (p.g > 0)) { notes += "노출 정규화 부호 불일치"; cand = false }
            if (p.pass && (block < BLOCK_MIN || block > BLOCK_MAX)) { notes += "차단율 ${"%.0f".format(100 * block)}% 범위 밖"; cand = false }
            if (p.pass && !nullValid) { notes += "null 게이트 무효(N0=$n0)"; cand = false }
            c to Final(p.pass, cand, notes, converged, if (R != null) interval("d/$i").ciLow else null, signs, eg, block)
        }
        val ranked = CELLS.withIndex().filter { finals.getValue(it.value).candidate }
            .sortedWith(compareByDescending<IndexedValue<ExternalRegime.Cell>> { cellOf(P, it.index).lowerBound / baseCount(P) }.thenBy { it.index }).map { it.value }

        // ── 리포트(9·10) ──
        val out = StringBuilder()
        if (smoke) out.appendLine("**SMOKE — 판정 아님** (`GATE_UNITS=${units.joinToString(",")}`)").also { out.appendLine() }
        out.appendLine("# 외부 레짐 게이트 — ${units.joinToString(" → ")}분봉, 13셀 사전명세 판정")
        out.appendLine()
        out.appendLine("규칙은 결과를 보기 전에 커밋했다(plan `2026-09-10-external-regime-gate` `# Acceptance` 1~12). 기준 = 현행 라이브 TP5/SL5/트레일1.5/arm0/k0.5/h1, `combined`. 셀 = 조건이 참인 날만 진입 허용. 주 판정 = ${P}분봉.")
        out.appendLine("기여 = 진입일 단위, frame = 240분봉 공통 ${frame.size}일, 블록 ${PairedMaxTBootstrap.BLOCK}일, B=${PairedMaxTBootstrap.RESAMPLES}, seed ${PairedMaxTBootstrap.SEED}, draw 는 셀·rung·null 공통. family = ${n}(참고: 김프·펀딩 7셀 q 병기).")
        out.appendLine("null = 시계열을 s×${NULL_SHIFT_STEP}일 순환 이동(s=1..${NULL_SEEDS}) × ${n}셀 = ${NULL_SEEDS * n} 에 같은 maxT — 통과 `N0` > ${NULL_MAX_PASSES} 면 계기 무효.")
        out.appendLine()
        out.appendLine("## 0. rung 별 기준선 (사전고정 1·8)")
        out.appendLine()
        out.appendLine("| 해상도 | 기준 거래 | 기준 Σpnl %p | Σ보유일 | ALL_PASS 일치 | maxT q (13셀) | q (7셀 참고) |")
        out.appendLine("|---|---|---|---|---|---|---|")
        for (level in levels) {
            val b = baseRuns.getValue(level.unit)
            out.appendLine("| %dm | %d | %+.2f | %d | 예 | %.3f | %.3f |".format(level.unit, b.all.size, b.all.sumOf { it.netPnlPct }, b.all.sumOf { heldDays(it, windowOf(level, it)) }, families.getValue(level.unit).q, family7.getValue(level.unit).q))
        }
        out.appendLine()
        out.appendLine("## 1. 사다리 — 격차/기준거래 %p · 통과 · 수렴 · 후보 (9)")
        out.appendLine()
        out.appendLine("| 셀 | " + levels.joinToString(" | ") { "${it.unit}분" } + " | 수렴 | d 95% 하한 | ${P}분 동시 하한/거래 | ${P}분 차단율 | 마켓 +/전체 | 후보 | 비고 |")
        out.appendLine("|---" + "|---".repeat(levels.size) + "|---|---|---|---|---|---|---|")
        for ((i, c) in CELLS.withIndex()) {
            val f = finals.getValue(c)
            out.appendLine("| %s | %s | %s | %s | %+.3f | %.0f%% | %d/%d | %s | %s |".format(c.label,
                levels.joinToString(" | ") { l -> val p = cellOf(l.unit, i); "%+.3f%s".format(p.g / baseCount(l.unit), if (p.pass) " **통과**" else "") },
                when (f.converged) { true -> "수렴"; false -> "미수렴"; null -> "미정의" }, f.dLower?.let { "%+.4f".format(it) } ?: "—",
                cellOf(P, i).lowerBound / baseCount(P), 100 * f.block, f.signs.first, f.signs.second, if (f.candidate) "**후보**" else "—", f.notes.joinToString("; ")))
        }
        out.appendLine()
        out.appendLine(if (ranked.isEmpty()) "**후보 없음**" + (if (CELLS.none { finals.getValue(it).pass }) " — ${P}분봉 통과 0: 현행 유지, 이 family 에서 바꿀 근거 없음." else "") else "**후보 순서**: " + ranked.joinToString(" → ") { it.label })
        out.appendLine()
        out.appendLine("## 2. ${P}분봉 판정표 (13셀 전부)")
        out.appendLine()
        out.appendLine("| 셀 | 거래 | Σ보유일 | 격차 %p | 격차/기준거래 | se | T | 동시95%하한 | 한계p | 통과(13) | 통과(7 참고) | 차단 (market,day) | 허용 (market,day) | 노출 정규화 격차(%p/보유일) | bhNeg [95%] |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        val nNeg = lvP.windows.filter { it.dir in bhNeg }.sumOf { baseRuns.getValue(P).byWindow.getValue(it.dir).size }
        for ((i, c) in CELLS.withIndex()) {
            val p = cellOf(P, i); val run = cellRuns.getValue(P to c); val f = finals.getValue(c)
            val neg = interval("$P/neg/$i")
            val ref7 = REFERENCE_7.indexOf(c).let { if (it >= 0) (if (family7.getValue(P).cells[it].pass) "통과" else "—") else "해당 없음" }
            out.appendLine("| %s | %d | %d | %+.2f | %+.3f | %.2f | %.2f | %+.2f | %.4f | %s | %s | %d | %d | %+.4f | %+.3f [%+.3f, %+.3f] |".format(
                c.label, run.all.size, run.all.sumOf { heldDays(it, windowOf(lvP, it)) }, p.g, p.g / baseCount(P), p.se, p.t, p.lowerBound, p.marginalP,
                if (p.pass) "**통과**" else "—", ref7, run.blocked, run.allowed, f.exposure, neg.g / nNeg, neg.ciLow / nNeg, neg.ciHigh / nNeg))
        }
        out.appendLine()
        out.appendLine("후보 조건(사전고정 6): (a) ${P}분 maxT 통과 (b) ${R ?: "—"}분 같은 부호·g${P} ≥ 0.8·g${R ?: "—"} (c) 격차/기준거래 ≥ %.2f (d) 마켓 양수 비율 ≥ %.0f%% (e) 노출 정규화 부호 일치 (f) 차단율 %.0f~%.0f%% (g) null 게이트 유효.".format(ECONOMIC_FLOOR, 100 * MARKET_POSITIVE_SHARE, 100 * BLOCK_MIN, 100 * BLOCK_MAX))
        out.appendLine()
        out.appendLine("## 3. null 게이트 (5)")
        out.appendLine()
        out.appendLine("**N0 = $n0 / ${NULL_SEEDS * n}** → " + (if (nullValid) "계기 유효(≤ $NULL_MAX_PASSES)" else "**계기 무효**(> $NULL_MAX_PASSES) — 후보 0 으로 종결"))
        out.appendLine()
        out.appendLine("| seed(이동일) | q | 통과 셀 | 최대 T | 최대 격차/기준거래 |")
        out.appendLine("|---|---|---|---|---|")
        for ((s, f) in nullFamilies) {
            val maxT = f.cells.filter { !it.t.isNaN() }.maxOfOrNull { it.t } ?: Double.NaN
            out.appendLine("| %d (%d) | %.3f | %s | %.2f | %+.3f |".format(s, s * NULL_SHIFT_STEP, f.q, f.cells.withIndex().filter { it.value.pass }.joinToString(",") { CELLS[it.index].label }.ifEmpty { "—" }, maxT, f.cells.maxOf { it.g } / baseCount(P)))
        }
        out.appendLine()
        out.appendLine("## 4. ${P}분봉 10창 × 13셀 격차 %p (판정에 쓰지 않는다)")
        out.appendLine()
        out.appendLine("| 창 | 기준 Σpnl (건) | " + CELLS.joinToString(" | ") { it.label } + " |")
        out.appendLine("|---|---" + "|---".repeat(n) + "|")
        for (w in lvP.windows) {
            val b = baseRuns.getValue(P).byWindow.getValue(w.dir)
            out.appendLine("| %s | %+.2f (%d) | ".format(w.label, b.sumOf { it.netPnlPct }, b.size) + CELLS.joinToString(" | ") { "%+.2f".format(cellRuns.getValue(P to it).byWindow.getValue(w.dir).sumOf { t -> t.netPnlPct } - b.sumOf { t -> t.netPnlPct }) } + " |")
        }
        out.appendLine()
        out.appendLine("## 한계")
        out.appendLine()
        out.appendLine("- 게이트가 진입 하나를 막으면 그 뒤의 포지션 상태가 기준과 달라져 이후 진입도 달라진다 — 셀 거래는 기준의 부분집합이 아니다. 기여는 진입일 차분이라 상쇄가 크지만 완전하지 않다.")
        out.appendLine("- 다섯 신호는 전부 BTC 기준 시장 전체 스칼라다. 알트 마켓의 고유 레짐(마켓별 김프·펀딩)은 재지 않았다.")
        out.appendLine("- 위상 이동 null 은 자기상관 구조를 보존하지만 시장 사이클과의 정렬만 깬다 — 20 seed 는 분위수가 아니라 통과 건수 상한으로만 쓴다.")
        out.appendLine("- 생존편향·단일 포지션·무슬리피지 등 계기 한계는 선행 사다리와 같다.")

        val path = Path.of("build/reports/external-regime-gate.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[gate] 리포트: ${path.toAbsolutePath()}")
    }

    private companion object {
        val UNITS = listOf(240, 15, 5)
        val CELLS = ExternalRegime.Cell.values().toList()
        /** Decisions 3 — 김프·펀딩만의 7셀 family q 를 참고 열로 병기(판정은 13셀 q). */
        val REFERENCE_7 = listOf(ExternalRegime.Cell.KIMP_LOW, ExternalRegime.Cell.KIMP_HIGH, ExternalRegime.Cell.KIMP_RISING,
            ExternalRegime.Cell.FUND_NEG, ExternalRegime.Cell.FUND_LOW, ExternalRegime.Cell.FUND_HIGH, ExternalRegime.Cell.COMBO)
        const val NULL_SEEDS = 20
        const val NULL_SHIFT_STEP = 17
        const val NULL_MAX_PASSES = 13
        const val ECONOMIC_FLOOR = 0.10
        const val MARKET_POSITIVE_SHARE = 6.0 / 8.0
        const val BLOCK_MIN = 0.10
        const val BLOCK_MAX = 0.70
        val BH_NEG = setOf("bull", "p2021h1", "p2021h2", "p2022h1")
        const val EXPECTED_DAYS_PER_WINDOW = 150
        const val EXPECTED_FRAME_DAYS = 1_500
        /** 선행 사다리(`exit-resolution-ladder-2026-09`) 5분봉 기준 거래수 — 사전고정 8e. */
        const val PRIOR_BASE_TRADES_5M = 1_767
    }
}
