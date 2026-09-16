package com.trading.bot.engine

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.trading.bot.engine.LiveEntryMatcher.ArmEntry
import com.trading.bot.engine.LiveEntryMatcher.Key
import com.trading.bot.engine.LiveEntryMatcher.LiveBuy
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 라이브 `combined` 매수(운영 `trade_records`)를 같은 기간의 [LiveSemanticsArm] 240·15·5분봉 진입 집합과 대조한다(#190 잔여).
 * 매칭·통계·판정 규칙은 [LiveEntryMatcher](사전고정, 결과 전 커밋) — 여기는 데이터 로딩·계기 실행·리포트만.
 *
 * `entry-set-decomposition-2026-09` 는 "해상도가 높을수록 라이브 쪽" 을 방향 논증으로만 말했다. 라이브는 10초 tick 이라
 * 5분봉도 되밀린 돌파를 전부 잡지는 못한다 — 판정은 세 해상도의 **상대** 비교다.
 *
 * 계기 설정은 기간을 [PARAM_SWITCH_DAY] 로 갈라 이어붙인다 — 라이브는 09-05 까지 트레일링 2.0/arm 3.0([StrategySearchGrid.baselinePoint]),
 * 09-06 부터 1.5/0([StrategySearchGrid.currentLivePoint], `trailing-arm-finding-2026-09`). 진입 집합은 청산을 거친 포지션 상태에 의존한다.
 * 이어붙인 경계의 포지션 상태는 근사라 두 설정 단독 실행도 민감도로 낸다. warmup 은 라이브 D1 창(`TradingEngine.MAX_DAILY_CANDLE_LOOKBACK` = 60)에
 * 맞춘 60 이 주, 계기 기본 50 이 민감도 — `combined` 의 RSI(Wilder) 는 창 길이에 값이 달라진다.
 *
 * 입력은 전부 저장소 밖 캐시 `$BACKTEST_CACHE_DIR/live-entry-2026/`(`scripts/collect_live_entry_fixtures.py`). 라이브 거래 기록은
 * public 저장소에 커밋하지 않으므로 캐시 루트가 저장소 안이면 실패한다. 캐시가 없어도 실패한다(skip 아님).
 *
 * 실행: `RUN_LIVE_ENTRY=true BACKTEST_CACHE_DIR=~/.cache/coin-trading-bot/backtest-cache ./gradlew :bot:test --tests "*LiveEntryResolutionTest*" --rerun-tasks`
 */
class LiveEntryResolutionTest {

    private val props = TradingProperties()
    private val strategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }
    private val mapper = jacksonObjectMapper()

    private data class LiveTrade(
        val id: Long,
        val market: String,
        val side: String,
        val price: Double,
        @JsonProperty("created_utc") val createdUtc: String,
        val reason: String = "",
        @JsonProperty("pnl_percent") val pnlPercent: Double? = null,
    ) {
        val day: String get() = createdUtc.substring(0, 10)
    }

    private fun <T> loadGz(path: Path, type: Class<T>): List<T> {
        require(Files.exists(path)) { "캐시 없음: $path — python3 scripts/collect_live_entry_fixtures.py --write" }
        return GZIPInputStream(Files.newInputStream(path)).use { mapper.readValue(it, mapper.typeFactory.constructCollectionType(List::class.java, type)) }
    }

    private fun loadCandles(root: Path, dir: String, market: String): List<Candle> = loadGz(root.resolve(dir).resolve("$market.json.gz"), Candle::class.java)

    /** 하나의 계기 설정(config·warmup)으로 낸 세 해상도의 진입 집합 — 기간 안, 제외일 밖. */
    private class Arm(val label: String, val entries: Map<Int, List<ArmEntry>>)

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_LIVE_ENTRY", matches = "true")
    fun `compare live entries with the ladder instruments at 240, 15 and 5 minutes`() = runBlocking {
        val root = IntradayCache.cacheRoot().resolve(DIR)
        require(System.getenv("BACKTEST_CACHE_DIR")?.isNotBlank() == true && !root.toAbsolutePath().startsWith(IntradayCache.repoRoot())) {
            "라이브 거래 기록은 저장소 밖에 둔다 — BACKTEST_CACHE_DIR 를 저장소 밖 경로로 설정할 것 (현재 $root)"
        }
        val daily = MARKETS.associateWith { loadCandles(root, "daily", it) }
        val tradesPath = root.resolve("trades.json")
        require(Files.exists(tradesPath)) { "라이브 거래 없음: $tradesPath — scripts/sql/live_entry_trades.sql 참조" }
        val liveRecords = mapper.readValue<List<LiveTrade>>(Files.readString(tradesPath)).filter { it.market in MARKETS }
        val liveBuysAll = liveRecords.filter { it.side == "BUY" && it.day in LIVE_START..LIVE_END }
        assertEquals(EXPECTED_LIVE_BUYS, liveBuysAll.size, "라이브 매수 건수가 추출 시점(2026-09-16)과 다르다")
        // 라이브 포지션 구간 (매수일, 매도일] — BUY 뒤 첫 SELL 과 짝. 구간 끝 미청산은 열린 채로 둔다.
        val livePositions = liveRecords.groupBy { it.market }.mapValues { (_, ts) ->
            val spans = ArrayList<kotlin.Pair<String, String>>(); var open: LiveTrade? = null
            for (t in ts.sortedWith(compareBy({ it.createdUtc }, { it.id }))) {
                if (t.side == "BUY") open = t else if (open != null) { spans += open.day to t.day; open = null }
            }
            if (open != null) spans += open.day to "9999-12-31"
            spans
        }
        fun liveHeld(market: String, day: String) = livePositions[market].orEmpty().any { (b, s) -> day > b && day <= s }

        // 분봉 로딩 + 첫 봉 결측 판정(사전고정 제외 규칙). 세 해상도가 같은 (마켓, 일) 첫 봉 시가를 공유해야 같은 계기다.
        val intraday = UNITS.associateWith { unit -> MARKETS.associateWith { m -> loadCandles(root, "intraday$unit", m).sortedBy { it.candleDateTimeUtc } } }
        val excluded = sortedSetOf<String>()
        val dayOpen240 = HashMap<Key, Double>()
        for (unit in UNITS) for ((m, bars) in intraday.getValue(unit)) {
            val byDay = bars.groupBy { it.candleDateTimeUtc.substring(0, 10) }
            require(byDay.values.all { it.size <= 24 * 60 / unit }) { "$unit m $m: 봉/일 상한 초과" }
            for (d in tradingDays(daily.getValue(m))) {
                val first = byDay[d]?.first()
                if (first == null || !first.candleDateTimeUtc.endsWith("T00:00:00")) { excluded += "$m/$d"; continue }
                if (unit == 240) dayOpen240[Key(m, d)] = first.openingPrice
                else dayOpen240[Key(m, d)]?.let { o -> assertTrue(abs(first.openingPrice - o) <= 1e-9 * o, "$unit m $m $d: 첫 봉 시가 ${first.openingPrice} ≠ 240분 $o") }
            }
        }
        val excludedKeys = excluded.map { val (m, d) = it.split("/"); Key(m, d) }.toSet()
        val liveBuys = liveBuysAll.filter { Key(it.market, it.day) !in excludedKeys }.map { LiveBuy(it.id, it.market, it.createdUtc, it.price) }
        val liveExcluded = liveBuysAll.size - liveBuys.size

        suspend fun runArm(unit: Int, market: String, config: BacktestConfig, warmup: Int): List<LiveSemanticsArm.Trade> =
            LiveSemanticsArm.run(market, strategy, daily.getValue(market).reversed(), intraday.getValue(unit).getValue(market), config, props, warmup = warmup)
        fun toEntries(trades: List<LiveSemanticsArm.Trade>) = trades.filter { it.entryDate in LIVE_START..LIVE_END && Key(it.market, it.entryDate) !in excludedKeys }
            .map { ArmEntry(it.market, it.entryDate, it.entryBarUtc, it.entryPrice, it.exitDate) }
        val old = StrategySearchGrid.baselinePoint().toConfig()
        val current = StrategySearchGrid.currentLivePoint().toConfig()
        suspend fun arm(label: String, warmup: Int, stitched: Boolean, config: BacktestConfig = current): Arm = Arm(label, UNITS.associateWith { unit ->
            MARKETS.flatMap { m ->
                if (!stitched) toEntries(runArm(unit, m, config, warmup))
                else toEntries(runArm(unit, m, old, warmup)).filter { it.day < PARAM_SWITCH_DAY } + toEntries(runArm(unit, m, current, warmup)).filter { it.day >= PARAM_SWITCH_DAY }
            }
        })
        val primary = arm("이어붙임(09-05 까지 2.0/3.0 → 1.5/0) · warmup 60", 60, stitched = true)
        val sensitivity = listOf(
            arm("현행 1.5/0 단독 · warmup 60", 60, stitched = false, config = current),
            arm("구 2.0/3.0 단독 · warmup 60", 60, stitched = false, config = old),
            arm("이어붙임 · warmup 50(계기 기본)", 50, stitched = true),
        )

        val results = UNITS.map { LiveEntryMatcher.match(it, liveBuys, primary.entries.getValue(it)) }
        val verdict = LiveEntryMatcher.verdict(results)
        val common = LiveEntryMatcher.commonIds(results)
        val base240 = primary.entries.getValue(240).map { it.key }.toSet()
        val notIn5 = base240 - primary.entries.getValue(5).map { it.key }.toSet()
        fun armHeld(unit: Int, market: String, day: String) = primary.entries.getValue(unit).any { it.market == market && day > it.day && day <= it.exitDay }

        val out = StringBuilder()
        out.appendLine("# 라이브 진입 vs 해상도 사다리 계기 — 어느 봉이 라이브에 가까운가 (#190)")
        out.appendLine()
        out.appendLine("라이브 `combined` 매수 %d건 (%s ~ %s, %d마켓), 첫 봉 결측으로 제외 %d건 → 표본 %d. 제외 (마켓, 일) %d개%s.".format(
            liveBuysAll.size, LIVE_START, LIVE_END, MARKETS.size, liveExcluded, liveBuys.size, excluded.size, if (excluded.isEmpty()) "" else ": " + excluded.joinToString(" ")))
        out.appendLine("계기 `LiveSemanticsArm` k0.5/TP5/SL5/h1, ${primary.label}. 키 = (마켓, UTC 거래일). 기록가격 오차 = (라이브 − 계기)/계기 × 100. 시각 = 라이브 created_at − 계기 진입 봉 시작(분). 규칙은 `LiveEntryMatcher` KDoc.")
        out.appendLine()
        out.appendLine("## 주 결과")
        out.appendLine()
        out.appendLine("| 해상도 | 계기 진입 N | 매칭 | recall | precision | **F1** | 공통 표본 median(\\|Δ\\|) %p | 부호 중앙값 %p | 라이브 높/낮/같 | 시각 중앙값(분) | 라이브 전용 (계기 보유중) | 계기 전용 (라이브 보유중) |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (r in results) {
            val hi = r.matched.count { it.fillPct > 1e-9 }; val lo = r.matched.count { it.fillPct < -1e-9 }
            out.appendLine("| %d분 | %d | %d | %.3f | %.3f | **%.3f** | %.3f | %+.3f | %d/%d/%d | %.0f | %d (%d) | %d (%d) |".format(
                r.unit, r.armCount, r.matched.size, r.recall, r.precision, r.f1, LiveEntryMatcher.medianAbsFill(r, common), r.signedMedianFill,
                hi, lo, r.matched.size - hi - lo, r.medianMinutes,
                r.liveOnly.size, r.liveOnly.count { armHeld(r.unit, it.market, it.day) }, r.armOnly.size, r.armOnly.count { liveHeld(it.market, it.day) }))
        }
        out.appendLine()
        out.appendLine("공통 표본(세 해상도 모두 매칭) n = %d. **사전고정 판정: %s** (F1 최대 %s · median(|Δ|) 최소 %s).".format(
            common.size, verdict.note, verdict.f1Best?.let { "${it}분" } ?: "동률", verdict.fillBest?.let { "${it}분" } ?: "유보"))
        out.appendLine()
        out.appendLine("240분 진입 중 5분에 없는 (마켓, 일): %d건%s.".format(notIn5.size, if (notIn5.isEmpty()) " (선행 관측 (c)=0 재현)" else " — 포지션 상태·부분봉 차이로 240 에서만 진입한 경로: ${notIn5.joinToString { "${it.market}/${it.day}" }}"))
        out.appendLine()
        out.appendLine("## 민감도 — 설정·warmup 을 바꿔도 순위가 유지되나")
        out.appendLine()
        out.appendLine("| 설정 | " + UNITS.joinToString(" | ") { "${it}분 N / 매칭 / F1" } + " | 판정 |")
        out.appendLine("|---|" + UNITS.joinToString("") { "---|" } + "---|")
        for (a in listOf(primary) + sensitivity) {
            val rs = UNITS.map { LiveEntryMatcher.match(it, liveBuys, a.entries.getValue(it)) }
            out.appendLine("| ${a.label} | " + rs.joinToString(" | ") { "%d / %d / %.3f".format(it.armCount, it.matched.size, it.f1) } + " | ${LiveEntryMatcher.verdict(rs).note} |")
        }
        out.appendLine()
        out.appendLine("## 마켓별 진입 수 (주 설정)")
        out.appendLine()
        out.appendLine("| 마켓 | 라이브 | " + UNITS.joinToString(" | ") { "${it}분 N" } + " | " + UNITS.joinToString(" | ") { "${it}분 매칭" } + " |")
        out.appendLine("|---|---|" + UNITS.joinToString("") { "---|" } + UNITS.joinToString("") { "---|" })
        for (m in MARKETS) {
            out.appendLine("| $m | ${liveBuys.count { it.market == m }} | " + UNITS.joinToString(" | ") { u -> primary.entries.getValue(u).count { it.market == m }.toString() } +
                " | " + results.joinToString(" | ") { r -> r.matched.count { it.live.market == m }.toString() } + " |")
        }
        out.appendLine()
        out.appendLine("## 기록가격 오차 분포 (해상도별 전체 매칭, %p — 표본이 달라 해상도 간 직접 비교는 공통 표본 열로)")
        out.appendLine()
        out.appendLine("| 해상도 | n | 최소 | 10% | 25% | 중앙 | 75% | 90% | 최대 | median(\\|Δ\\|) 전체 |")
        out.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        for (r in results) if (r.matched.isNotEmpty()) {
            val f = r.matched.map { it.fillPct }.sorted()
            fun q(p: Double) = f[((f.size - 1) * p).toInt()]
            out.appendLine("| %d분 | %d | %+.3f | %+.3f | %+.3f | %+.3f | %+.3f | %+.3f | %+.3f | %.3f |".format(r.unit, f.size, f.first(), q(0.1), q(0.25), r.signedMedianFill, q(0.75), q(0.9), f.last(), LiveEntryMatcher.median(f.map { abs(it) })))
        }
        out.appendLine()
        out.appendLine("## 시각 차 분포 (분, 라이브 − 계기 봉 시작; 음수 = 라이브가 먼저)")
        out.appendLine()
        out.appendLine("| 해상도 | n | 10% | 중앙 | 90% | \\|Δt\\| 중앙 | 봉 안 비율(참고) |")
        out.appendLine("|---|---|---|---|---|---|---|")
        for (r in results) if (r.matched.isNotEmpty()) {
            val t = r.matched.map { it.minutes.toDouble() }.sorted()
            fun q(p: Double) = t[((t.size - 1) * p).toInt()]
            out.appendLine("| %d분 | %d | %.0f | %.0f | %.0f | %.0f | %.2f |".format(r.unit, t.size, q(0.1), r.medianMinutes, q(0.9), LiveEntryMatcher.median(t.map { abs(it) }), r.matched.count { it.minutes in 0 until r.unit }.toDouble() / t.size))
        }
        out.appendLine()
        out.appendLine("## 라이브 전용 진입 — 어느 해상도도 못 잡은 것 (집계만; 개별 체결은 캐시에)")
        out.appendLine()
        val allLiveOnly = liveBuys.filter { b -> results.all { r -> r.liveOnly.any { it.id == b.id } } }
        out.appendLine("%d건. 그날 5분 계기가 그 마켓을 보유 중: %d건. UTC 시각대 분포: %s".format(
            allLiveOnly.size, allLiveOnly.count { armHeld(5, it.market, it.day) },
            allLiveOnly.groupBy { it.createdUtc.substring(11, 13) }.toSortedMap().entries.joinToString { "${it.key}h×${it.value.size}" }))
        out.appendLine()
        out.appendLine("실행: 라이브 매수 ${liveBuysAll.size} · 표본 ${liveBuys.size} · 제외 $liveExcluded · 계기 실행 ${UNITS.size * MARKETS.size * 5}회 · 공통 표본 ${common.size}.")

        val path = Path.of("build/reports/live-entry-resolution.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println(out)
    }

    /** 일봉의 KST 날짜 라벨 중 라이브 기간 안의 것 — 제외 판정의 (마켓, 일) 공간. */
    private fun tradingDays(newestFirst: List<Candle>): List<String> =
        newestFirst.map { it.candleDateTimeKst.substring(0, 10) }.filter { it in LIVE_START..LIVE_END }

    private companion object {
        const val DIR = "live-entry-2026"
        val UNITS = listOf(240, 15, 5)
        val MARKETS = listOf("KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-DOGE", "KRW-ADA", "KRW-AVAX", "KRW-LINK")
        const val LIVE_START = "2026-07-14"
        const val LIVE_END = "2026-09-14"
        const val PARAM_SWITCH_DAY = "2026-09-06"
        const val EXPECTED_LIVE_BUYS = 76
    }
}
