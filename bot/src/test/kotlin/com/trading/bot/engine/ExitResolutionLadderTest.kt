package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 해상도 사다리(240 → 15 → 5분봉)로 익절·손절·9시 정책 7셀을 **사전명세** 비교한다 — plan `2026-09-09-minute-ladder` `# Acceptance` 1~11.
 * 계기·창·통계량은 [TakeProfitStopLossIntradayTest] 과 같고 봉 단위만 다르다. 주 판정 = 5분봉(목록의 마지막), 240·15 는 사다리 표기.
 * 240분봉 rung 은 사전고정 커밋 전 smoke 로 이미 관측됐다(plan Decisions 4) — blind 인 것은 15·5분봉뿐이다.
 *
 * 기여는 **진입일 단위**(그날 진입한 셀 거래 손익 합 − 기준 거래 손익 합). frame 은 240분봉 기준 공통(1,500일)이고 draw 는 셀·rung 전부가 공유한다.
 *
 * 실행: `RUN_EXIT_LADDER=true ./gradlew :bot:test --tests "*ExitResolutionLadderTest*" --rerun-tasks` (15·5분봉 캐시 필요 — [IntradayCache]).
 * `LADDER_UNITS=240` 은 배관 smoke 전용이며 산출물에 "SMOKE — 판정 아님" 이 찍힌다.
 */
class ExitResolutionLadderTest {

    private val props = TradingProperties()
    private val strategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }

    private data class Cell(val label: String, val tp: Double, val sl: Double, val keepDays: Int = 0) {
        val isPolicy get() = keepDays > 0
        fun config() = StrategySearchGrid.currentLivePoint().copy(takeProfitPct = tp, maxLossPct = sl).toConfig()
    }

    /** 처리(family): 주 / 진입봉 손절 종가 판정 / 비관 트레일링. */
    private enum class Arm(val onClose: Boolean, val pessimistic: Boolean) { PRIMARY(false, false), ENTRY_BAR(true, false), PESSIMISTIC(false, true) }

    private data class EntryKey(val market: String, val entryDate: String, val entryPrice: Double)

    private class WindowData(val label: String, val dir: String, val unit: Int, val daily: Map<String, List<Candle>>, val intraday: Map<String, List<Candle>>) {
        val tradingDays: List<String>            // 워밍업 이후 일봉 날짜(마켓 합집합)
        val zeroBarMarketDays: Int                // 그날 봉이 없는 (market, day)
        val firstBarMissing: Int                  // 첫 봉(00:00 UTC)만 없는 (market, day)
        val maxBarsPerDay: Int
        val dayOpen: Map<Pair<String, String>, Double>  // (market, day) → 첫 존재 봉 시가
        val zeroBarDaysOf: Map<String, Set<String>>
        val buyAndHold: Map<String, Double>
        val buyAndHoldMedian: Double
        init {
            val days = sortedSetOf<String>()
            var zero = 0; var firstMissing = 0; var maxBars = 0
            val opens = HashMap<Pair<String, String>, Double>()
            val zeroOf = HashMap<String, MutableSet<String>>()
            val bh = LinkedHashMap<String, Double>()
            for ((market, newestFirst) in daily) {
                val ch = newestFirst.reversed()
                val byDay = intraday.getValue(market).groupBy { it.candleDateTimeUtc.substring(0, 10) }
                for (i in BacktestEngine.MIN_CANDLES until ch.size) {
                    val d = ch[i].candleDateTimeKst.substring(0, 10)
                    days += d
                    val bars = byDay[d]
                    if (bars == null) { zero++; zeroOf.getOrPut(market) { HashSet() } += d; continue }
                    maxBars = maxOf(maxBars, bars.size)
                    if (!bars.first().candleDateTimeUtc.endsWith("T00:00:00")) firstMissing++
                    opens[market to d] = bars.first().openingPrice
                }
                val start = ch[BacktestEngine.MIN_CANDLES]
                bh[market] = (ch.last().tradePrice - start.openingPrice) / start.openingPrice * 100.0
            }
            tradingDays = days.toList(); zeroBarMarketDays = zero; firstBarMissing = firstMissing; maxBarsPerDay = maxBars
            dayOpen = opens; zeroBarDaysOf = zeroOf; buyAndHold = bh
            val s = bh.values.sorted(); buyAndHoldMedian = (s[s.size / 2] + s[(s.size - 1) / 2]) / 2
        }
        fun key(date: String) = "$dir/$date"
    }

    private class Level(val unit: Int, val windows: List<WindowData>)

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_EXIT_LADDER", matches = "true")
    fun `pre-registered resolution ladder for take-profit, stop-loss and the 09-00 keep-winners policy`() = runBlocking {
        val regimes = BacktestFixtures.EXPANSION_2020_2023 + BacktestFixtures.TIME_INDEPENDENT
        val smoke = System.getenv("LADDER_UNITS") != null
        val units = System.getenv("LADDER_UNITS")?.split(",")?.map { it.trim().toInt() } ?: UNITS
        require(units.first() == 240) { "frame 은 240분봉 기준이라 목록은 240 으로 시작해야 한다" }
        for (unit in units.filter { it != 240 }) for (r in regimes) {
            require(IntradayCache.available(unit, r.dir, BacktestFixtures.markets(r))) { "${unit}분봉 캐시 부재: ${r.dir} — 부분 캐시로는 판정하지 않는다(사전고정 1)" }
        }
        val levels = units.map { unit ->
            Level(unit, regimes.map { r ->
                val daily = BacktestFixtures.loadAll(r)
                val intraday = if (unit == 240) IntradayFixtures.loadAll(r.dir, daily.keys) else IntradayCache.loadAll(unit, r.dir, daily.keys)
                WindowData(r.label, r.dir, unit, daily, intraday)
            })
        }
        val base240 = levels.first()
        val P = levels.last().unit
        val R = levels.dropLast(1).lastOrNull()?.unit

        // ── 공통 frame(사전고정 4)·단순보유 분류(9)·봉/일 단정(7d) ──
        base240.windows.forEach { assertEquals(EXPECTED_DAYS_PER_WINDOW, it.tradingDays.size, "${it.label}: 240분 frame 일수") }
        assertEquals(0, base240.windows.sumOf { it.zeroBarMarketDays }, "240분 결측 마켓-일")
        val frame = PairedMaxTBootstrap.Frame(base240.windows.map { w -> w.tradingDays.map { w.key(it) } })
        assertEquals(EXPECTED_FRAME_DAYS, frame.size, "공통 frame 일수")
        val bhNeg = base240.windows.filter { it.buyAndHoldMedian < 0 }.map { it.dir }.toSet()
        assertEquals(BH_NEG, bhNeg, "단순보유 창 분류가 선행 표와 다르다")
        for (level in levels) level.windows.forEach { assertTrue(it.maxBarsPerDay <= 24 * 60 / level.unit, "${level.unit}m ${it.label}: 봉/일 ${it.maxBarsPerDay}") }
        // 7e — 공통 (market, day) 의 첫 봉 시가 동일
        for (level in levels.drop(1)) for ((w240, w) in base240.windows.zip(level.windows)) {
            for ((k, o) in w.dayOpen) {
                val o240 = w240.dayOpen[k] ?: continue
                assertTrue(abs(o - o240) <= 1e-9 * maxOf(1.0, o240), "${level.unit}m ${w.label} $k: 첫 봉 시가 $o ≠ 240분 $o240 (7e)")
            }
        }

        // ── 실행: rung × (기준 + 7셀) × 3 처리 ──
        val runs = HashMap<Triple<Int, Cell, Arm>, Map<String, List<LiveSemanticsArm.Trade>>>()
        for (level in levels) for (cell in listOf(BASE) + CANDIDATES) for (arm in Arm.values()) {
            val byWindow = LinkedHashMap<String, List<LiveSemanticsArm.Trade>>()
            for (w in level.windows) {
                val out = ArrayList<LiveSemanticsArm.Trade>()
                for ((market, newestFirst) in w.daily) {
                    out += LiveSemanticsArm.run(
                        market, strategy, newestFirst.reversed(), w.intraday.getValue(market).reversed(), cell.config(), props,
                        entryBarStopOnClose = arm.onClose, keepWinnersUntilDays = cell.keepDays, pessimisticTrailing = arm.pessimistic,
                    )
                }
                byWindow[w.dir] = out
            }
            runs[Triple(level.unit, cell, arm)] = byWindow
            println("[ladder] ${level.unit}m ${cell.label} $arm: ${byWindow.values.sumOf { it.size }}건")
        }
        fun trades(unit: Int, cell: Cell, arm: Arm = Arm.PRIMARY) = runs.getValue(Triple(unit, cell, arm))
        fun all(unit: Int, cell: Cell, arm: Arm = Arm.PRIMARY) = trades(unit, cell, arm).values.flatten()
        fun keyOf(t: LiveSemanticsArm.Trade) = EntryKey(t.market, t.entryDate, t.entryPrice)
        fun heldDays(t: LiveSemanticsArm.Trade, w: WindowData): Int {
            val days = w.tradingDays
            return days.indexOf(t.exitDate) - days.indexOf(t.entryDate)
        }
        fun windowOf(level: Level, dir: String) = level.windows.first { it.dir == dir }

        // ── 배관 단정 7a·7b·7c·정책 보유일 ──
        for (level in levels) for (cell in CANDIDATES) for (arm in Arm.values()) {
            if (!cell.isPolicy) for (w in level.windows) {
                assertEquals(trades(level.unit, BASE, arm).getValue(w.dir).map(::keyOf).toSet(), trades(level.unit, cell, arm).getValue(w.dir).map(::keyOf).toSet(),
                    "${level.unit}m ${cell.label} $arm ${w.label}: 진입 집합이 기준과 다르다 (7a)")
            }
            val t = all(level.unit, cell, arm)
            if (cell.tp >= OFF) assertEquals(0, t.count { it.reason == "TAKE_PROFIT" }, "${cell.label}: 익절 off 인데 TAKE_PROFIT (7c)")
            if (cell.sl >= OFF) assertEquals(0, t.count { it.reason == "STOP_LOSS" }, "${cell.label}: 손절 off 인데 STOP_LOSS (7c)")
            if (cell.isPolicy) for (w in level.windows) for (tr in trades(level.unit, cell, arm).getValue(w.dir)) {
                if (tr.reason == "END") continue
                val zeroDays = w.zeroBarDaysOf[tr.market].orEmpty()
                val between = w.tradingDays.subList(w.tradingDays.indexOf(tr.entryDate), w.tradingDays.indexOf(tr.exitDate) + 1)
                if (between.none { it in zeroDays }) assertTrue(heldDays(tr, w) <= cell.keepDays, "${level.unit}m ${cell.label}: 보유일 ${heldDays(tr, w)} > ${cell.keepDays} (7)")
            }
        }
        run {
            val b = all(240, BASE)
            assertEquals(PRIOR_BASE_TRADES, b.size, "240분 기준 거래수 (7b)")
            for ((cell, prior) in PRIOR_GAPS) {
                val gap = all(240, cell).sumOf { it.netPnlPct } - b.sumOf { it.netPnlPct }
                assertTrue(abs(gap - prior) <= 0.01, "240분 ${cell.label}: 격차 %.2f ≠ 선행 %.2f (7b)".format(gap, prior))
            }
        }

        // ── 기여(진입일 단위, 공통 frame) — 모든 rung·family·마스크를 한 번의 draw 로 ──
        fun contributions(level: Level, cell: Cell, arm: Arm, filter: (WindowData) -> Boolean = { true }): DoubleArray {
            val byKey = HashMap<String, Double>()
            for (w in level.windows.filter(filter)) {
                for (t in trades(level.unit, cell, arm).getValue(w.dir)) byKey.merge(w.key(t.entryDate), t.netPnlPct, Double::plus)
                for (t in trades(level.unit, BASE, arm).getValue(w.dir)) byKey.merge(w.key(t.entryDate), -t.netPnlPct, Double::plus)
            }
            return frame.align(byKey)
        }
        fun baseCount(unit: Int) = all(unit, BASE).size
        val n = CANDIDATES.size
        val groups = ArrayList<DoubleArray>()
        val index = HashMap<String, Int>()   // "unit/arm/i" 또는 "unit/pos/i", "unit/neg/i", "d/i"
        fun put(key: String, arr: DoubleArray) { index[key] = groups.size; groups += arr }
        for (level in levels) {
            for (arm in Arm.values()) for ((i, c) in CANDIDATES.withIndex()) put("${level.unit}/$arm/$i", contributions(level, c, arm))
            for ((i, c) in CANDIDATES.withIndex()) put("${level.unit}/pos/$i", contributions(level, c, Arm.PRIMARY) { it.dir !in bhNeg })
            for ((i, c) in CANDIDATES.withIndex()) put("${level.unit}/neg/$i", contributions(level, c, Arm.PRIMARY) { it.dir in bhNeg })
        }
        if (R != null) for (i in CANDIDATES.indices) {
            val a5 = groups[index.getValue("$P/${Arm.PRIMARY}/$i")]; val a15 = groups[index.getValue("$R/${Arm.PRIMARY}/$i")]
            put("d/$i", DoubleArray(frame.size) { a5[it] / baseCount(P) - 0.8 * a15[it] / baseCount(R) })
        }
        val sums = PairedMaxTBootstrap.resampleSums(frame, groups)
        val sums10 = PairedMaxTBootstrap.resampleSums(frame, CANDIDATES.indices.map { groups[index.getValue("$P/${Arm.PRIMARY}/$it")] }, block = 10)
        fun family(unit: Int, arm: Arm): PairedMaxTBootstrap.Family {
            val idx = CANDIDATES.indices.map { index.getValue("$unit/$arm/$it") }
            return PairedMaxTBootstrap.maxT(DoubleArray(n) { groups[idx[it]].sum() }, idx.map { sums[it] }.toTypedArray())
        }
        val families = levels.associate { l -> l.unit to Arm.values().associateWith { family(l.unit, it) } }
        val familyL10 = PairedMaxTBootstrap.maxT(DoubleArray(n) { groups[index.getValue("$P/${Arm.PRIMARY}/$it")].sum() }, sums10)
        fun interval(key: String) = PairedMaxTBootstrap.interval(groups[index.getValue(key)].sum(), sums[index.getValue(key)])
        fun cellOf(unit: Int, arm: Arm, i: Int) = families.getValue(unit).getValue(arm).cells[i]
        fun perTrade(unit: Int, i: Int) = cellOf(unit, Arm.PRIMARY, i).g / baseCount(unit)
        fun exposureGap(unit: Int, cell: Cell): Double {
            val m = all(unit, cell); val b = all(unit, BASE)
            val lv = levels.first { it.unit == unit }
            fun days(ts: List<LiveSemanticsArm.Trade>) = ts.sumOf { maxOf(1, heldDays(it, windowOf(lv, lv.windows.first { w -> w.daily.containsKey(it.market) && it.entryDate in w.tradingDays }.dir))) }
            return m.sumOf { it.netPnlPct } / days(m) - b.sumOf { it.netPnlPct } / days(b)
        }

        // ── 수렴(5) · 후보(6) ──
        data class Final(val pass: Boolean, val converged: Boolean?, val monotone: Boolean?, val candidate: Boolean, val notes: List<String>, val dLower: Double?)
        val finals = CANDIDATES.withIndex().associate { (i, c) ->
            val p = cellOf(P, Arm.PRIMARY, i)
            val g5 = perTrade(P, i); val g15 = R?.let { perTrade(it, i) }
            val converged: Boolean? = if (g15 != null && g15 > 0) g5 >= 0.8 * g15 else null
            val monotone: Boolean? = if (g15 != null && g15 > 0 && levels.size >= 3) perTrade(240, i) >= g15 else null
            val dLower = if (R != null) interval("d/$i").ciLow else null
            val notes = ArrayList<String>()
            var cand = p.pass
            if (p.pass && converged != true) { notes += when { R == null -> "사다리 없음(smoke)"; converged == null -> "수렴 미정의(${R}분 격차 ≤ 0)"; else -> "미수렴 — 1분봉 필요" }; cand = false }
            if (p.pass && monotone == false) { notes += "비단조(240 < ${R}) — 편향 단조감소 반증"; cand = false }
            if (p.pass && g5 < ECONOMIC_FLOOR) { notes += "경제 하한 미달"; cand = false }
            if (p.pass && interval("$P/neg/$i").ciHigh < 0) { notes += "하락 창에서 유의하게 열세"; cand = false }
            if (p.pass && (c.sl != 5.0 || c.isPolicy) && !cellOf(P, Arm.ENTRY_BAR, i).pass) { notes += "진입봉 브래킷 미통과"; cand = false }
            if (p.pass && (c.tp > 5.0 || c.isPolicy) && !cellOf(P, Arm.PESSIMISTIC, i).pass) { notes += "비관 트레일링 브래킷 미통과"; cand = false }
            val eg = exposureGap(P, c)
            if (p.pass && (eg > 0) != (p.g > 0)) { notes += "노출 정규화 부호 불일치"; cand = false }
            c to Final(p.pass, converged, monotone, cand, notes, dLower)
        }
        val ranked = CANDIDATES.withIndex().filter { finals.getValue(it.value).candidate }
            .sortedWith(compareByDescending<IndexedValue<Cell>> { cellOf(P, Arm.PRIMARY, it.index).lowerBound / baseCount(P) }.thenBy { it.index }).map { it.value }

        // ── 진단(9) — 주 판정 rung ──
        val lvP = levels.last()
        fun windowGap(cell: Cell, w: WindowData) = trades(P, cell).getValue(w.dir).sumOf { it.netPnlPct } - trades(P, BASE).getValue(w.dir).sumOf { it.netPnlPct }
        fun baseCountIn(f: (WindowData) -> Boolean) = lvP.windows.filter(f).sumOf { trades(P, BASE).getValue(it.dir).size }
        fun gapPerBase(cell: Cell, f: (WindowData) -> Boolean = { true }): Double { val cnt = baseCountIn(f); return if (cnt == 0) 0.0 else lvP.windows.filter(f).sumOf { windowGap(cell, it) } / cnt }
        data class Diag(val effectiveN: Int, val entryBarStops: Int, val entryBarStopPnl: Double, val stops: Int, val overshoot: Double, val laterOvershoot: Double,
                        val lowoMin: Double, val lowoWindow: String, val lowoNegMin: Double, val lowoNegWindow: String, val topSurvivorRemoved: Double,
                        val spearman: Double, val worstWindow: String, val worstPerTrade: Double, val overlap240: Int)
        fun diag(cell: Cell): Diag {
            val mine = all(P, cell); val base = all(P, BASE)
            val baseByKey = base.associateBy(::keyOf)
            val effN = mine.count { t -> baseByKey[keyOf(t)]?.let { abs(it.exitPrice - t.exitPrice) > 1e-9 } ?: true }
            val ebs = mine.filter { it.exitOnEntryBar && it.reason == "STOP_LOSS" }
            val stops = mine.filter { it.reason == "STOP_LOSS" }
            fun ov(ts: List<LiveSemanticsArm.Trade>) = if (ts.isEmpty()) 0.0 else ts.map { (it.exitPrice - it.exitBarLow) / it.entryPrice * 100.0 }.average()
            val worst = lvP.windows.map { w -> val k = trades(P, BASE).getValue(w.dir).size; w to (if (k == 0) 0.0 else windowGap(cell, w) / k) }.minByOrNull { it.second }!!
            val lowo = lvP.windows.map { ex -> ex to gapPerBase(cell) { it.dir != ex.dir } }.minByOrNull { it.second }!!
            val lowoNeg = lvP.windows.filter { it.dir in bhNeg }.map { ex -> ex to gapPerBase(cell) { it.dir in bhNeg && it.dir != ex.dir } }.minByOrNull { it.second }!!
            var g = 0.0; var cnt = 0
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (w in lvP.windows) {
                val top = w.buyAndHold.maxByOrNull { it.value }!!.key
                val m = trades(P, cell).getValue(w.dir); val b = trades(P, BASE).getValue(w.dir)
                g += m.filter { it.market != top }.sumOf { it.netPnlPct } - b.filter { it.market != top }.sumOf { it.netPnlPct }; cnt += b.count { it.market != top }
                for ((market, bh) in w.buyAndHold) { xs += bh; ys += m.filter { it.market == market }.sumOf { it.netPnlPct } - b.filter { it.market == market }.sumOf { it.netPnlPct } }
            }
            val e240 = all(240, cell).map { it.market to it.entryDate }.toSet(); val eP = mine.map { it.market to it.entryDate }.toSet()
            return Diag(effN, ebs.size, ebs.sumOf { it.netPnlPct }, stops.size, ov(stops), ov(stops.filter { !it.exitOnEntryBar }),
                lowo.second, lowo.first.label, lowoNeg.second, lowoNeg.first.label, if (cnt == 0) 0.0 else g / cnt,
                PairedMaxTBootstrap.spearman(xs.toDoubleArray(), ys.toDoubleArray()), worst.first.label, worst.second, (e240 intersect eP).size)
        }

        // ── 리포트 ──
        val out = StringBuilder()
        if (smoke) out.appendLine("**SMOKE — 판정 아님** (`LADDER_UNITS=${units.joinToString(",")}`)").also { out.appendLine() }
        out.appendLine("# 청산 해상도 사다리 — ${units.joinToString(" → ")}분봉, 익절·손절·9시 정책 7셀 사전명세 비교")
        out.appendLine()
        out.appendLine("규칙은 15·5분봉 결과를 보기 전에 커밋했다(plan `2026-09-09-minute-ladder` `# Acceptance` 1~11; 240분봉은 smoke 로 먼저 관측). 기준 = 현행 라이브 TP5/SL5/트레일1.5/arm0/k0.5/h1. 주 판정 = ${P}분봉.")
        out.appendLine("기여 = 진입일 단위, frame = 240분봉 공통 ${frame.size}일, 블록 ${PairedMaxTBootstrap.BLOCK}일, B=${PairedMaxTBootstrap.RESAMPLES}, seed ${PairedMaxTBootstrap.SEED}, draw 는 셀·rung·family 공통.")
        out.appendLine("수렴 = ${P}분 격차/기준거래 ≥ 0.8 × ${R ?: "—"}분 값(15분 값 > 0). 비단조(240 < 15)면 승격 불가. 후보 조건은 사전고정 6.")
        out.appendLine()
        out.appendLine("## 0. rung 별 기준선·결측 통계 (10-3)")
        out.appendLine()
        out.appendLine("| 해상도 | 기준 거래 | 기준 Σpnl %p | Σ보유일 | 봉/일 최대 | 0봉 마켓-일 | 첫 봉 결측 마켓-일 | maxT q 주 / 진입봉 / 비관 |")
        out.appendLine("|---|---|---|---|---|---|---|---|")
        for (level in levels) {
            val b = all(level.unit, BASE); val f = families.getValue(level.unit)
            out.appendLine("| %dm | %d | %+.2f | %d | %d | %d | %d | %.3f / %.3f / %.3f |".format(level.unit, b.size, b.sumOf { it.netPnlPct },
                b.sumOf { maxOf(1, heldDays(it, level.windows.first { w -> it.entryDate in w.tradingDays && w.daily.containsKey(it.market) })) },
                level.windows.maxOf { it.maxBarsPerDay }, level.windows.sumOf { it.zeroBarMarketDays }, level.windows.sumOf { it.firstBarMissing },
                f.getValue(Arm.PRIMARY).q, f.getValue(Arm.ENTRY_BAR).q, f.getValue(Arm.PESSIMISTIC).q))
        }
        out.appendLine()
        out.appendLine("## 1. 사다리 — 격차/기준거래 %p · 통과 · 수렴 (10-1)")
        out.appendLine()
        out.appendLine("| 셀 | " + levels.joinToString(" | ") { "${it.unit}분" } + " | 수렴 | 단조 | d=g${P}−0.8·g${R ?: "?"} 95% 하한 | ${P}분 동시 하한/거래 | 후보 | 비고 |")
        out.appendLine("|---" + "|---".repeat(levels.size) + "|---|---|---|---|---|---|")
        for ((i, c) in CANDIDATES.withIndex()) {
            val f = finals.getValue(c)
            out.appendLine("| %s | %s | %s | %s | %s | %+.3f | %s | %s |".format(c.label,
                levels.joinToString(" | ") { l -> val p = cellOf(l.unit, Arm.PRIMARY, i); "%+.3f%s".format(p.g / baseCount(l.unit), if (p.pass) " **통과**" else "") },
                when (f.converged) { true -> "수렴"; false -> "미수렴"; null -> "미정의" }, when (f.monotone) { true -> "단조"; false -> "**비단조**"; null -> "—" },
                f.dLower?.let { "%+.4f".format(it) } ?: "—", cellOf(P, Arm.PRIMARY, i).lowerBound / baseCount(P), if (f.candidate) "**후보**" else "—", f.notes.joinToString("; ")))
        }
        out.appendLine()
        out.appendLine(if (ranked.isEmpty()) "**후보 없음**" + (if (CANDIDATES.none { finals.getValue(it).pass }) " — ${P}분봉 통과 0: 현행 유지, 이 사다리에서 바꿀 근거 없음." else "") else "**후보 순서**: " + ranked.joinToString(" → ") { it.label })
        out.appendLine()
        out.appendLine("## 2. ${P}분봉 판정표 (7셀 전부, 10-2)")
        out.appendLine()
        out.appendLine("| 셀 | 거래 | Σ보유일 | 격차 %p | 격차/기준거래 | se | T | 동시95%하한 | 한계p | 통과 | 선행 q 2.644 통과 | L10 통과 | 진입봉 family · 격차 | 비관 family · 격차 | 노출 정규화 격차(%p/보유일) | bhPos [95%] | bhNeg [95%] |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        val nPos = baseCountIn { it.dir !in bhNeg }; val nNeg = baseCountIn { it.dir in bhNeg }
        for ((i, c) in CANDIDATES.withIndex()) {
            val p = cellOf(P, Arm.PRIMARY, i); val e = cellOf(P, Arm.ENTRY_BAR, i); val q = cellOf(P, Arm.PESSIMISTIC, i); val t = all(P, c)
            val pos = interval("$P/pos/$i"); val neg = interval("$P/neg/$i")
            out.appendLine("| %s | %d | %d | %+.2f | %+.3f | %.2f | %.2f | %+.2f | %.4f | %s | %s | %s | %s · %+.2f | %s · %+.2f | %+.4f | %+.3f [%+.3f, %+.3f] | %+.3f [%+.3f, %+.3f] |".format(
                c.label, t.size, t.sumOf { maxOf(1, heldDays(it, lvP.windows.first { w -> it.entryDate in w.tradingDays && w.daily.containsKey(it.market) })) }, p.g, p.g / baseCount(P), p.se, p.t, p.lowerBound, p.marginalP,
                if (p.pass) "**통과**" else "—", if (p.t > PRIOR_Q) "통과" else "—", if (familyL10.cells[i].pass) "통과" else "—",
                if (e.pass) "통과" else "—", e.g, if (q.pass) "통과" else "—", q.g, exposureGap(P, c),
                pos.g / nPos, pos.ciLow / nPos, pos.ciHigh / nPos, neg.g / nNeg, neg.ciLow / nNeg, neg.ciHigh / nNeg))
        }
        out.appendLine()
        out.appendLine("후보 조건(사전고정 6): 통과 ∧ 수렴·단조 ∧ 격차/기준거래 ≥ %.2f ∧ bhNeg 상한 ≥ 0 ∧ 브래킷 family(손절·정책: 진입봉 / 익절 8·off·정책: 비관) 통과 ∧ 노출 정규화 부호 일치.".format(ECONOMIC_FLOOR))
        out.appendLine()
        out.appendLine("## 3. ${P}분봉 강건성 진단 (9)")
        out.appendLine()
        out.appendLine("| 셀 | LOWO 최소 (제외 창) | bhNeg LOWO 최소 (제외 창) | 탑 생존자 제거 | Spearman ρ | 유효 N | 진입봉 SL 건수 · pnl | SL 건수 · 오버슛 전체/이후 | 최악 창 (격차/기준거래) | 240↔${P} 진입일 겹침 |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        for (c in CANDIDATES) {
            val d = diag(c)
            out.appendLine("| %s | %+.3f (%s) | %+.3f (%s) | %+.3f | %+.2f | %d | %d · %+.1f | %d · %.3f / %.3f | %s (%+.3f) | %d / %d |".format(
                c.label, d.lowoMin, d.lowoWindow, d.lowoNegMin, d.lowoNegWindow, d.topSurvivorRemoved, d.spearman, d.effectiveN, d.entryBarStops, d.entryBarStopPnl,
                d.stops, d.overshoot, d.laterOvershoot, d.worstWindow, d.worstPerTrade, d.overlap240, all(P, c).size))
        }
        out.appendLine()
        out.appendLine("## 4. 청산 사유 구성 (10-4)")
        out.appendLine()
        out.appendLine("| 해상도 · 셀 | " + REASONS.joinToString(" | ") + " |")
        out.appendLine("|---" + "|---".repeat(REASONS.size) + "|")
        for (level in levels) for (c in listOf(BASE) + CANDIDATES) {
            val t = all(level.unit, c)
            out.appendLine("| %dm %s | %s |".format(level.unit, c.label, REASONS.joinToString(" | ") { r -> val g = t.filter { it.reason == r }; "%d건 %+.1f".format(g.size, g.sumOf { it.netPnlPct }) }))
        }
        out.appendLine()
        out.appendLine("## 5. 9시 정책 (8)")
        out.appendLine()
        out.appendLine("| 해상도 · 셀 | 거래 | Σ보유일 | 유지된 포지션 · 손익 합 | 유지 뒤 청산 사유 | 09:00 정리(미잠김) | 막힌 진입 | 트레일링 갭 관통 건수 · 오버슛 %p | END 로 끝난 유지 |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|")
        for (level in levels) for (c in CANDIDATES.filter { it.isPolicy }) {
            val lv = levels.first { it.unit == level.unit }
            val t = all(level.unit, c)
            val kept = t.filter { it.keptPastLimit && it.reason != "END" }
            val keptEnd = t.count { it.keptPastLimit && it.reason == "END" }
            val closedAtBoundary = t.count { !it.keptPastLimit && it.reason == "TIME_EXIT" }
            val baseEntries = all(level.unit, BASE).map { it.market to it.entryDate }.toSet(); val mine = t.map { it.market to it.entryDate }.toSet()
            val trailing = t.filter { it.reason == "TRAILING_STOP" && !it.exitBarOpen.isNaN() }
            val gapThrough = trailing.filter { it.exitBarOpen < it.exitPrice }
            val ov = if (trailing.isEmpty()) 0.0 else trailing.map { (it.exitPrice - it.exitBarLow) / it.entryPrice * 100.0 }.average()
            out.appendLine("| %dm %s | %d | %d | %d · %+.1f | %s | %d | %d | %d · %.3f | %d |".format(level.unit, c.label, t.size,
                t.sumOf { maxOf(1, heldDays(it, lv.windows.first { w -> it.entryDate in w.tradingDays && w.daily.containsKey(it.market) })) }, kept.size, kept.sumOf { it.netPnlPct },
                kept.groupBy { it.reason }.entries.sortedByDescending { it.value.size }.joinToString(" · ") { "${it.key} ${it.value.size}" },
                closedAtBoundary, (baseEntries - mine).size, gapThrough.size, ov, keptEnd))
        }
        out.appendLine()
        out.appendLine("## 6. ${P}분봉 10창 × 7셀 격차 %p (10-6, 판정에 쓰지 않는다)")
        out.appendLine()
        out.appendLine("| 창 | 기준 Σpnl (건) | " + CANDIDATES.joinToString(" | ") { it.label } + " |")
        out.appendLine("|---|---" + "|---".repeat(CANDIDATES.size) + "|")
        for (w in lvP.windows) {
            val b = trades(P, BASE).getValue(w.dir)
            out.appendLine("| %s | %+.2f (%d) | ".format(w.label, b.sumOf { it.netPnlPct }, b.size) + CANDIDATES.joinToString(" | ") { "%+.2f".format(windowGap(it, w)) } + " |")
        }
        out.appendLine()
        out.appendLine("## 한계")
        out.appendLine()
        out.appendLine("- ${P}분봉도 라이브(10초 tick)보다 성기다. 수렴은 편향의 대리 검사이지 소멸 증명이 아니다 — 비관 트레일링 family 가 상한 쪽 브래킷이다.")
        out.appendLine("- 세 rung 은 같은 거래 집합이 아니다(체결가·부분봉이 해상도에 따라 다르다). 격차는 차분이라 상쇄가 크지만 완전하지 않다 — §3 겹침 열.")
        out.appendLine("- 재추출은 창별 stratified 라 창 간 변동은 se 에 없다(LOWO 가 대리). 생존편향은 측정 불가. 9시 정책 셀의 트레일링 무슬리피지·갭 관통은 셀에 유리한 편향(§5 열).")

        val path = Path.of("build/reports/exit-resolution-ladder.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[ladder] 리포트: ${path.toAbsolutePath()}")
        assertTrue(out.contains("사다리 —"))
    }

    private companion object {
        val UNITS = listOf(240, 15, 5)
        const val OFF = StrategySearchGrid.TAKE_PROFIT_OFF
        val BASE = Cell("TP5/SL5", 5.0, 5.0)
        val CANDIDATES = listOf(
            Cell("TP8/SL5", 8.0, 5.0), Cell("TPoff/SL5", OFF, 5.0),
            Cell("TP5/SL3", 5.0, 3.0), Cell("TP5/SL7", 5.0, 7.0), Cell("TP5/SLoff", 5.0, OFF),
            Cell("TP5/SL5+keep5", 5.0, 5.0, keepDays = 5), Cell("TPoff/SL5+keep5", OFF, 5.0, keepDays = 5),
        )
        const val ECONOMIC_FLOOR = 0.10
        /** 선행 19셀 family 의 maxT 임계 — family 축소(19→7)로 q 가 내려간 효과를 보이기 위한 참고 열(사전고정 4). */
        const val PRIOR_Q = 2.644
        val BH_NEG = setOf("bull", "p2021h1", "p2021h2", "p2022h1")
        val REASONS = listOf("TRAILING_STOP", "TAKE_PROFIT", "STOP_LOSS", "TIME_EXIT", "END")
        const val EXPECTED_DAYS_PER_WINDOW = 150
        const val EXPECTED_FRAME_DAYS = 1_500
        /** 선행 리포트(`tp-sl-grid` 브랜치 `take-profit-stop-loss-intraday.md`, 240분봉) 값 — 사전고정 7b 자기일관성 검사. */
        const val PRIOR_BASE_TRADES = 1_058
        val PRIOR_GAPS = mapOf(CANDIDATES[0] to 97.10, CANDIDATES[1] to 206.35, CANDIDATES[2] to -145.99, CANDIDATES[3] to 32.37, CANDIDATES[4] to 138.64)
    }
}
