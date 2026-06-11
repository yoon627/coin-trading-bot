package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.CombinedStrategy
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * 청산 파라미터 스윕 — CI 비실행(수동 전용): `RUN_SWEEP=true ./gradlew :bot:test --tests "*ParameterSweepTest*"`.
 *
 * 이슈 #27 Structural: 라이브(combined)와 정합된 BacktestEngine 으로 TP/SL/trailing/arm/maxHold 조합을
 * 실데이터(Upbit 일봉, 공개 quotation API)에서 비교해 env 점진 적용의 근거를 만든다.
 *
 * 해석 한계(plan 참조): 일봉 종가 peak(장중 미반영)·TIME_EXIT 종가 체결·~150봉 단일 국면 표본 —
 * 결과는 참고용(순위·plateau 민감도만), 절대 수익률 신뢰 금지. 적용 전 소액 카나리아 필수.
 */
@EnabledIfEnvironmentVariable(named = "RUN_SWEEP", matches = "true")
class ParameterSweepTest {

    companion object {
        // 전/후반 분할 일관성 체크용 최근 절반 — 워밍업(MIN_CANDLES=50) 포함이라 실질 ~75봉.
        private const val RECENT_HALF_CANDLES = 125
        private const val TOP_REPORT_ROWS = 15
        private const val WORST_REPORT_ROWS = 5
    }

    private data class SweepRow(
        val config: BacktestConfig,
        val trades: Int,
        val winRate: Double,
        val totalReturnPct: Double,
        val profitFactor: Double,
        val maxDrawdownPct: Double,
        val avgHoldDays: Double,
    )

    @Test
    fun `sweep exit parameters on live daily candles`() = runBlocking {
        val candles = fetchDayCandles("KRW-BTC", 200)
        val engine = BacktestEngine(listOf(CombinedStrategy()), TradingProperties())

        val grid = buildList {
            for (tp in listOf(2.0, 3.0, 5.0, 8.0, 99.0))
                for (sl in listOf(3.0, 5.0, 7.0))
                    for (trail in listOf(1.5, 2.0, 3.0))
                        for (arm in listOf(0.0, 2.0, 3.0, 5.0))
                            for (hold in listOf(1, 3, 5, 7, 999))
                                for (filter in listOf(false, true))
                                    add(
                                        BacktestConfig(
                                            takeProfitPct = tp, maxLossPct = sl,
                                            trailingStopPct = trail, trailingArmPct = arm,
                                            maxHoldDays = hold, useMarketFilter = filter,
                                        )
                                    )
        }

        val rows = grid.mapNotNull { config ->
            engine.run("combined", candles, "KRW-BTC", config)?.let { r ->
                SweepRow(config, r.totalTrades, r.winRate, r.totalReturnPct, r.profitFactor, r.maxDrawdownPct, r.avgHoldDays)
            }
        }

        val baseline = engine.run("combined", candles, "KRW-BTC", BacktestConfig())
        val buyAndHold = baseline?.buyAndHoldPct ?: 0.0
        // 전/후반 분할 일관성: 상위 조합이 최근 절반에서도 유지되는지.
        val recentHalf = candles.take(RECENT_HALF_CANDLES)

        val top = rows.filter { it.trades > 0 }.sortedByDescending { it.totalReturnPct }.take(TOP_REPORT_ROWS)
        val report = StringBuilder()
        report.appendLine("# Parameter sweep — KRW-BTC D1 ${candles.last().candleDateTimeKst.take(10)} ~ ${candles.first().candleDateTimeKst.take(10)} (${candles.size} candles, combined)")
        report.appendLine("baseline(=live defaults): return=${fmt(baseline?.totalReturnPct)} winRate=${fmt(baseline?.winRate)} trades=${baseline?.totalTrades} | buy&hold=${fmt(buyAndHold)}")
        report.appendLine()
        report.appendLine("| rank | TP | SL | trail | arm | hold | filter | trades | win% | return% | PF | maxDD% | avgHold | recentHalf return% |")
        report.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        top.forEachIndexed { i, row ->
            val recent = engine.run("combined", recentHalf, "KRW-BTC", row.config)
            report.appendLine(
                "| ${i + 1} | ${row.config.takeProfitPct} | ${row.config.maxLossPct} | ${row.config.trailingStopPct} | " +
                    "${row.config.trailingArmPct} | ${row.config.maxHoldDays} | ${row.config.useMarketFilter} | " +
                    "${row.trades} | ${fmt(row.winRate)} | ${fmt(row.totalReturnPct)} | ${fmt(row.profitFactor)} | " +
                    "${fmt(row.maxDrawdownPct)} | ${fmt(row.avgHoldDays)} | ${fmt(recent?.totalReturnPct)} |"
            )
        }
        report.appendLine()
        val worst = rows.filter { it.trades > 0 }.sortedBy { it.totalReturnPct }.take(WORST_REPORT_ROWS)
        report.appendLine("worst 5 (회피 영역): " + worst.joinToString { "TP${it.config.takeProfitPct}/SL${it.config.maxLossPct}/tr${it.config.trailingStopPct}/arm${it.config.trailingArmPct}/h${it.config.maxHoldDays}/f${it.config.useMarketFilter}=${fmt(it.totalReturnPct)}%" })

        val out = File("build/reports/parameter-sweep.md")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        println(report)
    }

    private fun fmt(v: Double?): String = v?.let { "%.2f".format(it) } ?: "-"

    private fun fetchDayCandles(market: String, count: Int): List<Candle> {
        // 공개 quotation API — 인증 불요. 시세 조회라 시크릿 없음.
        val candles = WebClient.create("https://api.upbit.com")
            .get()
            .uri("/v1/candles/days?market={market}&count={count}", market, count)
            .retrieve()
            .bodyToMono<List<Candle>>()
            .block(Duration.ofSeconds(30))
        checkNotNull(candles) { "Upbit 일봉 응답 없음 ($market) — 네트워크/rate limit 확인" }
        check(candles.isNotEmpty()) { "Upbit 일봉 0건 ($market) — market 코드 확인" }
        return candles
    }
}
