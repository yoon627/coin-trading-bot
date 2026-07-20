package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.ExitGates
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * M1 replay 편향 실측 — CI 비실행(수동 전용):
 *   `RUN_M1_REPLAY=true ./gradlew :bot:test --tests "*M1ReplayBiasTest*"`
 *   마켓 재정의: `M1_REPLAY_MARKETS=KRW-BTC,KRW-ETH`, 기간: `M1_REPLAY_DAYS=60`.
 *
 * D1 백테의 intrabar 청산 근사(봉 high/low 판정 + 봉 내 순서 worst-case 가정, #33)가 실제(10초 tick) 대비
 * 얼마나 편향되는지, M1(1분봉)을 tick 프록시로 삼아 같은 [IntrabarExitModel] 을 분 단위로 순차 replay 해
 * 실제 도달 순서의 청산과 비교한다. 진입은 D1 과 동일(변수 격리), 청산만 비교.
 *
 * 판정은 이벤트 수(N)·불확실성(±CI) 병기, 표본 미달 시 유보(억지 판정 금지) — plan Decisions ③.
 */
@EnabledIfEnvironmentVariable(named = "RUN_M1_REPLAY", matches = "true")
class M1ReplayBiasTest {

    companion object {
        private const val PAGE = 200
        private val UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val REASONS = listOf("TRAILING_STOP", "STOP_LOSS", "TAKE_PROFIT", "CHART_EXIT", "TIME_EXIT")

        // 편향은 intraday 가격 게이트에서만 나온다(TIME_EXIT/CHART_EXIT 는 D1=M1 자명 일치) — 이 게이트 이벤트가
        // 최소치 미만이면 CI=0 이 나와도 미관측 행동의 증거가 아니므로 trust verdict 금지.
        private val RELEVANT_GATES = setOf("STOP_LOSS", "TRAILING_STOP", "TAKE_PROFIT")
        private const val MIN_RELEVANT_EVENTS = 10

        // Student-t 95% 양측 임계값 (index=df, 1..30 정확값). df>30 은 유한-df 보수값으로 감쇠.
        private val T95 = doubleArrayOf(
            0.0, 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
            2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
            2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042,
        )
    }

    private data class Row(
        val market: String, val entryUtc: String,
        val d1Reason: String, val m1Reason: String,
        val d1Pnl: Double, val m1Pnl: Double,
    ) {
        val pnlDiff get() = m1Pnl - d1Pnl // >0 = M1 실제가 유리(D1 비관 편향), <0 = D1 낙관 편향
    }

    @Test
    fun `measure D1 exit-model bias against M1 replay`() = runBlocking {
        val client = WebClient.create("https://api.upbit.com")
        val markets = (System.getenv("M1_REPLAY_MARKETS") ?: "KRW-BTC,KRW-ETH,KRW-XRP,KRW-SOL,KRW-DOGE")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        // 하한 60 — MIN_CANDLES(50) 워밍업 미달이면 BacktestEngine.run 이 null 을 내 조용히 N=0 이 된다.
        val days = (System.getenv("M1_REPLAY_DAYS")?.toIntOrNull() ?: 90).coerceIn(60, 200)
        val config = BacktestConfig() // 라이브 기본 정합(maxHoldDays=1 등)
        val engine = BacktestEngine(listOf(CombinedStrategy()), TradingProperties())
        val holdDays = ExitGates.effectiveMaxHoldDays(config.maxHoldDays)

        val rows = mutableListOf<Row>()
        var incomplete = 0
        for (market in markets) {
            val d1 = fetchDayCandles(client, market, days)
            val chrono = d1.reversed() // run 은 newest-first 입력 → 내부 reversed; trade 인덱스는 chronological
            val result = engine.run("combined", d1, market, config) ?: continue
            for (t in result.trades) {
                if (t.reason == "END") continue // 시리즈 말미 강제 마크투마켓 — 편향 대상 아님
                val buyBar = chrono.getOrNull(t.buyIndex) ?: continue
                val entryUtc = LocalDateTime.parse(buyBar.candleDateTimeUtc)
                val limitInstant = entryUtc.toLocalDate().plusDays(holdDays.toLong()).atStartOfDay()
                // 한도봉(09:00 리셋) 직후 저유동 마켓은 체결 분이 드물어 경계봉이 결측되기 쉽다
                // → 여유 있게(+60min) 백필해 t>=limitInstant 봉을 확보(survivorship 표본 손실 완화).
                val m1 = fetchM1Range(client, market, entryUtc, limitInstant.plusMinutes(60))
                val replay = M1ReplayEngine.replayExit(t.buyPrice, entryUtc, limitInstant, m1, config)
                val exit = replay.exit
                if (exit == null) { incomplete++; continue } // M1 데이터가 한도까지 미달 — 집계 제외
                val m1Pnl = ((exit.sellPrice - t.buyPrice) / t.buyPrice) * 100.0 - config.feeRate * 2 * 100
                rows.add(Row(market, buyBar.candleDateTimeUtc, t.reason, exit.reason, t.pnlPercent, m1Pnl))
            }
        }

        writeReport(rows, incomplete, markets, days, config)
    }

    private fun writeReport(rows: List<Row>, incomplete: Int, markets: List<String>, days: Int, config: BacktestConfig) {
        val n = rows.size
        val sb = StringBuilder()
        sb.appendLine("# M1 replay 편향 실측 — combined, D1 ${days}일 × ${markets.joinToString(",")}")
        sb.appendLine("- 비교 이벤트 N=$n, incomplete(M1 데이터 미달 제외)=$incomplete")
        if (n == 0) {
            sb.appendLine("- **판정 유보: 비교 가능 청산 이벤트 0건** (데이터/기간 확대 필요)")
            emit(sb); return
        }
        val diffs = rows.map { it.pnlDiff }
        val tradeMean = diffs.average() // trade-weighted (참고용)
        val fee = config.feeRate * 2 * 100
        val mismatch = rows.count { it.d1Reason != it.m1Reason }.toDouble() / n
        // 동일 마켓/기간 트레이드는 clustered 라 iid sd/√n 은 불확실성을 과소평가 → 마켓을 클러스터 단위로.
        val marketMeans = rows.groupBy { it.market }.map { (_, rs) -> rs.map { it.pnlDiff }.average() }
        val k = marketMeans.size

        // reason 불일치는 방향성 편향 증거가 아니다(다른 reason 이 같은 sellPrice 를 낼 수 있음) → PnL 판정과 분리 보고.
        val mismatchNote = "- reason 불일치율 = ${fmt(mismatch * 100)}%  (※ 청산 사유 구성 차이 지표 — PnL 방향성 편향 증거 아님)"

        if (k < 2) {
            sb.appendLine("- meanPnlBias(trade-weighted) = ${fmt(tradeMean)}%  (단일 마켓 — 클러스터 CI 산출 불가)")
            sb.appendLine(mismatchNote); sb.appendLine()
            appendConfusion(sb, rows)
            sb.appendLine("## 판정")
            sb.appendLine("- **판정 유보 (단일 마켓, N=$n)** — 마켓 간 독립 표본이 없어 불확실성 추정 불가, 다마켓 확대 필요.")
            emit(sb); return
        }

        // CI 와 estimand 를 맞추려 point 도 마켓 균등가중 평균(clusterMean)으로 보고. between-market 변동 → stdErr.
        // 소표본 t 임계값(df=K-1) — K 가 작으면 정규 1.96 은 CI 를 크게 과소추정(K=2 → t≈12.7)해 과신을 유발.
        val clusterMean = marketMeans.average()
        val clusterSd = sqrt(marketMeans.sumOf { (it - clusterMean) * (it - clusterMean) } / (k - 1))
        val tCrit = tCritical95(k - 1)
        val ci95 = tCrit * (clusterSd / sqrt(k.toDouble()))
        val lo = clusterMean - ci95
        val hi = clusterMean + ci95

        sb.appendLine("- meanPnlBias(M1−D1, 마켓 균등가중) = ${fmt(clusterMean)}%  (±${fmt(ci95)} 95%CI, 클러스터 K=$k, t(df=${k - 1})=${fmt(tCrit)}; trade-weighted=${fmt(tradeMean)}%)")
        sb.appendLine("  - 부호: +면 D1 이 비관(실제가 더 유리), −면 D1 이 낙관(실제가 더 불리)")
        sb.appendLine(mismatchNote); sb.appendLine()
        appendConfusion(sb, rows)
        sb.appendLine("## 판정")
        // 편향 유발 게이트(SL/TRAILING/TP) 이벤트가 최소치 미만이면 CI=0 이 나와도 미관측 행동의 증거가 아님 → 유보.
        // 그 위에서만 CI 기반 판정: 전체 CI ⊂ ±fee → 등가(신뢰), CI 전체가 ±fee 밖 → 편향, 걸치면 유보.
        val relevant = rows.count { it.d1Reason in RELEVANT_GATES || it.m1Reason in RELEVANT_GATES }
        when {
            relevant < MIN_RELEVANT_EVENTS ->
                sb.appendLine("- **판정 유보 (N=$n, K=$k): 편향 유발 게이트(SL/TRAILING/TP) 관측 $relevant 건 < $MIN_RELEVANT_EVENTS** — 대부분 TIME_EXIT 이라 편향 노출 표본 부족(maxHoldDays 확대 등으로 intraday 청산 이벤트 확보 필요).")
            lo >= -fee && hi <= fee ->
                sb.appendLine("- **D1 청산 모델 신뢰 가능** (N=$n, K=$k, 관련이벤트 $relevant): 전체 95%CI ⊂ ±수수료(${fmt(fee)}%).")
            lo > fee || hi < -fee ->
                sb.appendLine("- **D1 모델 ${if (clusterMean >= 0) "비관" else "낙관"} PnL 편향 ${fmt(clusterMean)}%±${fmt(ci95)} (N=$n, K=$k)** — CI 전체가 허용범위 밖, 절대수익률 보정 필요.")
            else ->
                sb.appendLine("- **판정 유보 (N=$n, K=$k): 95%CI(±${fmt(ci95)}%)가 허용범위 ±${fmt(fee)}% 를 걸쳐 등가·편향 단정 불가** — 클러스터(마켓) 확대 필요.")
        }
        if (mismatch >= 0.2) sb.appendLine("- ⚠️ reason 불일치율 ${fmt(mismatch * 100)}% 높음 — 청산 사유 구성 차이 큼(PnL 편향과 별개로 검토).")
        emit(sb)
    }

    private fun emit(sb: StringBuilder) {
        val out = File("build/reports/m1-replay-bias.md")
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
        println(sb)
    }

    private fun fmt(v: Double): String = "%.3f".format(v)

    private fun appendConfusion(sb: StringBuilder, rows: List<Row>) {
        sb.appendLine("## reason confusion (행=D1, 열=M1)")
        sb.appendLine("| D1 \\ M1 | ${REASONS.joinToString(" | ")} |")
        sb.appendLine("|---|${REASONS.joinToString("|") { "---" }}|")
        for (dr in REASONS) {
            val cells = REASONS.joinToString(" | ") { mr -> rows.count { it.d1Reason == dr && it.m1Reason == mr }.toString() }
            sb.appendLine("| $dr | $cells |")
        }
        sb.appendLine()
    }

    /** Student-t 95% 양측 임계값(df=K-1). df 1..30 은 정확값, >30 은 유한-df 보수값으로 감쇠(1.96 으로 조기 수렴 금지). */
    private fun tCritical95(df: Int): Double = when {
        df < 1 -> T95[1]
        df <= 30 -> T95[df]
        df <= 40 -> 2.021
        df <= 60 -> 2.000
        df <= 120 -> 1.980
        else -> 1.962
    }

    private fun fetchDayCandles(client: WebClient, market: String, count: Int): List<Candle> {
        val candles = client.get()
            .uri("/v1/candles/days?market={market}&count={count}", market, count)
            .retrieve().bodyToMono<List<Candle>>().block(Duration.ofSeconds(30))
        checkNotNull(candles) { "Upbit 일봉 응답 없음 ($market)" }
        check(candles.isNotEmpty()) { "Upbit 일봉 0건 ($market)" }
        return candles
    }

    /** [/v1/candles/minutes/1] 을 `to` 파라미터로 과거로 페이지네이션(200/콜, newest-first) 후 [from,to] 로 잘라 오름차순 반환. */
    private fun fetchM1Range(client: WebClient, market: String, fromUtc: LocalDateTime, toUtc: LocalDateTime): List<Candle> {
        val acc = HashMap<String, Candle>()
        var cursor = toUtc
        var guard = 0
        while (cursor.isAfter(fromUtc) && guard++ < 500) {
            val page = fetchM1Page(client, market, cursor)
            if (page.isEmpty()) break
            page.forEach { acc[it.candleDateTimeUtc] = it }
            val oldest = LocalDateTime.parse(page.minOf { it.candleDateTimeUtc })
            if (!oldest.isAfter(fromUtc) || page.size < PAGE) break
            cursor = oldest
            Thread.sleep(150) // quotation rate limit 여유
        }
        return acc.values
            .filter { val t = LocalDateTime.parse(it.candleDateTimeUtc); !t.isBefore(fromUtc) && !t.isAfter(toUtc) }
            .sortedBy { it.candleDateTimeUtc }
    }

    private fun fetchM1Page(client: WebClient, market: String, to: LocalDateTime): List<Candle> {
        var attempt = 0
        while (true) {
            try {
                return client.get()
                    .uri { b ->
                        b.path("/v1/candles/minutes/1")
                            .queryParam("market", market)
                            .queryParam("count", PAGE)
                            .queryParam("to", to.format(UTC))
                            .build()
                    }
                    .retrieve().bodyToMono<List<Candle>>().block(Duration.ofSeconds(30)) ?: emptyList()
            } catch (e: WebClientResponseException) {
                // 재시도 무의미한 4xx(400 등)는 즉시 throw — 429/5xx 만 지수 백오프.
                val retryable = e.statusCode.value() == 429 || e.statusCode.is5xxServerError
                if (!retryable || attempt++ >= 3) throw e
                Thread.sleep(1000L * attempt)
            }
        }
    }
}
