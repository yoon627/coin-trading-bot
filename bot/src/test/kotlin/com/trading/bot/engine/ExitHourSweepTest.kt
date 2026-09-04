package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * "익일 **09:00** 전량매도" 에서 **시각** 을 흔든다 — 그리고 그 김에 D1 청산 모델의 편향을 240분봉으로 실측한다.
 *
 * 선행 측정([[hold-limit-policy-2026-09]])은 경계에서 *무엇을 파는가*(전량·조건부·부분·연장·폐지)만 봤다.
 * *언제 파는가* 는 일봉으로 원리적으로 측정 불가다 — 일봉 경계가 곧 09:00 이라 종가 ≡ 익일 시가이기 때문이다.
 *
 * 두 개의 추정대상을 따로 낸다. 섞으면 검정력이 낮은 쪽이 높은 쪽을 오염시킨다.
 *
 * 1. **무조건부 일중 드리프트** — 거래와 무관하게 `log(P_{09+h} / P_09)` 의 시간대 프로파일. 표본이 market-day
 *    전체라 왕복수수료(0.10%) 수준까지 해상한다. 전략·파라미터와 무관해 재사용 가능한 자산이다.
 * 2. **거래 조건부 시각 스윕** — 실제 진입에 대해 경계 시각만 옮겨 replay. 표본이 청산 건수라 검정력이 낮고,
 *    **사전에 underpowered 로 선언**한다. null 이 나와도 "효과 없음"이 아니라 "이 표본으로는 판정 불가"다.
 *
 * 부수 산출물로 `D1 vs 일중봉 replay`(같은 경계 09:00)를 낸다 — 이것이 #33 intrabar 편향의 실측치이고,
 * 트레일링을 조이는 후보의 성적이 얼마나 D1 가정에 기대고 있는지를 가른다.
 *
 * 실행: `RUN_EXIT_HOUR=true ./gradlew :bot:test --tests "*ExitHourSweepTest*" --rerun-tasks`
 */
class ExitHourSweepTest {

    private val props = TradingProperties()

    private data class Arm(val label: String, val point: SweepPoint)

    private data class Window(val label: String, val dir: String, val daily: Map<String, List<Candle>>, val segment: StrategySearch.Segment)

    /** 한 거래를 시각별로 replay 한 결과. 어느 한 시각이라도 데이터가 못 미치면 전 시각에서 제외한다. */
    private data class Replayed(val market: String, val exitDate: String, val d1Pnl: Double, val byOffset: Map<Int, Double>)

    private fun live() = StrategySearchGrid.baselinePoint()
    private fun variantA() = live().copy(trailingStopPct = 1.5, trailingArmPct = 0.0)
    private fun candidateE() = live().copy(
        kValue = 0.3, takeProfitPct = StrategySearchGrid.TAKE_PROFIT_OFF,
        maxLossPct = 7.0, trailingStopPct = 1.5, trailingArmPct = 0.0,
    )

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_EXIT_HOUR", matches = "true")
    fun `sweep the hold-limit hour and measure the D1 exit bias`() = runBlocking {
        val windows = buildList {
            add(Window("1년 전체 (yearly)", "yearly", YearlyFixtures.loadAll(), StrategySearch.Segment("전체", 0..364)))
            for (r in BacktestFixtures.TIME_INDEPENDENT) {
                add(Window(r.label, r.dir, BacktestFixtures.loadAll(r), StrategySearch.REGIME))
            }
        }
        val arms = listOf(Arm("라이브 현행", live()), Arm("변형 A · 트레일링 1축", variantA()), Arm("후보 E · 4축", candidateE()))

        val out = StringBuilder()
        out.appendLine("# 익일 청산 **시각** 스윕 + D1 청산모델 편향 실측 (240분봉)")
        out.appendLine()
        out.appendLine("240분 격자 = KST 01/05/09/13/17/21. 진입은 신호 다음 봉 **09:00 시가**로 고정하고 청산 경계 시각만 옮긴다.")
        out.appendLine()
        out.appendLine("**사전 선언 — 분리되지 않는 것**: 09:00 진입을 고정하면 위상(시각)과 보유기간이 함께 움직인다")
        out.appendLine("(H=09 는 24h, H=13 은 28h, H=05 는 20h 보유). 이 표는 *위상 효과* 가 아니라 **위상+기간 결합 효과**다.")
        out.appendLine("그리고 슬리피지는 어느 열에도 없다 — 유동성이 얇은 시간대의 우위는 백테 아티팩트일 수 있고 캔들로는 판정 불가다.")
        out.appendLine()

        // ── 1. 무조건부 일중 드리프트 ────────────────────────────────────────────────
        out.appendLine("## 1. 무조건부 일중 드리프트 — 거래와 무관하게 09:00 이후 시각이 유리한가")
        out.appendLine()
        out.appendLine("`log(P_{09+h} / P_09)` 를 모든 market-day 에서. 전략·거래집합과 무관하므로 표본이 가장 크고,")
        out.appendLine("따라서 **이 축에서만 진짜 null 을 낼 수 있다**. 클러스터(날짜) 표준오차로 95% 구간을 낸다.")
        out.appendLine()
        out.appendLine("| 창 | market-day | " + IntradayFixtures.OFFSET_HOURS.drop(1).joinToString(" | ") { "→${IntradayFixtures.label(it)}" } + " |")
        out.appendLine("|---" + "|---".repeat(IntradayFixtures.OFFSET_HOURS.size) + "|")
        for (w in windows) {
            val intraday = IntradayFixtures.loadAll(w.dir, w.daily.keys)
            val drift = driftPanel(intraday)
            val cells = IntradayFixtures.OFFSET_HOURS.drop(1).map { h ->
                val (mean, se, n) = drift.getValue(h)
                "%+.3f ±%.3f".format(mean * 100, 1.96 * se * 100).let { if (n == 0) "—" else it }
            }
            out.appendLine("| ${w.label} | ${drift.values.first().third} | ${cells.joinToString(" | ")} |")
        }
        out.appendLine()
        out.appendLine("단위 %p, `평균 ±1.96·SE`(SE 는 날짜 클러스터). 왕복 수수료 0.10%p 가 비교 기준선이다 —")
        out.appendLine("구간이 ±0.10 안에 들어오면 **어떤 시각도 수수료를 넘는 체계적 드리프트 우위를 주지 않는다**.")
        out.appendLine()

        // ── 2. D1 편향 + 거래 조건부 시각 스윕 ───────────────────────────────────────
        out.appendLine("## 2. D1 청산모델 편향과 거래 조건부 시각 스윕")
        out.appendLine()
        for (w in windows) {
            val intraday = IntradayFixtures.loadAll(w.dir, w.daily.keys)
            out.appendLine("### ${w.label}")
            out.appendLine()
            out.appendLine("| 설정 | 비교 거래 | 제외 | D1 Σpnl | " + IntradayFixtures.OFFSET_HOURS.joinToString(" | ") { "일중 ${IntradayFixtures.label(it)}" } + " |")
            out.appendLine("|---|---|---|---" + "|---".repeat(IntradayFixtures.OFFSET_HOURS.size) + "|")
            val replayed = LinkedHashMap<String, List<Replayed>>()
            for (arm in arms) {
                val (rows, excluded) = replayArm(w, intraday, arm.point)
                replayed[arm.label] = rows
                val cells = IntradayFixtures.OFFSET_HOURS.map { h -> "%+.2f".format(rows.sumOf { it.byOffset.getValue(h) }) }
                out.appendLine("| %s | %d | %d | %+.2f | %s |".format(
                    arm.label, rows.size, excluded, rows.sumOf { it.d1Pnl }, cells.joinToString(" | ")))
            }
            out.appendLine()
            out.appendLine("경계 09:00 을 고정하고 **일중봉 기준으로** 라이브와 비교한다 — D1 격차가 모델 가정에 얼마나 기대고 있었는지가 여기서 갈린다.")
            out.appendLine()
            out.appendLine("불확실성은 청산 달력일 블록 부트스트랩(${DateBlockBootstrap.RESAMPLES}회) — 마켓이 아니라 날짜가 재추출 단위다.")
            out.appendLine()
            out.appendLine("| 설정 | D1 격차 | **일중 09:00 격차** | 5% 하한 | P(격차 ≤ 0) |")
            out.appendLine("|---|---|---|---|---|")
            val liveRows = replayed.getValue(arms.first().label)
            for (arm in arms.drop(1)) {
                val rows = replayed.getValue(arm.label)
                val d1Gap = rows.sumOf { it.d1Pnl } - liveRows.sumOf { it.d1Pnl }
                val gap = rows.sumOf { it.byOffset.getValue(0) } - liveRows.sumOf { it.byOffset.getValue(0) }
                val dates = (rows.map { it.exitDate } + liveRows.map { it.exitDate }).distinct()
                val byDate = dates.associateWith { d ->
                    rows.filter { it.exitDate == d }.sumOf { it.byOffset.getValue(0) } -
                        liveRows.filter { it.exitDate == d }.sumOf { it.byOffset.getValue(0) }
                }
                val boot = DateBlockBootstrap.of(byDate)
                out.appendLine("| %s | %+.2f | **%+.2f** | %+.2f | **%.3f** |".format(arm.label, d1Gap, gap, boot.p05, boot.pLeZero))
            }
            out.appendLine()
        }
        out.appendLine("`D1 Σpnl` 과 `일중 09:00` 의 차이가 **#33 intrabar 편향의 실측치**다 — 같은 거래·같은 경계인데")
        out.appendLine("D1 은 하루에 게이트를 한 번, 240분봉은 여섯 번 평가한다. 시각 비교는 반드시 `일중 09:00` 을 기준으로 읽는다")
        out.appendLine("(D1 수치와 비교하면 시각 효과와 모델 효과가 섞인다).")
        out.appendLine()
        out.appendLine("`제외` = 어느 한 시각이라도 240분봉이 경계까지 닿지 않아 **모든 시각에서 뺀 거래 수**.")
        out.appendLine("시각마다 분모가 달라지면 순위가 생존편향 산물이 된다.")
        out.appendLine()
        out.appendLine("## 한계")
        out.appendLine()
        out.appendLine("- 거래 조건부 스윕은 **사전에 underpowered 로 선언**한다. 청산 건수가 창당 60~180 이고 일간 σ 가 3% 대라,")
        out.appendLine("  청산당 0.3%p 미만의 시각 효과는 이 표본으로 판정할 수 없다. null 은 \"효과 없음\"이 아니라 \"판정 불가\"로 읽는다.")
        out.appendLine("- 240분봉도 라이브(10초 tick)보다 성기다 — 일중 replay 는 편향의 **하한**이지 영점이 아니다.")
        out.appendLine("- 진입은 09:00 고정이라 라이브의 장중 돌파 체결과 다르다([[reset-churn-measurement]] 가 기록한 divergence 와 같은 성질).")
        out.appendLine("- 격자 밖 시각(예: 10:00)은 잴 수 없다. 240분 격자가 후보 시각 집합을 6개로 고정한다.")

        val path = Path.of("build/reports/exit-hour-sweep.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[hour] 리포트: ${path.toAbsolutePath()}")
        assertTrue(out.contains("무조건부 일중 드리프트"))
    }

    /** `log(P_{09+h}/P_09)` 의 날짜 클러스터 평균·SE·표본수. 키는 offset 시간. */
    private fun driftPanel(intraday: Map<String, List<Candle>>): Map<Int, Triple<Double, Double, Int>> {
        // 날짜별로 모든 마켓의 관측을 묶는다 — 같은 날 8마켓은 독립 관측이 아니다.
        val byDateByOffset = HashMap<Int, HashMap<String, MutableList<Double>>>()
        for (h in IntradayFixtures.OFFSET_HOURS.drop(1)) byDateByOffset[h] = HashMap()
        for ((_, newestFirst) in intraday) {
            val byInstant = newestFirst.associateBy { LocalDateTime.parse(it.candleDateTimeUtc) }
            for (c in newestFirst) {
                val t = LocalDateTime.parse(c.candleDateTimeUtc)
                if (t.hour != 0) continue // 09:00 KST 격자점만 기준으로 삼는다
                for (h in IntradayFixtures.OFFSET_HOURS.drop(1)) {
                    val later = byInstant[t.plusHours(h.toLong())] ?: continue
                    byDateByOffset.getValue(h).getOrPut(t.toLocalDate().toString()) { ArrayList() }
                        .add(ln(later.openingPrice / c.openingPrice))
                }
            }
        }
        return byDateByOffset.mapValues { (_, byDate) ->
            // 날짜 = 클러스터. 클러스터 평균의 표준오차를 쓴다(마켓 상관을 흡수).
            val perDate = byDate.values.map { it.average() }
            val n = perDate.size
            if (n < 2) return@mapValues Triple(0.0, 0.0, 0)
            val mean = perDate.average()
            val sd = sqrt(perDate.sumOf { (it - mean) * (it - mean) } / (n - 1))
            Triple(mean, sd / sqrt(n.toDouble()), n)
        }
    }

    /** D1 진입을 고정하고 경계 시각만 옮겨 240분봉으로 replay. 어느 시각이라도 데이터가 모자라면 그 거래는 전부 제외. */
    private suspend fun replayArm(
        w: Window,
        intraday: Map<String, List<Candle>>,
        point: SweepPoint,
    ): Pair<List<Replayed>, Int> {
        val config = point.toConfig()
        val feePct = config.feeRate * 2 * 100
        val rows = ArrayList<Replayed>()
        var excluded = 0

        for ((market, newestFirst) in w.daily) {
            val chronological = newestFirst.reversed()
            val input = chronological.subList(w.segment.inputRange.first, w.segment.inputRange.last + 1)
            val engine = BacktestEngine(listOf(YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == point.strategy }), props)
            val m = SwingMetrics.measure(engine, point.strategy, market, input, BacktestEngine.MIN_CANDLES, config)
            val bars = input.drop(BacktestEngine.MIN_CANDLES)
            val minute = intraday.getValue(market).reversed()

            for (t in m.trades) {
                // 진입 체결은 그 봉의 시가 = 09:00 KST = 그 날짜 00:00 UTC.
                val entryUtc = LocalDate.parse(bars[t.buyIndex].candleDateTimeKst.substring(0, 10)).atStartOfDay()
                val base = entryUtc.plusDays(config.maxHoldDays.toLong())
                val slice = minute.filter { !LocalDateTime.parse(it.candleDateTimeUtc).isBefore(entryUtc) }
                val perOffset = LinkedHashMap<Int, Double>()
                for (h in IntradayFixtures.OFFSET_HOURS) {
                    val limit = base.plusHours(h.toLong())
                    val r = M1ReplayEngine.replayExit(t.buyPrice, entryUtc, limit, slice, config)
                    val exit = r.exit ?: break
                    perOffset[h] = (exit.sellPrice - t.buyPrice) / t.buyPrice * 100.0 - feePct
                }
                if (perOffset.size != IntradayFixtures.OFFSET_HOURS.size) { excluded++; continue }
                rows += Replayed(market, bars[t.sellIndex].candleDateTimeKst.substring(0, 10), t.pnlPercent, perOffset)
            }
        }
        return rows to excluded
    }
}
