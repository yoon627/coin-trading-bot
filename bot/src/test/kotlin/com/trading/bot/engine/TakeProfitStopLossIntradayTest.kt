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
 * 익절 {3,5,8,off} × 손절 {3,5,7,10,off} 20셀의 **사전명세 비교** — 현행 라이브(TP5/SL5/트레일1.5/arm0/k0.5/h1) 대비, 10창,
 * 라이브 의미론 계기([LiveSemanticsArm]). 규칙은 plan `2026-09-09-tp-sl-grid` `# Acceptance` 1~12 에 결과를 보기 전에 커밋했다.
 *
 * 통계량은 [PairedMaxTBootstrap] — 진입일 1:1 페어링, 워밍업 이후 전 거래일 frame, 창별 이동블록, 셀 공통 draw 의 studentized maxT.
 * 선행 정의([DateBlockBootstrap]) 는 비교용 열로만 낸다. 확증 실험이 아니다 — 10창은 이미 트레일링 판정에 쓰였다.
 *
 * 실행: `RUN_TP_SL_GRID=true ./gradlew :bot:test --tests "*TakeProfitStopLossIntradayTest*" --rerun-tasks`
 */
class TakeProfitStopLossIntradayTest {

    private val props = TradingProperties()
    private val notionalKrw = 100_000.0
    private val strategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }

    private data class CellKey(val tp: Double, val sl: Double) {
        val label: String get() = "TP${fmt(tp)}/SL${fmt(sl)}"
        private fun fmt(v: Double) = if (v >= OFF) "off" else "%.0f".format(v)
        /** 현행(5/5)과의 축 스텝 거리 — tie-break 용(같으면 익절 축 우선은 호출부 정렬 순서로). */
        fun distanceFromCurrent(): Int = abs(TAKE_PROFITS.indexOf(tp) - TAKE_PROFITS.indexOf(5.0)) + abs(STOP_LOSSES.indexOf(sl) - STOP_LOSSES.indexOf(5.0))
    }

    private data class EntryKey(val market: String, val entryDate: String, val entryPrice: Double)

    private class WindowData(val label: String, val dir: String, val daily: Map<String, List<Candle>>, val intraday: Map<String, List<Candle>>) {
        /** 시간순 index ≥ 워밍업이고 그 마켓에 240분봉이 있는 거래일 — 창 frame 은 마켓 합집합. */
        val frameDays: List<String>
        val skippedMarketDays: Int
        /** 마켓별 거래구간 단순보유(시간순 index 50 시가 → 마지막 종가) %. */
        val buyAndHold: Map<String, Double>
        val buyAndHoldMedian: Double
        init {
            val days = sortedSetOf<String>()
            var skipped = 0
            val bh = LinkedHashMap<String, Double>()
            for ((market, newestFirst) in daily) {
                val ch = newestFirst.reversed()
                val barDays = intraday.getValue(market).map { it.candleDateTimeUtc.substring(0, 10) }.toSet()
                for (i in BacktestEngine.MIN_CANDLES until ch.size) {
                    val d = ch[i].candleDateTimeKst.substring(0, 10)
                    if (d in barDays) days += d else skipped++
                }
                val start = ch[BacktestEngine.MIN_CANDLES]
                bh[market] = (ch.last().tradePrice - start.openingPrice) / start.openingPrice * 100.0
            }
            frameDays = days.toList()
            skippedMarketDays = skipped
            buyAndHold = bh
            val s = bh.values.sorted()
            buyAndHoldMedian = (s[s.size / 2] + s[(s.size - 1) / 2]) / 2
        }
        fun key(date: String) = "$dir/$date"
    }

    private class Paired(
        val deltaByEntryKey: Map<EntryKey, Double>,
        val exitPriceChanged: Int,
        val trades: List<LiveSemanticsArm.Trade>,
    )

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_TP_SL_GRID", matches = "true")
    fun `pre-registered take-profit x stop-loss comparison on ten windows`() = runBlocking {
        val windows = (BacktestFixtures.TIME_INDEPENDENT + BacktestFixtures.EXPANSION_2020_2023).map { r ->
            val daily = BacktestFixtures.loadAll(r)
            WindowData(r.label, r.dir, daily, IntradayFixtures.loadAll(r.dir, daily.keys))
        }
        val cells = TAKE_PROFITS.flatMap { tp -> STOP_LOSSES.map { sl -> CellKey(tp, sl) } }
        val candidates = cells.filter { it != CURRENT }
        val frame = PairedMaxTBootstrap.Frame(windows.map { w -> w.frameDays.map { w.key(it) } })

        // 단순보유 창 분류·frame 상수가 사전고정과 같은지(배관 7c) — 데이터 속성이라 결과와 무관하다.
        val bhNeg = windows.filter { it.buyAndHoldMedian < 0 }.map { it.dir }.toSet()
        assertEquals(PREREGISTERED_BH_NEG, bhNeg, "단순보유 창 분류가 사전고정 표와 다르다")
        windows.forEach { assertEquals(EXPECTED_DAYS_PER_WINDOW, it.frameDays.size, "${it.label}: frame 일수") }
        assertEquals(EXPECTED_FRAME_DAYS, frame.size, "frame 총 일수")
        assertEquals(0, windows.sumOf { it.skippedMarketDays }, "결측 마켓-일")

        // ── 셀 실행: 20셀 × 2 처리(진입봉 손절 저가/종가) × 10창 ──
        suspend fun runCell(cell: CellKey, onClose: Boolean, w: WindowData): List<LiveSemanticsArm.Trade> {
            val config = StrategySearchGrid.currentLivePoint().copy(takeProfitPct = cell.tp, maxLossPct = cell.sl).toConfig()
            val out = ArrayList<LiveSemanticsArm.Trade>()
            for ((market, newestFirst) in w.daily) {
                out += LiveSemanticsArm.run(
                    market, strategy, newestFirst.reversed(), w.intraday.getValue(market).reversed(),
                    config, props, entryBarStopOnClose = onClose,
                )
            }
            return out
        }
        val runs = HashMap<Triple<CellKey, Boolean, String>, List<LiveSemanticsArm.Trade>>()
        for (cell in cells) for (onClose in listOf(false, true)) for (w in windows) {
            runs[Triple(cell, onClose, w.dir)] = runCell(cell, onClose, w)
        }
        println("[tp-sl] 셀 실행 완료: ${runs.size} runs")
        assertEquals(EXPECTED_BASELINE_TRADES, windows.sumOf { runs.getValue(Triple(CURRENT, false, it.dir)).size }, "기준선 거래수(배관 7c)")

        // ── 페어링(진입 키 1:1) + 배관 단정 ──
        fun pair(cell: CellKey, onClose: Boolean, w: WindowData): Paired {
            val base = runs.getValue(Triple(CURRENT, onClose, w.dir)).associateBy { EntryKey(it.market, it.entryDate, it.entryPrice) }
            val mine = runs.getValue(Triple(cell, onClose, w.dir))
            val mineByKey = mine.associateBy { EntryKey(it.market, it.entryDate, it.entryPrice) }
            assertEquals(mine.size, mineByKey.size, "${cell.label} ${w.label}: 진입 키 중복")
            assertEquals(base.keys, mineByKey.keys, "${cell.label} ${w.label}: 진입 집합이 기준과 다르다 (사전고정 7a)")
            var changed = 0
            val delta = LinkedHashMap<EntryKey, Double>()
            for ((k, t) in mineByKey) {
                val b = base.getValue(k)
                delta[k] = t.netPnlPct - b.netPnlPct
                if (abs(t.exitPrice - b.exitPrice) > 1e-9) changed++
            }
            return Paired(delta, changed, mine)
        }
        val paired = HashMap<Triple<CellKey, Boolean, String>, Paired>()
        for (cell in cells) for (onClose in listOf(false, true)) for (w in windows) paired[Triple(cell, onClose, w.dir)] = pair(cell, onClose, w)

        for (cell in cells) for (onClose in listOf(false, true)) {
            val all = windows.flatMap { runs.getValue(Triple(cell, onClose, it.dir)) }
            if (cell.tp >= OFF) assertEquals(0, all.count { it.reason == "TAKE_PROFIT" }, "${cell.label}: 익절 off 인데 TAKE_PROFIT (7b)")
            if (cell.sl >= OFF) assertEquals(0, all.count { it.reason == "STOP_LOSS" }, "${cell.label}: 손절 off 인데 STOP_LOSS (7b)")
        }
        // 한도봉(진입 다음 날 첫 봉)의 TP/SL — 봉 시가가 직전 봉 범위 밖으로 뛰면 발동하고 체결가는 시가가 아니라 임계선이다. 진단(사전고정 7 단정 아님).
        data class LimitBar(val count: Int, val openMinusFillPct: Double)
        fun limitBar(cell: CellKey): LimitBar {
            val t = windows.flatMap { runs.getValue(Triple(cell, false, it.dir)) }
                .filter { it.exitDate > it.entryDate && (it.reason == "TAKE_PROFIT" || it.reason == "STOP_LOSS") }
            return LimitBar(t.size, t.sumOf { (it.exitBarOpen - it.exitPrice) / it.entryPrice * 100.0 })
        }

        // ── 기여 배열(frame 정렬): 주 family 19 · 감도 family 19 · bhPos/bhNeg 마스크 19×2 ──
        fun contributions(cell: CellKey, onClose: Boolean, windowFilter: (WindowData) -> Boolean = { true }): DoubleArray {
            val byKey = HashMap<String, Double>()
            for (w in windows.filter(windowFilter)) {
                for ((k, d) in paired.getValue(Triple(cell, onClose, w.dir)).deltaByEntryKey) {
                    byKey.merge(w.key(k.entryDate), d, Double::plus)
                }
            }
            return frame.align(byKey)
        }
        fun pairedCount(cell: CellKey, onClose: Boolean, windowFilter: (WindowData) -> Boolean = { true }) =
            windows.filter(windowFilter).sumOf { paired.getValue(Triple(cell, onClose, it.dir)).deltaByEntryKey.size }

        val primaryArr = candidates.map { contributions(it, false) }
        val sensArr = candidates.map { contributions(it, true) }
        val posArr = candidates.map { c -> contributions(c, false) { it.dir !in bhNeg } }
        val negArr = candidates.map { c -> contributions(c, false) { it.dir in bhNeg } }
        val sums = PairedMaxTBootstrap.resampleSums(frame, primaryArr + sensArr + posArr + negArr)
        val n = candidates.size
        val primaryG = DoubleArray(n) { primaryArr[it].sum() }
        val primary = PairedMaxTBootstrap.maxT(primaryG, sums.copyOfRange(0, n))
        val sens = PairedMaxTBootstrap.maxT(DoubleArray(n) { sensArr[it].sum() }, sums.copyOfRange(n, 2 * n))
        val pos = candidates.indices.map { PairedMaxTBootstrap.interval(posArr[it].sum(), sums[2 * n + it]) }
        val neg = candidates.indices.map { PairedMaxTBootstrap.interval(negArr[it].sum(), sums[3 * n + it]) }
        // 보고 전용 — 블록 길이 감도(사전고정 5): L ∈ {1, 10}
        val blockSens = listOf(1, 10).associateWith { L ->
            PairedMaxTBootstrap.maxT(primaryG, PairedMaxTBootstrap.resampleSums(frame, primaryArr, block = L))
        }
        println("[tp-sl] maxT q(primary)=%.3f q(sens)=%.3f q(L1)=%.3f q(L10)=%.3f".format(primary.q, sens.q, blockSens.getValue(1).q, blockSens.getValue(10).q))

        // 선행 정의(청산일 합집합 frame 꼬리비율) — 비교용
        fun legacy(cell: CellKey): DateBlockBootstrap.Result {
            val mine = windows.flatMap { runs.getValue(Triple(cell, false, it.dir)) }
            val base = windows.flatMap { runs.getValue(Triple(CURRENT, false, it.dir)) }
            val dates = (mine.map { it.exitDate } + base.map { it.exitDate }).distinct()
            return DateBlockBootstrap.of(dates.associateWith { d ->
                mine.filter { it.exitDate == d }.sumOf { it.netPnlPct } - base.filter { it.exitDate == d }.sumOf { it.netPnlPct }
            })
        }

        // ── 진단: 창별 격차·최악 창·LOWO·탑 생존자 제거·Spearman·유효 N·진입봉 SL ──
        fun windowGap(cell: CellKey, w: WindowData) = paired.getValue(Triple(cell, false, w.dir)).deltaByEntryKey.values.sum()
        fun gapPerTrade(cell: CellKey, filter: (WindowData) -> Boolean = { true }): Double {
            val g = windows.filter(filter).sumOf { windowGap(cell, it) }
            val cnt = pairedCount(cell, false, filter)
            return if (cnt == 0) 0.0 else g / cnt
        }
        data class Diag(
            val effectiveN: Int, val entryBarStops: Int, val entryBarStopPnl: Double,
            val stops: Int, val stopOvershootPct: Double,
            val worstWindow: String, val worstWindowGapPerTrade: Double,
            val lowoMin: Double, val lowoWindow: String,
            val lowoNegMin: Double, val lowoNegWindow: String,
            val topSurvivorRemoved: Double, val spearman: Double,
        )
        fun diag(cell: CellKey): Diag {
            val all = windows.flatMap { runs.getValue(Triple(cell, false, it.dir)) }
            val effN = windows.sumOf { paired.getValue(Triple(cell, false, it.dir)).exitPriceChanged }
            val ebs = all.filter { it.exitOnEntryBar && it.reason == "STOP_LOSS" }
            val stops = all.filter { it.reason == "STOP_LOSS" && !it.exitBarLow.isNaN() }
            val overshoot = if (stops.isEmpty()) 0.0 else stops.map { (it.exitPrice - it.exitBarLow) / it.entryPrice * 100.0 }.average()
            val perWindow = windows.map { w ->
                val cnt = paired.getValue(Triple(cell, false, w.dir)).deltaByEntryKey.size
                w to (if (cnt == 0) 0.0 else windowGap(cell, w) / cnt)
            }
            val worst = perWindow.minByOrNull { it.second }!!
            val lowo = windows.map { ex -> ex to gapPerTrade(cell) { it.dir != ex.dir } }.minByOrNull { it.second }!!
            val negWindows = windows.filter { it.dir in bhNeg }
            val lowoNeg = negWindows.map { ex -> ex to gapPerTrade(cell) { it.dir in bhNeg && it.dir != ex.dir } }.minByOrNull { it.second }!!
            // 창별 단순보유 최고 마켓 제거
            var g = 0.0; var cnt = 0
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (w in windows) {
                val top = w.buyAndHold.maxByOrNull { it.value }!!.key
                val p = paired.getValue(Triple(cell, false, w.dir))
                for ((k, d) in p.deltaByEntryKey) if (k.market != top) { g += d; cnt++ }
                for ((market, bh) in w.buyAndHold) {
                    xs += bh
                    ys += p.deltaByEntryKey.filterKeys { it.market == market }.values.sum()
                }
            }
            return Diag(
                effN, ebs.size, ebs.sumOf { it.netPnlPct }, stops.size, overshoot,
                worst.first.label, worst.second, lowo.second, lowo.first.label, lowoNeg.second, lowoNeg.first.label,
                if (cnt == 0) 0.0 else g / cnt,
                PairedMaxTBootstrap.spearman(xs.toDoubleArray(), ys.toDoubleArray()),
            )
        }
        val diags = candidates.associateWith { diag(it) }

        // 지문(7d) — 청산 집합이 같은 셀은 동일 행동
        val fingerprint = cells.associateWith { c ->
            windows.flatMap { w -> runs.getValue(Triple(c, false, w.dir)).map { "${w.dir}|${it.market}|${it.entryDate}|${it.exitDate}|${it.exitPrice}|${it.reason}" } }.sorted().joinToString("\n")
        }
        val sameBehaviour = cells.groupBy { fingerprint.getValue(it) }.values.filter { it.size > 1 }

        // yearly·bear 진단(주 처리만, 격차/거래)
        suspend fun diagWindow(label: String, dir: String, daily: Map<String, List<Candle>>): Map<CellKey, Double> {
            val intraday = IntradayFixtures.loadAll(dir, daily.keys)
            val w = WindowData(label, dir, daily, intraday)
            val base = runCell(CURRENT, false, w).associateBy { EntryKey(it.market, it.entryDate, it.entryPrice) }
            return cells.associateWith { c ->
                val mine = runCell(c, false, w)
                val g = mine.sumOf { t ->
                    val b = requireNotNull(base[EntryKey(t.market, t.entryDate, t.entryPrice)]) { "$label ${c.label}: 진입 집합이 기준과 다르다" }
                    t.netPnlPct - b.netPnlPct
                }
                if (mine.isEmpty()) 0.0 else g / mine.size
            }
        }
        val yearlyDiag = diagWindow("1년 전체 (yearly)", "yearly", YearlyFixtures.loadAll())
        val bearDiag = BacktestFixtures.Regime.BEAR.let { diagWindow(it.label, it.dir, BacktestFixtures.loadAll(it)) }

        // ── 후보 판정(사전고정 8) ──
        val idx = candidates.withIndex().associate { it.value to it.index }
        fun totalTrades(cell: CellKey) = pairedCount(cell, false)
        data class Verdict(val pass: Boolean, val candidate: Boolean, val notes: List<String>)
        val verdicts = candidates.associateWith { c ->
            val i = idx.getValue(c)
            val p = primary.cells[i]; val s = sens.cells[i]
            val perTrade = p.g / totalTrades(c)
            val notes = ArrayList<String>()
            var cand = p.pass
            if (p.se == 0.0) notes += "기준과 동일 행동"
            if (p.pass && !s.pass) { notes += "진입봉-민감"; cand = false }
            if (p.pass && perTrade < ECONOMIC_FLOOR) { notes += "경제 하한 미달"; cand = false }
            if (p.pass && neg[i].ciHigh < 0) { notes += "하락 창에서 유의하게 열세"; cand = false }
            if (p.pass && c.tp > 5.0) { notes += "익절 8·off — 전향 검증 필요(후보 불가)"; cand = false }
            if (p.pass && c.tp < 5.0) notes += "익절 축 편향 역방향 통과"
            if (p.pass && c.sl != 5.0) notes += "손절 축은 양방향 편향 — 단독으로 강하지 않음"
            Verdict(p.pass, cand, notes)
        }
        val ranked = candidates.filter { verdicts.getValue(it).candidate }
            .sortedWith(compareByDescending<CellKey> { primary.cells[idx.getValue(it)].lowerBound / totalTrades(it) }.thenBy { it.distanceFromCurrent() }.thenBy { TAKE_PROFITS.indexOf(it.tp) })

        // ── 리포트 ──
        val out = StringBuilder()
        out.appendLine("# 익절 × 손절 사전명세 비교 — 10창, 라이브 의미론 240분봉, 진입일 페어링 maxT")
        out.appendLine()
        out.appendLine("규칙은 결과를 보기 전에 커밋했다(plan `2026-09-09-tp-sl-grid` `# Acceptance` 1~12). 기준 = 현행 라이브 TP5/SL5/트레일1.5/arm0/k0.5/h1.")
        out.appendLine("확증 실험이 아니다 — 10창은 이미 트레일링 판정에 쓰였고 익절·손절 값은 옛 일봉 격자의 축이다. 통과 셀도 이 측정만으로는 승격되지 않는다.")
        out.appendLine()
        out.appendLine("frame: 창별 워밍업 이후 거래일 ${frame.size}일(마켓-일 결측 ${windows.sumOf { it.skippedMarketDays }}건은 그 마켓 진입 불가로만 작용), 블록 ${PairedMaxTBootstrap.BLOCK}일, B=${PairedMaxTBootstrap.RESAMPLES}, seed ${PairedMaxTBootstrap.SEED}.")
        out.appendLine("maxT 95% 임계 q — 주 family ${"%.3f".format(primary.q)}, 진입봉 감도 family ${"%.3f".format(sens.q)}. 통과 = G/se > q (동시 95% 하한 > 0).")
        out.appendLine()

        val baseTrades = windows.flatMap { runs.getValue(Triple(CURRENT, false, it.dir)) }
        val baseSum = baseTrades.sumOf { it.netPnlPct }
        out.appendLine("기준(현행) 10창 pooled: ${baseTrades.size}건, Σpnl ${"%+.2f".format(baseSum)}%p, ${"%,.0f".format(baseSum * notionalKrw / 100)}원.")
        out.appendLine()

        fun cellSum(c: CellKey) = windows.flatMap { runs.getValue(Triple(c, false, it.dir)) }.sumOf { it.netPnlPct }
        out.appendLine("## 1. 4×5 행렬 — Σpnl %p (10창 pooled) · 격차/거래 %p · 통과")
        out.appendLine()
        out.appendLine("| 익절 \\ 손절 | " + STOP_LOSSES.joinToString(" | ") { "SL " + (if (it >= OFF) "off" else "%.0f".format(it)) } + " |")
        out.appendLine("|---" + "|---".repeat(STOP_LOSSES.size) + "|")
        for (tp in TAKE_PROFITS) {
            out.appendLine("| TP ${if (tp >= OFF) "off" else "%.0f".format(tp)} | " + STOP_LOSSES.joinToString(" | ") { sl ->
                val c = CellKey(tp, sl)
                if (c == CURRENT) "%+.2f (기준)".format(cellSum(c)) else {
                    val i = idx.getValue(c)
                    val mark = if (primary.cells[i].pass) " **통과**" else ""
                    "%+.2f · %+.3f/건%s".format(cellSum(c), primary.cells[i].g / totalTrades(c), mark)
                }
            } + " |")
        }
        out.appendLine()

        out.appendLine("## 2. 19셀 판정표 (전부 싣는다)")
        out.appendLine()
        out.appendLine("| 셀 | 거래 | 격차 %p | 격차/거래 | se | T | 동시95%하한 | 한계p | 통과 | L1 · L10 통과 | 감도(진입봉 종가) 통과 · 격차 | 선행정의 P(G≤0) | 후보 | 비고 |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (c in candidates) {
            val i = idx.getValue(c); val p = primary.cells[i]; val s = sens.cells[i]; val v = verdicts.getValue(c); val lg = legacy(c)
            out.appendLine("| %s | %d | %+.2f | %+.3f | %.2f | %.2f | %+.2f | %.4f | %s | %s · %s | %s · %+.2f | %.4f | %s | %s |".format(
                c.label, totalTrades(c), p.g, p.g / totalTrades(c), p.se, p.t, p.lowerBound, p.marginalP,
                if (p.pass) "**통과**" else "—",
                if (blockSens.getValue(1).cells[i].pass) "통과" else "—", if (blockSens.getValue(10).cells[i].pass) "통과" else "—",
                if (s.pass) "통과" else "—", s.g, lg.pLeZero,
                if (v.candidate) "**후보**" else "—", v.notes.joinToString("; ")))
        }
        out.appendLine()
        out.appendLine("후보 조건(사전고정 8): 통과 ∧ 격차/거래 ≥ %.2f ∧ 감도 family 통과 ∧ bhNeg 95%% 상한 ≥ 0 ∧ 익절 ≤ 5. 후보 순서는 동시 하한/거래 내림차순(점추정 최대를 고르지 않는다 — winner's curse).".format(ECONOMIC_FLOOR))
        out.appendLine("검정력 맥락: 기준선 ${baseTrades.size}건 중 익절·손절이 실제로 청산가를 바꾸는 거래는 각 8%% 안팎이라, 전체 평균 ≥ %.2f%%p 는 영향 거래당 약 +1.2%%p 를 요구한다 — 통과 0 은 효과 부재가 아니라 검정력 부족일 수 있다.".format(ECONOMIC_FLOOR))
        out.appendLine("선행정의 열은 청산일 합집합 frame 의 길이 1 iid 재추출 꼬리비율(`DateBlockBootstrap` — 이름과 달리 블록이 아니다)이며 비교용이다.")
        out.appendLine(if (ranked.isEmpty()) "**후보 없음**" + (if (candidates.none { verdicts.getValue(it).pass }) " — 통과 셀 0: 현행 TP5/SL5 유지, 이 격자·계기에서 바꿀 근거 없음." else "") else "**후보 순서**: " + ranked.joinToString(" → ") { it.label })
        if (sameBehaviour.isNotEmpty()) out.appendLine("동일 행동 셀(지문 일치): " + sameBehaviour.joinToString(" · ") { g -> g.joinToString("=") { it.label } })
        out.appendLine()

        out.appendLine("## 3. 생존편향·강건성 진단 (전 셀)")
        out.appendLine()
        out.appendLine("단순보유(거래구간) 중앙값: " + windows.joinToString(" · ") { "${it.label} ${"%+.1f".format(it.buyAndHoldMedian)}" } + "; bhNeg = ${bhNeg.sorted()}")
        out.appendLine()
        out.appendLine("| 셀 | bhPos 격차/거래 [95%] | bhNeg 격차/거래 [95%] | LOWO 최소 (제외 창) | bhNeg LOWO 최소 (제외 창) | 탑 생존자 제거 | Spearman ρ | 유효 N | 진입봉 SL 건수 · pnl | SL 건수 · 평균 오버슛 %p | 최악 창 (격차/거래) |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (c in candidates) {
            val i = idx.getValue(c); val d = diags.getValue(c)
            val nPos = pairedCount(c, false) { it.dir !in bhNeg }; val nNeg = pairedCount(c, false) { it.dir in bhNeg }
            out.appendLine("| %s | %+.3f [%+.3f, %+.3f] | %+.3f [%+.3f, %+.3f] | %+.3f (%s) | %+.3f (%s) | %+.3f | %+.2f | %d | %d · %+.1f | %d · %.3f | %s (%+.3f) |".format(
                c.label, pos[i].g / nPos, pos[i].ciLow / nPos, pos[i].ciHigh / nPos, neg[i].g / nNeg, neg[i].ciLow / nNeg, neg[i].ciHigh / nNeg,
                d.lowoMin, d.lowoWindow, d.lowoNegMin, d.lowoNegWindow, d.topSurvivorRemoved, d.spearman, d.effectiveN, d.entryBarStops, d.entryBarStopPnl,
                d.stops, d.stopOvershootPct, d.worstWindow, d.worstWindowGapPerTrade))
        }
        out.appendLine()
        out.appendLine("단위: 격차/거래 %p. LOWO = 창 하나 제외 시 pooled 격차/거래 최소값. 탑 생존자 제거 = 창별 단순보유 최고 마켓 제외. ρ 는 (마켓, 창) 80점의 단순보유 ↔ 격차 합. 오버슛 = (손절선 − 봉 저가)/진입가 평균 — 무슬리피지 체결 편향의 상한.")
        out.appendLine()

        out.appendLine("## 4. 10창 × 19셀 격차 %p (판정에 쓰지 않는다)")
        out.appendLine()
        out.appendLine("| 창 | 기준 Σpnl (건) | " + candidates.joinToString(" | ") { it.label } + " |")
        out.appendLine("|---|---" + "|---".repeat(candidates.size) + "|")
        for (w in windows) {
            val b = runs.getValue(Triple(CURRENT, false, w.dir))
            out.appendLine("| %s | %+.2f (%d) | ".format(w.label, b.sumOf { it.netPnlPct }, b.size) + candidates.joinToString(" | ") { "%+.2f".format(windowGap(it, w)) } + " |")
        }
        out.appendLine()

        out.appendLine("## 5. 청산 사유 구성 (20셀, 10창 pooled)")
        out.appendLine()
        out.appendLine("| 셀 | " + REASONS.joinToString(" | ") + " | 한도봉 TP/SL 건수 · Σ(시가−체결가)/진입가 %p |")
        out.appendLine("|---" + "|---".repeat(REASONS.size + 1) + "|")
        for (c in cells) {
            val t = windows.flatMap { runs.getValue(Triple(c, false, it.dir)) }
            val lb = limitBar(c)
            out.appendLine("| %s | %s | %d · %+.2f |".format(c.label, REASONS.joinToString(" | ") { r -> val g = t.filter { it.reason == r }; "%d건 %+.1f".format(g.size, g.sumOf { it.netPnlPct }) },
                lb.count, lb.openMinusFillPct))
        }
        out.appendLine()
        out.appendLine("한도봉 TP/SL 은 봉 시가가 직전 봉 범위 밖으로 뛴 날에만 나며 체결가가 시가 대신 임계선이다(익절은 보수, 손절은 낙관). 마지막 열이 시가 체결이었다면의 누적 차이(양수 = 모델이 그만큼 과소).")
        out.appendLine()

        out.appendLine("## 6. yearly · bear 진단 — 격차/거래 %p (가설 창·중복 창, 판정 아님)")
        out.appendLine()
        out.appendLine("| 익절 \\ 손절 | " + STOP_LOSSES.joinToString(" | ") { "SL " + (if (it >= OFF) "off" else "%.0f".format(it)) } + " |")
        out.appendLine("|---" + "|---".repeat(STOP_LOSSES.size) + "|")
        for (tp in TAKE_PROFITS) out.appendLine("| TP ${if (tp >= OFF) "off" else "%.0f".format(tp)} (yearly / bear) | " + STOP_LOSSES.joinToString(" | ") { sl -> val c = CellKey(tp, sl); "%+.3f / %+.3f".format(yearlyDiag.getValue(c), bearDiag.getValue(c)) } + " |")
        out.appendLine()

        out.appendLine("## 한계")
        out.appendLine()
        out.appendLine("- 익절 축 편향은 넓은 쪽 단방향(트레일링 stale-peak 로 포지션이 라이브보다 오래 산다). 손절 축은 양방향 — 진입 봉 저가는 진입 이전일 수 있고(좁은 손절에 불리, 감도 family 로 브래킷)," +
            " 임계선 무슬리피지 체결은 좁은 손절에 유리(브래킷 없음, 오버슛 열로 크기만 가늠).")
        out.appendLine("- 10창 중 7창은 하나의 사이클, 로스터는 2026년까지 살아남은 종목이라 생존편향은 측정 불가. §3 은 진단이지 보정이 아니다.")
        out.appendLine("- 익절 off·손절 off 는 라이브 env 로 설정은 되지만 `/api/strategy/backtest` 가 [0,100] 을 강제해 그 값으로는 백테스트 API 가 400 이 된다. 8 과 off 사이는 이 격자가 답하지 못한다.")

        val path = Path.of("build/reports/take-profit-stop-loss-intraday.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[tp-sl] 리포트: ${path.toAbsolutePath()}")
        assertTrue(out.contains("19셀 판정표"))
    }

    private companion object {
        const val OFF = StrategySearchGrid.TAKE_PROFIT_OFF
        val TAKE_PROFITS = listOf(3.0, 5.0, 8.0, OFF)
        val STOP_LOSSES = listOf(3.0, 5.0, 7.0, 10.0, OFF)
        val CURRENT = CellKey(5.0, 5.0)
        /** 왕복수수료 1회분 — 사전고정 8(b). */
        const val ECONOMIC_FLOOR = 0.10
        /** 사전고정 9 표(거래구간 단순보유 중앙값 < 0). */
        val PREREGISTERED_BH_NEG = setOf("bull", "p2021h1", "p2021h2", "p2022h1")
        /** 사전고정 4 의 기대 상수(fixture 실측). */
        const val EXPECTED_DAYS_PER_WINDOW = 150
        const val EXPECTED_FRAME_DAYS = 1_500
        const val EXPECTED_BASELINE_TRADES = 1_058
        val REASONS = listOf("TRAILING_STOP", "TAKE_PROFIT", "STOP_LOSS", "TIME_EXIT", "END")
    }
}
