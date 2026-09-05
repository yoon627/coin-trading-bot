package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 탐색 생존 후보(k0.3/TP off/SL7/트레일1.5·arm0/h1)의 **해부** — 우위가 어디서 오는지, 그 우위가 어떤 가정에
 * 얹혀 있는지를 스윕 리포트가 내지 않는 네 축으로 잰다.
 *
 * 스윕은 `priceGateShare`(TP+SL 비율)만 내고 TRAILING/TIME 분해를 하지 않아, 후보가 무엇을 하는 설정인지
 * 리포트만 봐서는 알 수 없었다. 그리고 마켓 8개를 독립 표본처럼 세는데 `yearly/` 는 상관이 높아 그렇지 않다.
 *
 * 실행: `RUN_CANDIDATE_ANATOMY=true ./gradlew :bot:test --tests "*CandidateAnatomyTest*" --rerun-tasks`
 */
class CandidateAnatomyTest {

    private val props = TradingProperties()
    private val notionalKrw = 100_000.0

    private data class Arm(val label: String, val point: SweepPoint)

    private data class Exit(val market: String, val date: String, val reason: String, val pnl: Double, val capped: Double)

    private fun live() = StrategySearchGrid.baselinePoint()

    /** 탐색 생존 후보 — 4축을 한꺼번에 바꾼다. */
    private fun candidateE() = live().copy(
        kValue = 0.3,
        takeProfitPct = StrategySearchGrid.TAKE_PROFIT_OFF,
        maxLossPct = 7.0,
        trailingStopPct = 1.5,
        trailingArmPct = 0.0,
    )

    /**
     * 후보 4축 중 **트레일링 1축만** 바꾼 변형. 나머지(익절 5·손절 5·k 0.5·보유 1)는 라이브 그대로다.
     * 후보의 우위가 어느 축에서 오는지 가르는 대조군이고, 자유도가 1이라 사후성이 가장 적다.
     */
    private fun variantA() = live().copy(trailingStopPct = 1.5, trailingArmPct = 0.0)

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_CANDIDATE_ANATOMY", matches = "true")
    fun `anatomy of the surviving candidate`() = runBlocking {
        val windows = buildList {
            add(Triple("1년 전체 (yearly)", YearlyFixtures.loadAll(), StrategySearch.Segment("전체", 0..364)))
            for (r in BacktestFixtures.TIME_INDEPENDENT) add(Triple(r.label, BacktestFixtures.loadAll(r), StrategySearch.REGIME))
            add(Triple(BacktestFixtures.Regime.BEAR.label, BacktestFixtures.loadAll(BacktestFixtures.Regime.BEAR), StrategySearch.REGIME))
        }
        val arms = listOf(
            Arm("라이브 현행", live()),
            Arm("변형 A · 트레일링 1축만", variantA()),
            Arm("생존 후보 E · 4축", candidateE()),
        )

        val out = StringBuilder()
        out.appendLine("# 생존 후보 해부 — 우위의 출처와 그것이 얹힌 가정")
        out.appendLine()
        out.appendLine("고정 노셔널 ${"%,.0f".format(notionalKrw)}원/마켓. `Σ 거래별 net pnl%`(왕복 수수료 차감) 기준.")
        out.appendLine()
        out.appendLine("## 0. 마켓 상관과 실효 독립 표본")
        out.appendLine()
        out.appendLine("게이트의 \"양수 마켓 6/8\" 조건은 8개가 독립일 때만 다수결이다. 일간 로그수익률 상관으로 실효 표본을 낸다.")
        out.appendLine()
        out.appendLine("| fixture | 마켓 | 평균 상관 | 최소 | 최대 | 실효 독립 표본 n_eff |")
        out.appendLine("|---|---|---|---|---|---|")
        for ((name, fx, _) in windows.distinctBy { it.first }) {
            val (mean, lo, hi, neff) = correlationStats(fx)
            out.appendLine("| %s | %d | %.3f | %.3f | %.3f | **%.2f** |".format(name, fx.size, mean, lo, hi, neff))
        }
        out.appendLine()

        for ((windowName, fixtures, segment) in windows) {
            val exits = LinkedHashMap<String, List<Exit>>()
            for (arm in arms) exits[arm.label] = collectExits(fixtures, segment, arm.point)

            out.appendLine("## $windowName")
            out.appendLine()
            out.appendLine("### 청산 사유 분해")
            out.appendLine()
            out.appendLine("| 설정 | 사유 | 건수 | Σpnl %p | 평균 %p |")
            out.appendLine("|---|---|---|---|---|")
            for (arm in arms) {
                val byReason = exits.getValue(arm.label).groupBy { it.reason }
                for (reason in REASON_ORDER) {
                    val rows = byReason[reason] ?: continue
                    out.appendLine("| %s | %s | %d | %+.2f | %+.3f |".format(
                        arm.label, reason, rows.size, rows.sumOf { it.pnl }, rows.sumOf { it.pnl } / rows.size))
                }
                val total = exits.getValue(arm.label)
                out.appendLine("| %s | **합계** | **%d** | **%+.2f** | |".format(arm.label, total.size, total.sumOf { it.pnl }))
            }
            out.appendLine()

            val liveExits = exits.getValue("라이브 현행")
            out.appendLine("### 라이브 대비 격차 — 날짜 블록 부트스트랩 (${DateBlockBootstrap.RESAMPLES}회, 블록 = 달력 날짜)")
            out.appendLine()
            out.appendLine("한 날짜에 여러 마켓이 함께 청산되면 그건 관측 여러 개가 아니라 **같은 베팅 하나**다.")
            out.appendLine("따라서 재추출 단위는 마켓이 아니라 청산 달력일이다.")
            out.appendLine()
            out.appendLine("| 설정 | Σpnl %p | 거래수 | 격차 %p | 5% 하한 | P(격차 ≤ 0) | 상위2일 제거 후 격차 | 봉투 격차 |")
            out.appendLine("|---|---|---|---|---|---|---|---|")
            for (arm in arms.drop(1)) {
                val e = exits.getValue(arm.label)
                val gap = e.sumOf { it.pnl } - liveExits.sumOf { it.pnl }
                val dates = (liveExits.map { it.date } + e.map { it.date }).distinct().sorted()
                val byDate = dates.associateWith { d ->
                    e.filter { it.date == d }.sumOf { it.pnl } - liveExits.filter { it.date == d }.sumOf { it.pnl }
                }
                val top2 = byDate.entries.sortedByDescending { it.value }.take(2).sumOf { it.value }
                val boot = DateBlockBootstrap.of(byDate)
                val capGap = e.sumOf { it.capped } - liveExits.sumOf { it.capped }
                out.appendLine("| %s | %+.2f | %d | **%+.2f** | %+.2f | **%.3f** | %+.2f | %+.2f |".format(
                    arm.label, e.sumOf { it.pnl }, e.size, gap, boot.p05, boot.pLeZero, gap - top2, capGap))
            }
            out.appendLine()
            out.appendLine("`상위2일 제거 후 격차` = 기여가 가장 큰 청산일 2일을 뺀 나머지. `봉투 격차` = 아래 라이브 체결 봉투 기준.")
            out.appendLine()

            out.appendLine("### 최대 기여 청산일")
            out.appendLine()
            out.appendLine("| 설정 | 날짜 | 격차 기여 %p | 격차 대비 | 그날 청산 마켓 수 |")
            out.appendLine("|---|---|---|---|---|")
            for (arm in arms.drop(1)) {
                val e = exits.getValue(arm.label)
                val gap = e.sumOf { it.pnl } - liveExits.sumOf { it.pnl }
                val dates = (liveExits.map { it.date } + e.map { it.date }).distinct()
                val byDate = dates.associateWith { d ->
                    e.filter { it.date == d }.sumOf { it.pnl } - liveExits.filter { it.date == d }.sumOf { it.pnl }
                }
                for ((d, v) in byDate.entries.sortedByDescending { it.value }.take(2)) {
                    out.appendLine("| %s | %s | %+.2f | %.0f%% | %d |".format(arm.label, d, v, v / gap * 100, e.count { it.date == d }))
                }
            }
            out.appendLine()

            out.appendLine("### 라이브 체결 봉투 — 트레일링이 진입 봉 장중에 걸렸다면 (하한)")
            out.appendLine()
            out.appendLine("D1 은 `armPeak` 이 **직전 봉까지의** 고점이라 진입 봉 장중 신고점을 못 본다(`IntrabarExitModel`).")
            out.appendLine("라이브는 10초마다 peak 을 갱신하므로 **D1 이 발동하는 경우는 라이브에서도 전부 발동한다**(포함관계).")
            out.appendLine("진입 봉 OHLC 만으로 \"라이브였다면 그 봉 안에서 트레일링이 걸릴 수 있었다\"를 판정하고,")
            out.appendLine("걸렸다면 **가능한 최악의 체결가**(최소 arm 직후 즉시 반락)로 눌러 하한을 만든다.")
            out.appendLine()
            out.appendLine("| 설정 | D1 Σpnl | 봉투 대상 | 봉투 Σpnl | 금액(D1) | 금액(봉투) |")
            out.appendLine("|---|---|---|---|---|---|")
            for (arm in arms) {
                val e = exits.getValue(arm.label)
                val d1 = e.sumOf { it.pnl }
                val cap = e.sumOf { it.capped }
                out.appendLine("| %s | %+.2f | %d/%d | %+.2f | %s원 | %s원 |".format(
                    arm.label, d1, e.count { it.capped != it.pnl }, e.size, cap,
                    "%,.0f".format(d1 * notionalKrw / 100.0), "%,.0f".format(cap * notionalKrw / 100.0)))
            }
            out.appendLine()
        }

        out.appendLine("## 호가단위 — 트레일링 체결가를 실제 격자에 올리면")
        out.appendLine()
        out.appendLine("백테는 청산가로 임계선의 실수값을 그대로 쓴다. 실거래는 호가 위에서만 체결되고, 매도는 격자 **아래**로 내려간다.")
        out.appendLine("틱이 굵은 저가 코인에서는 이 반올림 하나가 트레일링 이익을 통째로 먹는다.")
        out.appendLine()
        out.appendLine(tickImpact(arms, YearlyFixtures.loadAll(), StrategySearch.Segment("전체", 0..364)))
        out.appendLine()
        out.appendLine("## 사전고정 게이트 재적용 — 변형 A 는 새 가설이 아니라 **이미 탈락한 좌표**다")
        out.appendLine()
        out.appendLine("`armValuesFor(1.5)` 가 `arm=0.0` 을 항상 포함하므로(`StrategySearchGrid.kt`) 변형 A 는 Stage A 의")
        out.appendLine("51,480 좌표 안에 실재했고 생존 3건에 들지 못했다. 어느 게이트에서 죽었는지를 같은 게이트 객체로 재적용해 확인한다.")
        out.appendLine()
        out.appendLine(gateAudit(arms.drop(1)))
        out.appendLine()
        out.appendLine("## 한계")
        out.appendLine()
        out.appendLine("- 변형 A 는 홀드아웃 요약을 **본 뒤에** 만든 대조군이다 — 사후 분석이며 확증이 아니다.")
        out.appendLine("- 봉투는 **하한**이지 추정치가 아니다. 진입 봉의 고가·저가 순서를 모르므로 후보에 가장 불리한 순서를 가정한다.")
        out.appendLine("  실제 값은 D1(상한)과 봉투(하한) 사이에 있고, 어디인지는 일중 데이터 없이는 모른다.")
        out.appendLine("- 부트스트랩은 날짜를 교환가능(exchangeable)하다고 본다. 국면 추세가 있으면 그 가정이 낙관이다.")
        out.appendLine("- 슬리피지·부분체결·호가단위는 어느 열에도 없다.")

        val path = Path.of("build/reports/candidate-anatomy.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[anatomy] 리포트: ${path.toAbsolutePath()}")
        assertTrue(out.contains("날짜 블록 부트스트랩"))
    }

    /** 청산 체결가를 [UpbitTickSize] 격자로 내렸을 때의 Σpnl 변화. 트레일링 청산에 가장 크게 걸린다. */
    private suspend fun tickImpact(
        arms: List<Arm>,
        fixtures: Map<String, List<Candle>>,
        segment: StrategySearch.Segment,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("| 설정 | 청산 | Σpnl %p | 호가 반영 후 | 차이 | 트레일링 청산 평균 틱/가격 |")
        sb.appendLine("|---|---|---|---|---|---|")
        for (arm in arms) {
            val config = arm.point.toConfig()
            val feePct = config.feeRate * 2 * 100
            var raw = 0.0
            var snapped = 0.0
            var trailN = 0
            var trailTickPct = 0.0
            var n = 0
            for ((market, newestFirst) in fixtures) {
                val chronological = newestFirst.reversed()
                val input = chronological.subList(segment.inputRange.first, segment.inputRange.last + 1)
                val engine = BacktestEngine(listOf(YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == arm.point.strategy }), props)
                val m = SwingMetrics.measure(engine, arm.point.strategy, market, input, BacktestEngine.MIN_CANDLES, config)
                for (t in m.trades) {
                    n++
                    raw += t.pnlPercent
                    // 매수는 격자 위로 올라가고(불리) 매도는 아래로 내려간다(불리) — 양쪽 다 반영한다.
                    val buy = UpbitTickSize.of(t.buyPrice).let { Math.ceil(t.buyPrice / it) * it }
                    val sell = UpbitTickSize.roundSellDown(t.sellPrice)
                    snapped += (sell - buy) / buy * 100.0 - feePct
                    if (t.reason == "TRAILING_STOP") {
                        trailN++
                        trailTickPct += UpbitTickSize.of(t.sellPrice) / t.sellPrice * 100.0
                    }
                }
            }
            sb.appendLine("| %s | %d | %+.2f | %+.2f | **%+.2f** | %s |".format(
                arm.label, n, raw, snapped, snapped - raw,
                if (trailN == 0) "—" else "%.3f%% (%d건)".format(trailTickPct / trailN, trailN)))
        }
        sb.appendLine()
        sb.appendLine("매수는 격자 위로, 매도는 아래로 올린다 — 양쪽 다 불리한 방향이고 이것이 **최소** 마찰이다(스프레드·부분체결은 별도).")
        return sb.toString()
    }

    /** 사전고정 게이트(G1·G2·G4a·G4b·G5·G6·G7·G3)를 후보에 그대로 재적용한다. 상수는 `StrategySearchGates` 가 소유한다. */
    private suspend fun gateAudit(candidates: List<Arm>): String {
        val search = StrategySearch()
        val yearly = YearlyFixtures.loadAll()
        val base = live()
        val points = (candidates.map { it.point } + base).distinct()
        val select = search.measure(yearly, StrategySearch.SELECT, points)
        val validate = search.measure(yearly, StrategySearch.VALIDATE, points)
        val holdouts = BacktestFixtures.TIME_INDEPENDENT.associateWith {
            search.measure(BacktestFixtures.loadAll(it), StrategySearch.REGIME, points)
        }
        val bear = search.measure(BacktestFixtures.loadAll(BacktestFixtures.Regime.BEAR), StrategySearch.REGIME, points)
        val fees = listOf(0.001, 0.002).associateWith {
            search.measure(yearly, StrategySearch.SELECT, points, StrategySearch.Options(feeRate = it))
        }
        // G3 는 이웃의 G1+G5 통과 비율 — 후보의 한 스텝 이웃만 따로 측정한다(전 그리드 대신).
        val grid = StrategySearchGrid.stageA()
        val neighbourPoints = candidates.flatMap { grid.neighbours(it.point) }.distinct()
        val neighbourSelect = if (neighbourPoints.isEmpty()) emptyMap() else
            search.measure(yearly, StrategySearch.SELECT, neighbourPoints + base)

        fun deltas(m: Map<SweepPoint, StrategySearch.Metrics>, p: SweepPoint) =
            StrategySearchGates.pairedDeltas(m.getValue(p).returnByMarket, m.getValue(base).returnByMarket)
        fun mdd(m: Map<SweepPoint, StrategySearch.Metrics>, p: SweepPoint) =
            StrategySearchGates.pairedDeltas(m.getValue(p).mddByMarket, m.getValue(base).mddByMarket)

        val sb = StringBuilder()
        sb.appendLine("| 설정 | G5 표본 | G1 선택창 | G3 plateau | G6 낙폭 | G2 검증창 | G4a 3국면 | G4b bear | G7 비용 |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|")
        for (arm in candidates) {
            val p = arm.point
            val s = select.getValue(p)
            val g5 = StrategySearchGates.g5(s.trades, s.zeroTradeMarkets)
            val d = deltas(select, p)
            val g1 = StrategySearchGates.g1(d)
            val g1Pass = (neighbourSelect.keys + select.keys).filter { q ->
                val m = (neighbourSelect[q] ?: select[q])!!
                StrategySearchGates.g5(m.trades, m.zeroTradeMarkets) &&
                    StrategySearchGates.g1(StrategySearchGates.pairedDeltas(m.returnByMarket, select.getValue(base).returnByMarket))
            }.toHashSet()
            val neigh = grid.neighbours(p)
            val g3 = StrategySearchGates.plateau(neigh, g1Pass)
            val g6 = StrategySearchGates.g6(mdd(select, p), s.worstMdd, select.getValue(base).worstMdd)
            val g2 = StrategySearchGates.g2(deltas(validate, p))
            val g4a = holdouts.values.all { StrategySearchGates.g4(deltas(it, p)) }
            val g4b = StrategySearchGates.g4(deltas(bear, p))
            val g7 = fees.values.all { StrategySearchGates.g1(deltas(it, p)) }
            fun mark(ok: Boolean) = if (ok) "통과" else "**탈락**"
            sb.appendLine("| %s | %s (%d건) | %s (중앙 %+.2f, 양수 %d/8) | %s (%d/%d) | %s | %s | %s | %s | %s |".format(
                arm.label, mark(g5), s.trades, mark(g1), SwingMetrics.median(d), d.count { it > 0 },
                mark(g3), neigh.count { it in g1Pass }, neigh.size,
                mark(g6), mark(g2), mark(g4a), mark(g4b), mark(g7)))
        }
        sb.appendLine()
        sb.appendLine("임계는 `StrategySearchGates` 의 사전고정 상수 그대로다(G1 중앙 ≥ +2.0%p **그리고** 양수 ≥ 6/8).")
        return sb.toString()
    }

    /** 거래를 청산 날짜·사유와 함께 뽑고, 같은 자리에서 라이브 체결 봉투도 계산한다. */
    private suspend fun collectExits(
        fixtures: Map<String, List<Candle>>,
        segment: StrategySearch.Segment,
        point: SweepPoint,
    ): List<Exit> {
        require(point.maxHoldDays == 1) { "봉투 계산은 진입 봉 하나만 보므로 maxHoldDays=1 에서만 유효하다" }
        val config = point.toConfig()
        val feePct = config.feeRate * 2 * 100
        // 라이브가 트레일링을 걸 수 있는 최소 고점 배수 — arm 과 `pnlAtTrailStop > 0` 이 강제하는 하한 중 큰 쪽.
        val peakMultiple = max(1 + point.trailingArmPct / 100.0, 1 / (1 - point.trailingStopPct / 100.0))
        val worstNetPnl = (peakMultiple * (1 - point.trailingStopPct / 100.0) - 1) * 100.0 - feePct

        val exits = ArrayList<Exit>()
        for ((market, newestFirst) in fixtures) {
            val chronological = newestFirst.reversed()
            val input = chronological.subList(segment.inputRange.first, segment.inputRange.last + 1)
            val engine = BacktestEngine(listOf(YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == point.strategy }), props)
            val m = SwingMetrics.measure(engine, point.strategy, market, input, BacktestEngine.MIN_CANDLES, config)
            val bars = input.drop(BacktestEngine.MIN_CANDLES)
            for (t in m.trades) {
                val entryBar = bars[t.buyIndex]
                val capable = entryBar.highPrice >= t.buyPrice * peakMultiple &&
                    entryBar.lowPrice <= entryBar.highPrice * (1 - point.trailingStopPct / 100.0)
                exits += Exit(
                    market = market,
                    date = bars[t.sellIndex].candleDateTimeKst.substring(0, 10),
                    reason = t.reason,
                    pnl = t.pnlPercent,
                    capped = if (capable) min(t.pnlPercent, worstNetPnl) else t.pnlPercent,
                )
            }
        }
        return exits
    }

    private fun correlationStats(fixtures: Map<String, List<Candle>>): DoubleArray {
        val rets = fixtures.mapValues { (_, newestFirst) ->
            val closes = newestFirst.reversed().map { it.tradePrice }
            (1 until closes.size).map { ln(closes[it] / closes[it - 1]) }
        }
        val names = rets.keys.sorted()
        val cs = ArrayList<Double>()
        for (i in names.indices) for (j in i + 1 until names.size) cs += pearson(rets.getValue(names[i]), rets.getValue(names[j]))
        val mean = cs.average()
        val n = names.size
        return doubleArrayOf(mean, cs.min(), cs.max(), n / (1 + (n - 1) * mean))
    }

    private fun pearson(a: List<Double>, b: List<Double>): Double {
        val n = min(a.size, b.size)
        val ma = a.take(n).average()
        val mb = b.take(n).average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until n) {
            val x = a[i] - ma; val y = b[i] - mb
            num += x * y; da += x * x; db += y * y
        }
        return num / sqrt(da * db)
    }

    private companion object {
        val REASON_ORDER = listOf("TRAILING_STOP", "TIME_EXIT", "STOP_LOSS", "TAKE_PROFIT", "CHART_EXIT", "END")
    }
}
