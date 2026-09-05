package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 전향 검증의 **소요 기간**을 실측한다 — 사전고정에 기간을 임의로 박지 않기 위해서다.
 *
 * 변형 A(`trailingStopPct 2.0→1.5`, `trailingArmPct 3.0→0`)는 **청산 게이트만** 바꾸므로 진입이 동일하다.
 * 따라서 같은 거래를 두 설정으로 짝지을 수 있고, **차이가 나는 거래만**이 정보를 준다.
 * 나머지는 짝지은 차이가 정확히 0 이라 표본에 기여하지 않는다.
 *
 * 실행: `RUN_SHADOW_POWER=true ./gradlew :bot:test --tests "*TrailingShadowPowerTest*" --rerun-tasks`
 */
class TrailingShadowPowerTest {

    private val props = TradingProperties()
    private val strategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }

    private fun live() = StrategySearchGrid.baselinePoint()
    private fun variantA() = live().copy(trailingStopPct = 1.5, trailingArmPct = 0.0)

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_SHADOW_POWER", matches = "true")
    fun `how long would a prospective shadow run need`() = runBlocking {
        val windows = buildList {
            add("1년 전체 (yearly)" to (YearlyFixtures.loadAll() to "yearly"))
            for (r in BacktestFixtures.Regime.entries) add(r.label to (BacktestFixtures.loadAll(r) to r.dir))
        }

        val diffs = ArrayList<Double>()
        var paired = 0
        var tradingDays = 0
        for ((_, spec) in windows) {
            val (daily, dir) = spec
            val intraday = IntradayFixtures.loadAll(dir, daily.keys)
            for ((market, newestFirst) in daily) {
                val d = newestFirst.reversed()
                val i = intraday.getValue(market).reversed()
                val liveTrades = LiveSemanticsArm.run(market, strategy, d, i, live().toConfig(), props)
                val aTrades = LiveSemanticsArm.run(market, strategy, d, i, variantA().toConfig(), props)
                // 진입이 동일하므로 (진입일) 로 짝지어진다.
                val aByEntry = aTrades.associateBy { it.entryDate }
                for (t in liveTrades) {
                    val a = aByEntry[t.entryDate] ?: continue
                    paired++
                    diffs += a.netPnlPct - t.netPnlPct
                }
                tradingDays += d.size
            }
        }

        val divergent = diffs.filter { abs(it) > 1e-9 }
        val mean = divergent.average()
        val sd = sqrt(divergent.sumOf { (it - mean) * (it - mean) } / (divergent.size - 1))
        // 80% power, 양측 α=0.05 의 1표본 t 근사: n = (Z_SUM · sd / mean)²
        val needed = ceil((Z_SUM * sd / mean) * (Z_SUM * sd / mean)).toInt()
        val divergenceRate = divergent.size.toDouble() / paired
        // fixture 의 마켓-일당 청산율을 라이브 규모(운영 티커 수)로 환산한다.
        val perMarketYear = paired.toDouble() / tradingDays * DAYS_PER_YEAR
        val divergentPerYear = perMarketYear * LIVE_MARKETS * divergenceRate

        println(
            """
            |[shadow-power]
            |  짝지은 거래        : $paired 건
            |  청산이 갈린 거래   : ${divergent.size} 건 (${"%.1f".format(divergenceRate * 100)}%)
            |  갈린 거래의 차이   : 평균 ${"%+.3f".format(mean)}%p, 표준편차 ${"%.3f".format(sd)}%p
            |  80%% power 필요 표본: $needed 건 (갈린 거래 기준)
            |  라이브 환산        : ${LIVE_MARKETS}마켓 연 ${"%.0f".format(perMarketYear * LIVE_MARKETS)}청산 중 갈린 것 연 ${"%.0f".format(divergentPerYear)}건
            |  → 필요 기간        : 약 ${"%.1f".format(needed / divergentPerYear)}년
            """.trimMargin(),
        )
        assertTrue(divergent.isNotEmpty())
    }

    private companion object {
        /** 운영 티커 수 — `YearlyFixtures.MARKETS`(수집일의 `TRADING_TICKERS`)와 같다. */
        val LIVE_MARKETS = YearlyFixtures.MARKETS.size
        const val DAYS_PER_YEAR = 365.0

        /** 80% power·양측 α=0.05 의 1표본 t 근사에 쓰는 z 합 (1.96 + 0.84). */
        const val Z_SUM = 2.80
    }
}
