package com.trading.bot.engine

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 리셋(보유상한 TIME_EXIT, 그날 첫 봉 시가 = 당일시가 청산) 뒤 **같은 날 같은 마켓 재진입**의 체결가 프리미엄 — `reset-churn-measurement` 의
 * 미측정 성분 1(D1 반사실은 재진입가 = 청산가로 고정). #143 의 M1 fixture 대신 5분봉 캐시와 [LiveSemanticsArm] 으로 잰다. 판정 규칙 없는 측정.
 *
 * 프리미엄 = (진입가 − 당일시가)/당일시가 × 100. `combined` 가 현재가 ≤ 돌파선을 거부하므로 재진입가 > 돌파선 = 당일시가 + k×전일레인지 —
 * **프리미엄은 정의상 양수**이고 리셋 여부와 무관한 "돌파 진입 프리미엄"이다. 그래서 (i) 돌파선 성분((돌파선−시가)/시가)과 오버슈트((진입가−돌파선)/시가)로
 * 분해하고 (ii) **대조군** = 같은 날 선행 TIME_EXIT 이 없는 진입의 같은 프리미엄과 비교한다. 두 분포가 같으면 "리셋 고유 비용"은 없다.
 * 가중: TIME_EXIT 건당·마켓 균등가중(#128 estimand). Σ는 #128 의 ±0.5%p/건에 **가법이 아니다**(분모·모집단·리베이스 경로가 다르다) — 크기 지표.
 * 5분봉 체결은 돌파 봉 시가라 라이브 tick 체결(돌파선 근처)보다 높은 쪽 — 같은 표본 안의 하한은 돌파선 성분이다.
 *
 * 기간정합: 10창(2020~2025)과 별도로 `live-entry-2026` 5분 캐시(2026-07-14~09-14)로 같은 지표를 내어 라이브 `trade_records` 와 같은 기간에서 대조한다.
 * 라이브 캐시가 없으면 실패한다(skip 아님). 실행:
 * `RUN_REENTRY_PREMIUM=true BACKTEST_CACHE_DIR=~/.cache/coin-trading-bot/backtest-cache ./gradlew :bot:test --tests "*ReentryPremiumTest*" --rerun-tasks`
 */
class ReentryPremiumTest {

    private val props = TradingProperties()
    private val strategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }
    private val mapper = jacksonObjectMapper()

    private data class LiveTrade(
        val id: Long, val market: String, val side: String, val price: Double,
        @JsonProperty("created_utc") val createdUtc: String, val reason: String = "", @JsonProperty("pnl_percent") val pnlPercent: Double? = null,
    ) { val day: String get() = createdUtc.substring(0, 10) }

    /** 한 진입의 프리미엄 분해. [reentry] 면 같은 날 선행 TIME_EXIT 이 있다. */
    /** [forcedNonTimeExitBefore]: 직전 거래가 같은 날 09:00 한도봉에서 TIME_EXIT 이 아닌 게이트(트레일링·손절·익절)로 청산된 뒤의 재진입 — 대조군에 섞이는 준-재진입. */
    private class Entry(val t: LiveSemanticsArm.Trade, val dayOpen: Double, val target: Double, val reentry: Boolean, val minutesAfterExit: Long?, val forcedNonTimeExitBefore: Boolean) {
        val premium get() = (t.entryPrice - dayOpen) / dayOpen * 100.0
        val lineComponent get() = (target - dayOpen) / dayOpen * 100.0
        val overshoot get() = (t.entryPrice - target) / dayOpen * 100.0
    }

    private class Stats(val n: Int, val median: Double, val mean: Double, val q10: Double, val q90: Double, val marketMean: Double)

    private fun stats(xs: List<Double>, byMarket: Map<String, List<Double>>): Stats {
        val s = xs.sorted()
        fun q(p: Double) = if (s.isEmpty()) 0.0 else s[((s.size - 1) * p).toInt()]
        val marketMeans = byMarket.values.filter { it.isNotEmpty() }.map { it.average() }
        return Stats(s.size, median(s), if (s.isEmpty()) 0.0 else s.average(), q(0.1), q(0.9), if (marketMeans.isEmpty()) 0.0 else marketMeans.average())
    }

    private fun median(sorted: List<Double>) = if (sorted.isEmpty()) 0.0 else if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2

    /** 마켓별 시간순 거래 → 진입 목록(프리미엄 분해·재진입 표식). 돌파선은 일봉 fixture 로 계기와 같은 식(당일시가 + k×전일 고저)으로 다시 계산. */
    private fun entries(trades: List<LiveSemanticsArm.Trade>, daily: Map<String, List<Candle>>, dayOpen: (String, String) -> Double?, k: Double): List<Entry> {
        val out = ArrayList<Entry>()
        for ((market, ts) in trades.groupBy { it.market }) {
            val chrono = daily.getValue(market).reversed()
            val idx = chrono.withIndex().associate { (i, c) -> c.candleDateTimeKst.substring(0, 10) to i }
            val sorted = ts.sortedWith(compareBy({ it.entryDate }, { it.entryBarUtc }))
            for ((i, t) in sorted.withIndex()) {
                val open = dayOpen(market, t.entryDate) ?: fail("당일시가 없음: $market ${t.entryDate}")
                val prev = idx[t.entryDate]?.let { chrono.getOrNull(it - 1) } ?: fail("전일봉 없음: $market ${t.entryDate}")
                val target = open + k * (prev.highPrice - prev.lowPrice)
                val prevTrade = sorted.getOrNull(i - 1)
                val reentry = prevTrade != null && prevTrade.reason == "TIME_EXIT" && prevTrade.exitDate == t.entryDate
                val minutes = if (reentry) Duration.between(LocalDateTime.parse(prevTrade!!.exitBarUtc), LocalDateTime.parse(t.entryBarUtc)).toMinutes() else null
                if (reentry) {
                    assertTrue(minutes!! > 0, "재진입 봉이 청산 봉보다 앞선다: $prevTrade → $t")
                    assertTrue(abs(prevTrade.exitPrice - open) <= 1e-9 * open, "TIME_EXIT 청산가 ${prevTrade.exitPrice} ≠ 당일시가 $open — 청산 규칙 전제가 깨졌다")
                }
                out += Entry(t, open, target, reentry, minutes, prevTrade != null && prevTrade.reason != "TIME_EXIT" && prevTrade.exitDate == t.entryDate && prevTrade.exitBarUtc.endsWith("T00:00:00"))
            }
        }
        assertEquals(trades.size, out.size, "진입 목록이 거래수와 다르다")
        return out
    }

    private fun section(out: StringBuilder, title: String, all: List<LiveSemanticsArm.Trade>, es: List<Entry>) {
        val re = es.filter { it.reentry }; val ctl = es.filter { !it.reentry }
        val timeExits = all.count { it.reason == "TIME_EXIT" }
        fun st(list: List<Entry>, f: (Entry) -> Double) = stats(list.map(f), list.groupBy({ it.t.market }, f))
        out.appendLine("## $title")
        out.appendLine()
        out.appendLine("기준 거래 %d (Σ %+.1f%%p) · TIME_EXIT %d · 같은 날 재진입 쌍 **%d**(TIME_EXIT 의 %.1f%%) · 대조군(선행 TIME_EXIT 없는 진입) %d.".format(
            all.size, all.sumOf { it.netPnlPct }, timeExits, re.size, 100.0 * re.size / maxOf(1, timeExits), ctl.size))
        out.appendLine()
        out.appendLine("| 프리미엄(당일시가 대비 %) | 재진입 n | 중앙값 | 평균 | 10% | 90% | 마켓 균등가중 평균 | 대조군 n | 중앙값 | 평균 | 10% | 90% | 마켓 균등가중 평균 | 차이(재진입−대조, 중앙값) |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for ((label, f) in listOf<kotlin.Pair<String, (Entry) -> Double>>("전체 프리미엄" to { it.premium }, "돌파선 성분" to { it.lineComponent }, "오버슈트(체결−돌파선)" to { it.overshoot })) {
            val a = st(re, f); val b = st(ctl, f)
            out.appendLine("| %s | %d | %+.3f | %+.3f | %+.3f | %+.3f | %+.3f | %d | %+.3f | %+.3f | %+.3f | %+.3f | %+.3f | %+.3f |".format(
                label, a.n, a.median, a.mean, a.q10, a.q90, a.marketMean, b.n, b.median, b.mean, b.q10, b.q90, b.marketMean, a.median - b.median))
        }
        out.appendLine()
        val mins = re.mapNotNull { it.minutesAfterExit?.toDouble() }.sorted()
        fun qm(p: Double) = if (mins.isEmpty()) 0.0 else mins[((mins.size - 1) * p).toInt()]
        val entryBarExit = re.count { it.t.exitOnEntryBar }; val sameDay = re.count { it.t.exitDate == it.t.entryDate && !it.t.exitOnEntryBar }; val ended = re.count { it.t.reason == "END" }
        val rePnl = re.filter { it.t.reason != "END" }.map { it.t.netPnlPct }; val ctlPnl = ctl.filter { it.t.reason != "END" }.map { it.t.netPnlPct }
        out.appendLine("- 재진입까지 분(구조적으로 ≥ 봉 길이 — 첫 봉 시가는 돌파선 아래): 최소 %.0f · 10%% %.0f · 중앙값 %.0f · 90%% %.0f. 재진입 거래 중 진입 봉 즉시 청산 %d · 같은 날 청산 %d · 구간 끝 END %d.".format(
            if (mins.isEmpty()) 0.0 else mins.first(), qm(0.1), median(mins), qm(0.9), entryBarExit, sameDay, ended))
        out.appendLine("- 대조군에 섞인 준-재진입(같은 날 09:00 한도봉에서 TIME_EXIT 이 아닌 게이트로 청산된 뒤 재진입): %d건 — 차이를 줄이는 쪽의 오염.".format(ctl.count { it.forcedNonTimeExitBefore }))
        out.appendLine("- 재진입 거래 손익(END 제외) 건당 %+.3f (n=%d) vs 대조군 건당 %+.3f (n=%d) — 재진입은 '전날 보유 + 그날 돌파' 로 선택된 부분집합이라 교란된 비교(참고).".format(
            if (rePnl.isEmpty()) 0.0 else rePnl.average(), rePnl.size, if (ctlPnl.isEmpty()) 0.0 else ctlPnl.average(), ctlPnl.size))
        val rate = re.size.toDouble() / maxOf(1, timeExits); val pr = st(re) { it.premium }
        out.appendLine("- 크기 지표(가법 아님, TIME_EXIT 건당): 프리미엄 × 재진입 비율 %.3f — pooled 중앙값 %+.3f → %+.3f%%p · pooled 평균 %+.3f → %+.3f%%p · 마켓 균등가중 평균 %+.3f → %+.3f%%p. 헤드라인은 pooled 중앙값, 선언한 estimand(마켓 균등)는 방향 확인용. #128 관측(24h 내 재매수 7건 평균 +1.80%%)과 자릿수만 대조.".format(
            rate, pr.median, pr.median * rate, pr.mean, pr.mean * rate, pr.marketMean, pr.marketMean * rate))
        out.appendLine()
    }

    private fun <T> loadGz(path: Path, type: Class<T>): List<T> {
        require(Files.exists(path)) { "캐시 없음: $path" }
        return GZIPInputStream(Files.newInputStream(path)).use { mapper.readValue(it, mapper.typeFactory.constructCollectionType(List::class.java, type)) }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_REENTRY_PREMIUM", matches = "true")
    fun `decompose the same-day re-entry premium after time exits and compare with non-reentry entries and live`() = runBlocking {
        val levels = LadderWindows.load(UNITS)
        val config = StrategySearchGrid.currentLivePoint().toConfig()
        val out = StringBuilder()
        out.appendLine("# 리셋(TIME_EXIT) 뒤 같은 날 재진입의 체결가 프리미엄 — 분해와 대조군 (#143 대체, #144 입력)")
        out.appendLine()
        out.appendLine("계기 `LiveSemanticsArm` 현행 라이브(k%.1f/TP5/SL5/트레일1.5/arm0/h1). 프리미엄 = (진입가 − 당일시가)/당일시가 × 100 — TIME_EXIT 청산가 = 당일시가(단정)라 재진입 쌍의 갭과 같다. 돌파선 = 당일시가 + k×전일 고저(일봉 fixture 로 재계산). 규칙은 KDoc.".format(config.kValue))
        out.appendLine()

        // ── 10창 (2020~2025) ──
        for (level in levels.filter { it.unit != 240 }) {
            val byWindow = LadderWindows.run(level, strategy, config, props)
            val all = byWindow.values.flatten()
            val pin = PRIOR_BASELINE.getValue(level.unit)
            assertEquals(pin.first, all.size, "${level.unit}분 기준 거래수가 선행과 다르다 — 계기 drift")
            assertTrue(abs(all.sumOf { it.netPnlPct } - pin.second) <= 0.05, "${level.unit}분 기준 Σpnl 이 선행과 다르다")
            assertEquals(all.size, all.map { it.market to it.entryDate }.toSet().size, "${level.unit}m: (마켓, 진입일) 이 유일하지 않다")
            val perWindow = level.windows.associate { w -> w.dir to entries(byWindow.getValue(w.dir), w.daily, { m, d -> w.dayOpen[m to d] }, config.kValue) }
            val es = perWindow.values.flatten()
            section(out, "${level.unit}분봉 · 10창 (2020~2025)", all, es)
            if (level.unit == 5) {
                out.appendLine("| 창 | TIME_EXIT | 재진입 쌍 | 재진입 프리미엄 중앙값 | 대조군 중앙값 | 재진입 ≤ 대조 |")
                out.appendLine("|---|---|---|---|---|---|")
                var below = 0
                for (w in level.windows) {
                    val ws = perWindow.getValue(w.dir)
                    val r = ws.filter { it.reentry }.map { it.premium }.sorted(); val c = ws.filter { !it.reentry }.map { it.premium }.sorted()
                    val le = r.isNotEmpty() && median(r) <= median(c); if (le) below++
                    out.appendLine("| ${w.dir} | ${byWindow.getValue(w.dir).count { it.reason == "TIME_EXIT" }} | ${r.size} | %s | %s | %s |".format(
                        if (r.isEmpty()) "-" else "%+.3f".format(median(r)), if (c.isEmpty()) "-" else "%+.3f".format(median(c)), if (r.isEmpty()) "-" else if (le) "예" else "아니오"))
                }
                out.appendLine()
                out.appendLine("창별 부호: 재진입 ≤ 대조군 %d/%d (모두 한쪽이면 부호검정 양측 p = %.4f).".format(below, level.windows.size, 2.0 / (1 shl level.windows.size)))
                out.appendLine()
            }
        }

        // ── 기간정합: live-entry-2026 5분 캐시 (2026-07-14~09-14) ──
        val root = IntradayCache.cacheRoot().resolve("live-entry-2026")
        require(Files.exists(root.resolve("trades.json"))) { "라이브 캐시 없음: $root — #190 의 scripts/collect_live_entry_fixtures.py 로 준비할 것(skip 아님)" }
        val liveDaily = LIVE_MARKETS.associateWith { loadGz(root.resolve("daily").resolve("$it.json.gz"), Candle::class.java) }
        val live5 = LIVE_MARKETS.associateWith { loadGz(root.resolve("intraday5").resolve("$it.json.gz"), Candle::class.java).sortedBy { it.candleDateTimeUtc } }
        val liveArmAll = ArrayList<LiveSemanticsArm.Trade>()
        val liveDayOpen = HashMap<kotlin.Pair<String, String>, Double>()
        val badFirstBar = LinkedHashSet<Pair<String, String>>()
        for (m in LIVE_MARKETS) {
            for ((d, bars) in live5.getValue(m).groupBy { it.candleDateTimeUtc.substring(0, 10) }) {
                // 첫 봉이 00:00 이 아니면 "당일시가 = 청산가" 전제가 그날에는 성립하지 않는다 — 10창은 LadderWindows 가 240분 시가와 대조해 막지만 이 캐시엔 그 가드가 없다.
                if (!bars.first().candleDateTimeUtc.endsWith("T00:00:00")) { badFirstBar += m to d; continue }
                liveDayOpen[m to d] = bars.first().openingPrice
            }
            liveArmAll += LiveSemanticsArm.run(m, strategy, liveDaily.getValue(m).reversed(), live5.getValue(m), config, props, warmup = 60)
        }
        // 재진입 판정은 경계 전 거래까지 본 뒤 기간으로 거른다(07-14 직전 진입 → 07-14 TIME_EXIT → 재진입이 대조군으로 오분류되지 않게).
        val liveArmKept = liveArmAll.filter { (it.market to it.entryDate) !in badFirstBar }
        val liveEsAll = entries(liveArmKept, liveDaily, { m, d -> liveDayOpen[m to d] }, config.kValue)
        val liveEs = liveEsAll.filter { it.t.entryDate in LIVE_START..LIVE_END }
        val liveArm = liveArmKept.filter { it.entryDate in LIVE_START..LIVE_END }
        section(out, "5분봉 · 라이브 기간 2026-07-14~09-14 (기간정합 계기, warmup 60, 현행 설정 단일)", liveArm, liveEs)
        out.appendLine("첫 봉이 00:00 이 아니라 제외한 (마켓, 일): %d개%s.".format(badFirstBar.size, if (badFirstBar.isEmpty()) "" else " — " + badFirstBar.joinToString(" ") { "${it.first}/${it.second}" }))
        out.appendLine()

        // ── 라이브 trade_records (집계만) ──
        val live = mapper.readValue<List<LiveTrade>>(Files.readString(root.resolve("trades.json")))
        val resets = live.filter { it.side == "SELL" && it.reason == "DAILY_RESET" }
        assertTrue(resets.all { it.createdUtc.substring(11, 13) == "00" }, "DAILY_RESET 매도가 00h UTC 가 아닌 건이 있다 — 같은 UTC 날 키 전제")
        data class LivePair(val sell: LiveTrade, val buy: LiveTrade) {
            val gapPct get() = (buy.price - sell.price) / sell.price * 100.0
            val seconds get() = Duration.between(LocalDateTime.parse(sell.createdUtc), LocalDateTime.parse(buy.createdUtc)).seconds
        }
        val pairs = ArrayList<LivePair>(); var skippedNonBuy = 0
        for ((_, ts) in live.groupBy { it.market }) {
            val sorted = ts.sortedWith(compareBy({ it.createdUtc }, { it.id }))
            for (i in sorted.indices) {
                val s = sorted[i]; if (s.side != "SELL" || s.reason != "DAILY_RESET") continue
                val nextBuy = sorted.drop(i + 1).firstOrNull { it.side == "BUY" } ?: continue
                if (sorted.drop(i + 1).takeWhile { it != nextBuy }.isNotEmpty()) skippedNonBuy++
                if (nextBuy.day == s.day) pairs += LivePair(s, nextBuy)
            }
        }
        val fast = pairs.filter { it.seconds <= 60 }; val slow = pairs.filter { it.seconds > 60 }
        val afterDeploy = slow.count { p -> LocalDateTime.parse(p.buy.createdUtc).toLocalDate().minusDays(1).toString() in DEPLOY_DATES }
        val bSignature = slow.count { p -> p.seconds <= 15 * 60 && LocalDateTime.parse(p.buy.createdUtc).toLocalDate().minusDays(1).toString() in DEPLOY_DATES }
        out.appendLine("## 라이브 `trade_records` (운영 combined 2026-07-14~09-14, 집계만 — BUY price 는 계좌 평단 기록가격)")
        out.appendLine()
        out.appendLine("- DAILY_RESET 매도 %d(전부 00h UTC) · 같은 날 같은 마켓 다음 BUY %d(매도 뒤 매도 등 건너뛴 레코드 %d).".format(resets.size, pairs.size, skippedNonBuy))
        out.appendLine("- 60초 내 재매수 %d(갭 %+.2f~%+.2f%%) — 시가 근처 즉시 매수는 #209 결함 산물(그룹 A 경계 stale window 또는 그룹 B 전일 D1 절단 — 둘 다 같은 서명, A 는 PR #184, B 는 `0534b13` 이 해소)이라 리셋 재진입이 아니다 → 제외.".format(fast.size, fast.minOfOrNull { it.gapPct } ?: 0.0, fast.maxOfOrNull { it.gapPct } ?: 0.0))
        out.appendLine("- 60초 초과 재매수 %d — 갭 최소 %+.2f%% · 최대 %+.2f%% · 중앙값 %+.2f%%(n 이 작아 분포 주장 없음), 분 중앙값 %.0f. 전날 배포(재시작)가 있던 날 %d건이지만 그룹 B 오염은 돌파선을 **낮춰** 갭을 줄이고 00h 직후 체결로 나타나므로(그 서명 — 리셋 후 15분 내 + 전날 배포 — 은 %d건) 계기보다 높은 라이브 중앙값을 설명하지 못한다.".format(
            slow.size, slow.minOfOrNull { it.gapPct } ?: 0.0, slow.maxOfOrNull { it.gapPct } ?: 0.0, median(slow.map { it.gapPct }.sorted()), median(slow.map { it.seconds / 60.0 }.sorted()), afterDeploy, bSignature))
        out.appendLine()

        val path = Path.of("build/reports/reentry-premium.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println(out)
        println("리포트: ${path.toAbsolutePath()}")
    }

    private companion object {
        val UNITS = listOf(240, 15, 5)
        /** `entry-set-decomposition-2026-09` 기준선 — 계기 drift 검출. */
        val PRIOR_BASELINE = mapOf(15 to (1_659 to -124.8), 5 to (1_767 to -219.4))
        val LIVE_MARKETS = listOf("KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-DOGE", "KRW-ADA", "KRW-AVAX", "KRW-LINK")
        const val LIVE_START = "2026-07-14"
        const val LIVE_END = "2026-09-14"
        /** 기간 내 deploy.yml success 일자(gh run list, 2026-09-16 조회) — 전날 재시작이 있던 재매수는 #209 그룹 B 오염 후보. */
        val DEPLOY_DATES = setOf("2026-08-03", "2026-08-04", "2026-08-11", "2026-08-18", "2026-08-19", "2026-08-20", "2026-08-22", "2026-08-23", "2026-08-24", "2026-08-25", "2026-08-26", "2026-08-31", "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04", "2026-09-05", "2026-09-06", "2026-09-08", "2026-09-14")
    }
}
