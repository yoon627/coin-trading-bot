package com.trading.bot.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.common.domain.Candle
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * 백테 유니버스 look-ahead 편향 실측 — CI 비실행(수동 전용):
 *   `RUN_UNIVERSE_AUDIT=true ./gradlew :bot:test --tests "*PointInTimeUniverseAuditTest*"`
 *
 * fixture 의 마켓은 **수집 시점**(구간 끝)의 거래대금 상위로 골랐다 → 구간 끝 정보를 쓴 look-ahead 다(#112).
 * 이 하네스는 [PointInTimeUniverse] selector 에 **t0 이전 정보만** 먹여 "그때라면 무엇을 골랐을 것인가"를
 * 재구성하고, 현재 로스터와 대조한다.
 *
 * 세 가지를 낸다:
 * 1. **3단 감쇠** — t0 상장 후보 수 → 선정조건 통과 수(top-8) → 그중 **구간 200봉을 실제로 채우는 수**.
 *    3번이 이 작업에서 가장 행동 가능한 숫자다(BULL 이 4마켓이 아니라 몇 마켓이 될 수 있는가).
 * 2. **placebo** — 같은 selector 를 **수집일** 기준으로 돌려 커밋된 로스터를 얼마나 재현하는지.
 *    현행 규칙("수집시점 24h")과 신안("t0 직전 30일 평균")은 시점과 집계창이 **둘 다** 다르므로,
 *    이 대조군이 없으면 overlap 차이를 look-ahead 에 귀속할 수 없다.
 * 3. **provenance** — 실행시각·commit·API 파라미터·응답 건수·누락 목록·입력 해시.
 *
 * ⚠️ overlap 이 높다는 것을 "편향이 작다"의 근거로 쓰지 않는다 — 폐지 종목이 후보에서 빠져
 * 살아남은 마켓이 그 자리를 채우므로 overlap 은 낙관 쪽으로 편의된다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_UNIVERSE_AUDIT", matches = "true")
class PointInTimeUniverseAuditTest {

    private data class Period(
        val regime: BacktestFixtures.Regime,
        val start: LocalDate,
        val end: LocalDate,
        /** fixture 를 실제로 받은 날 — placebo 기준점. */
        val collectedOn: LocalDate,
    )

    private val periods = listOf(
        Period(BacktestFixtures.Regime.BEAR, LocalDate.parse("2026-01-31"), LocalDate.parse("2026-08-18"), LocalDate.parse("2026-08-19")),
        Period(BacktestFixtures.Regime.BULL, LocalDate.parse("2023-11-23"), LocalDate.parse("2024-06-09"), LocalDate.parse("2026-08-20")),
    )

    private val mapper = jacksonObjectMapper()

    @Test
    fun `audit point-in-time universe against the committed roster`() {
        val client = WebClient.builder().baseUrl(BASE).build()
        val candidates = fetchKrwMarkets(client)
        val out = StringBuilder()

        out.appendLine("# 시점 중립 유니버스 감사 (#112)").appendLine()
        out.appendLine("- 실행: ${Instant.now()}")
        out.appendLine("- commit: ${gitSha()}")
        out.appendLine("- 후보(`GET /v1/market/all`, KRW): ${candidates.size}개")
        out.appendLine("- 조회: `GET /v1/candles/days?market=&count=${PointInTimeUniverse.MIN_HISTORY_DAYS}&to=<t0>` · 간격 ${SPACING_MS}ms")
        out.appendLine()

        for (period in periods) {
            val committed = BacktestFixtures.markets(period.regime)
            val horizonDays = period.start.toEpochDay().let { period.collectedOn.toEpochDay() - it }

            val pit = snapshotAt(client, candidates, period.start)
            val placebo = snapshotAt(client, candidates, period.collectedOn)
            val pitSel = PointInTimeUniverse.select(pit)
            val placeboSel = PointInTimeUniverse.select(placebo)

            out.appendLine("## ${period.regime.label}")
            out.appendLine()
            out.appendLine("- 구간: ${period.start} ~ ${period.end}, 수집일 ${period.collectedOn}")
            out.appendLine("- **look-ahead horizon: ${horizonDays}일** (국면 간 비교 시 병기 — 낮은 overlap 이 국면 탓인지 horizon 탓인지 못 가린다)")
            out.appendLine("- 커밋된 로스터(${committed.size}): ${committed.joinToString(" ")}")
            out.appendLine("- 입력 해시: pit=${hash(pit)} placebo=${hash(placebo)}")
            out.appendLine()

            if (pitSel.incomplete) {
                out.appendLine("> ⚠️ **판정 불가** — 후보 조회 누락 ${pit.missing.size}건: ${pit.missing.joinToString(" ")}")
                out.appendLine("> 누락을 거래대금 0 으로 흡수하면 다른 마켓이 부당하게 상위로 올라간다.")
                out.appendLine()
                continue
            }

            // 3단 감쇠 — ①listed ②eligible→top8 ③구간 200봉 충족
            val listed = pit.candles.count { it.value.isNotEmpty() }
            val eligible = pitSel.ranked.size
            val filling = pitSel.universe.filter { fills200(client, it, period.end, period.start) }

            out.appendLine("### 3단 감쇠")
            out.appendLine()
            out.appendLine("| 단계 | 수 |")
            out.appendLine("|---|---|")
            out.appendLine("| ① t0 시점 상장 후보 | $listed |")
            out.appendLine("| ② 선정조건 통과(30봉 완비·스테이블 제외) | $eligible |")
            out.appendLine("| ③ 그중 top-${PointInTimeUniverse.UNIVERSE_SIZE} 이 구간 200봉을 채우는 수 | **${filling.size}** |")
            out.appendLine()
            out.appendLine("- 시점 중립 top-${PointInTimeUniverse.UNIVERSE_SIZE}: ${pitSel.universe.joinToString(" ")}")
            out.appendLine("- 그중 200봉 충족: ${filling.joinToString(" ").ifEmpty { "(없음)" }}")
            out.appendLine()

            val overlap = pitSel.universe.intersect(committed.toSet())
            out.appendLine("### 커밋 로스터 대조")
            out.appendLine()
            out.appendLine("- overlap: **${overlap.size}/${committed.size}** — ${overlap.joinToString(" ").ifEmpty { "(없음)" }}")
            out.appendLine("- 빠지는 마켓: ${(committed - pitSel.universe.toSet()).joinToString(" ").ifEmpty { "(없음)" }}")
            out.appendLine("- 들어오는 마켓: ${(pitSel.universe - committed.toSet()).joinToString(" ").ifEmpty { "(없음)" }}")
            out.appendLine()

            out.appendLine("### placebo (수집일 기준 · 절차 노이즈 바닥)")
            out.appendLine()
            if (placeboSel.incomplete) {
                out.appendLine("- ⚠️ 판정 불가 — 누락 ${placebo.missing.size}건")
            } else {
                val reproduced = placeboSel.universe.intersect(committed.toSet())
                out.appendLine("- 30일-평균 selector 를 수집일에 돌린 결과: ${placeboSel.universe.joinToString(" ")}")
                out.appendLine("- 커밋 로스터 재현: **${reproduced.size}/${committed.size}**")
                out.appendLine("- 이 값이 절차 노이즈 바닥이다 — 위 overlap 을 이것과 비교해 읽는다(같으면 신호 없음).")
            }
            out.appendLine()
        }

        val report = File("build/reports/point-in-time-universe.md")
        report.parentFile.mkdirs()
        report.writeText(out.toString())
        println(out)
        println("리포트: ${report.absolutePath}")
    }

    /** t0 **직전** [PointInTimeUniverse.MIN_HISTORY_DAYS] 봉을 마켓별로 모아 스냅샷을 만든다. */
    private fun snapshotAt(client: WebClient, candidates: List<String>, asOf: LocalDate): PointInTimeUniverse.Snapshot {
        val candles = LinkedHashMap<String, List<Candle>>()
        val missing = mutableListOf<String>()
        for ((i, market) in candidates.withIndex()) {
            if (i > 0) Thread.sleep(SPACING_MS)
            val fetched = try {
                fetchDays(client, market, PointInTimeUniverse.MIN_HISTORY_DAYS, "${asOf}T00:00:00Z")
            } catch (e: WebClientResponseException) {
                // 4xx 중 400/404 는 "그 시점에 데이터 없음"(미상장)으로 읽는다. 그 외는 조회 실패 = 누락.
                if (e.statusCode.value() == 400 || e.statusCode.value() == 404) emptyList() else {
                    missing += market; continue
                }
            } catch (e: Exception) {
                missing += market; continue
            }
            // `to` 타임존이 어긋나면 "직전 30일"이 통째로 하루 밀린다 — 최신 봉이 t0 이전인지 확인한다.
            fetched.firstOrNull()?.let {
                val newest = LocalDate.parse(it.candleDateTimeKst.substring(0, 10))
                check(newest.isBefore(asOf)) { "to 경계 오류: $market 최신봉 $newest 가 t0 $asOf 이전이 아니다" }
            }
            candles[market] = fetched
        }
        return PointInTimeUniverse.Snapshot(asOf.toString(), candidates, candles, missing)
    }

    /** 구간 끝 기준 200봉이 실제로 존재하고 구간 시작까지 닿는가 — fixture 를 만들 수 있는지의 실측. */
    private fun fills200(client: WebClient, market: String, end: LocalDate, start: LocalDate): Boolean {
        Thread.sleep(SPACING_MS)
        val candles = try {
            fetchDays(client, market, 200, "${end.plusDays(1)}T00:00:00Z")
        } catch (e: Exception) {
            return false
        }
        if (candles.size < 200) return false
        val oldest = LocalDate.parse(candles.last().candleDateTimeKst.substring(0, 10))
        return !oldest.isAfter(start)
    }

    private fun fetchKrwMarkets(client: WebClient): List<String> {
        val body = client.get().uri("/v1/market/all").retrieve()
            .bodyToMono<List<Map<String, Any>>>().block(Duration.ofSeconds(30))
        checkNotNull(body) { "market/all 응답 없음" }
        return body.mapNotNull { it["market"] as? String }.filter { it.startsWith("KRW-") }.sorted()
    }

    private fun fetchDays(client: WebClient, market: String, count: Int, to: String): List<Candle> {
        var attempt = 0
        while (true) {
            try {
                return client.get()
                    .uri { b ->
                        b.path("/v1/candles/days")
                            .queryParam("market", market).queryParam("count", count).queryParam("to", to).build()
                    }
                    .retrieve().bodyToMono<List<Candle>>().block(Duration.ofSeconds(30)) ?: emptyList()
            } catch (e: WebClientResponseException) {
                // 429/5xx 만 지수 백오프 — 그 외 4xx 는 재시도가 무의미하다(M1ReplayBiasTest 와 동일).
                val retryable = e.statusCode.value() == 429 || e.statusCode.is5xxServerError
                if (!retryable || attempt++ >= 3) throw e
                Thread.sleep(1000L * attempt)
            }
        }
    }

    private fun hash(snapshot: PointInTimeUniverse.Snapshot): String {
        val bytes = mapper.writeValueAsBytes(snapshot)
        return MessageDigest.getInstance("SHA-256").digest(bytes).take(8)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * 실행 시점 커밋 + **working tree 오염 여부**.
     *
     * SHA 만 적으면 거짓 provenance 가 조용히 남는다 — 하네스를 커밋하기 전에 돌리면 기록된 커밋에는
     * 그 테스트가 없어서 "그 커밋에서 재현하라"가 성립하지 않는다(실제로 그렇게 새어 pre-push 리뷰가 잡았다).
     */
    private fun gitSha(): String = runCatching {
        val sha = exec("git", "rev-parse", "--short", "HEAD")
        val dirty = exec("git", "status", "--porcelain").isNotEmpty()
        if (dirty) "$sha ⚠️ working tree dirty — 이 커밋만으로는 재현되지 않는다" else sha
    }.getOrDefault("(unknown)")

    private fun exec(vararg cmd: String): String =
        ProcessBuilder(*cmd).redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim()

    private companion object {
        const val BASE = "https://api.upbit.com"

        /** `MarketDataIngestionService.CANDLE_REQUEST_SPACING_MS` 와 같은 값 — 실측 상한 초당 10회. */
        const val SPACING_MS = 150L
    }
}
