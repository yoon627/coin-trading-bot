package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * [LiveSemanticsArm] 의 인과성 고정 + 라이브 의미론 팔에서의 후보 재판정.
 *
 * 선행 작업(`query/exit-resolution-verdict-2026-09`)은 **청산만** 라이브에 맞췄다. 이 테스트는 진입까지 맞춘 팔을 세워
 * "진입 의미론이 결론을 바꾸는가" 에 답한다.
 */
class LiveSemanticsArmTest {

    private val props = TradingProperties()
    private val notionalKrw = 100_000.0
    private val strategy = YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }

    private fun live() = StrategySearchGrid.baselinePoint()
    private fun variantA() = live().copy(trailingStopPct = 1.5, trailingArmPct = 0.0)
    private fun candidateE() = live().copy(
        kValue = 0.3, takeProfitPct = StrategySearchGrid.TAKE_PROFIT_OFF,
        maxLossPct = 7.0, trailingStopPct = 1.5, trailingArmPct = 0.0,
    )

    /**
     * **미래 정보를 쓰지 않는다**는 것을 구조로 가둔다 — 첫 진입 봉 **이후**의 모든 가격을 크게 흔들어도
     * 첫 진입의 시각·체결가가 바뀌지 않아야 한다. 바뀌면 window 나 체결 판정에 미래 봉이 새어든 것이다.
     */
    @Test
    fun `entry decisions do not depend on future bars`() = runBlocking {
        val market = "KRW-BTC"
        val daily = YearlyFixtures.load(market).reversed()
        val intraday = IntradayFixtures.load("yearly", market).reversed()
        val config = live().toConfig()

        val baseline = LiveSemanticsArm.run(market, strategy, daily, intraday, config, props)
        assertTrue(baseline.isNotEmpty()) { "진입이 0건이면 이 테스트는 아무것도 재지 않는다" }
        val first = baseline.first()

        val entryBarIndex = intraday.indexOfFirst { it.candleDateTimeUtc.startsWith(first.entryDate) }
        assertTrue(entryBarIndex >= 0)
        // 진입 봉 자체는 돌파 판정에 정당하게 쓰이므로 그 **다음** 봉부터 흔든다.
        val mutated = intraday.mapIndexed { i, c ->
            if (i <= entryBarIndex + (BARS_PER_DAY - 1)) c
            else c.copy(
                openingPrice = c.openingPrice * 1.5, highPrice = c.highPrice * 1.5,
                lowPrice = c.lowPrice * 1.5, tradePrice = c.tradePrice * 1.5,
            )
        }
        val after = LiveSemanticsArm.run(market, strategy, daily, mutated, config, props)
        assertTrue(after.isNotEmpty())
        assertEquals(first.entryDate, after.first().entryDate, "미래 봉을 흔들자 첫 진입일이 바뀌었다")
        assertEquals(first.entryPrice, after.first().entryPrice, 1e-9, "미래 봉을 흔들자 첫 체결가가 바뀌었다")
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_LIVE_SEMANTICS", matches = "true")
    fun `re-adjudicate the candidates under live entry semantics`() = runBlocking {
        val windows = buildList {
            add(Triple("1년 전체 (yearly)", "yearly", YearlyFixtures.loadAll()))
            for (r in BacktestFixtures.TIME_INDEPENDENT) add(Triple(r.label, r.dir, BacktestFixtures.loadAll(r)))
            BacktestFixtures.Regime.BEAR.let { add(Triple(it.label, it.dir, BacktestFixtures.loadAll(it))) }
        }
        val arms = listOf("라이브 현행" to live(), "변형 A · 트레일링 1축" to variantA(), "후보 E · 4축" to candidateE())

        val out = StringBuilder()
        out.appendLine("# 진입까지 라이브 의미론으로 맞춘 팔")
        out.appendLine()
        out.appendLine("백테는 **종가가 돌파선 위에서 마감한 날만** 골라 다음 날 09:00 시가에 산다. 라이브는 장중에 넘는 순간 사고")
        out.appendLine("종가가 되밀려도 이미 보유 중이다 — **거래 모집단 자체가 다르다**. 240분봉으로 그 진입을 재현했다.")
        out.appendLine()
        out.appendLine("당일 부분봉은 **직전 봉까지만** 누적해 넘긴다(look-ahead 방지, `entry decisions do not depend on future bars` 가 고정).")
        out.appendLine("그래서 이 팔은 tick 마다 갱신되는 라이브보다 **약간 보수적**이다.")
        out.appendLine()

        for ((label, dir, daily) in windows) {
            val intraday = IntradayFixtures.loadAll(dir, daily.keys)
            val byArm = LinkedHashMap<String, List<LiveSemanticsArm.Trade>>()
            for ((armLabel, point) in arms) {
                val all = ArrayList<LiveSemanticsArm.Trade>()
                for ((market, newestFirst) in daily) {
                    all += LiveSemanticsArm.run(
                        market, strategy, newestFirst.reversed(),
                        intraday.getValue(market).reversed(), point.toConfig(), props,
                    )
                }
                byArm[armLabel] = all
            }
            out.appendLine("## $label")
            out.appendLine()
            out.appendLine("| 설정 | 거래수 | Σpnl %p | 금액 | 격차 %p | 5% 하한 | P(격차 ≤ 0) |")
            out.appendLine("|---|---|---|---|---|---|---|")
            val baseTrades = byArm.getValue(arms.first().first)
            for ((armLabel, _) in arms) {
                val t = byArm.getValue(armLabel)
                val sum = t.sumOf { it.netPnlPct }
                val cells = if (armLabel == arms.first().first) "— | — | —" else {
                    val dates = (t.map { it.exitDate } + baseTrades.map { it.exitDate }).distinct()
                    val byDate = dates.associateWith { d ->
                        t.filter { it.exitDate == d }.sumOf { it.netPnlPct } -
                            baseTrades.filter { it.exitDate == d }.sumOf { it.netPnlPct }
                    }
                    val b = DateBlockBootstrap.of(byDate)
                    "%+.2f | %+.2f | **%.3f**".format(sum - baseTrades.sumOf { it.netPnlPct }, b.p05, b.pLeZero)
                }
                out.appendLine("| %s | %d | %+.2f | %s원 | %s |".format(
                    armLabel, t.size, sum, "%,.0f".format(sum * notionalKrw / 100.0), cells))
            }
            out.appendLine()
            out.appendLine("청산 사유 구성(라이브 현행): " +
                baseTrades.groupBy { it.reason }.entries.sortedByDescending { it.value.size }
                    .joinToString(" · ") { "${it.key} ${it.value.size}건" })
            out.appendLine()
        }

        out.appendLine("## 한계")
        out.appendLine()
        out.appendLine("- 240분봉이라 라이브(10초 tick)보다 성기다. 돌파 체결가는 `max(target, 봉 시가)` 로 **하한**을 쓴다.")
        out.appendLine("- 슬리피지·부분체결·호가 스프레드는 없다(호가 격자 마찰은 별도로 −1.1~−3.7%p 로 실측됨).")
        out.appendLine("- 마켓별 예산이 독립이라고 가정한다 — 실제 계좌는 공유 잔고이고 상관 0.796 인 날에는 동시 진입이 제약된다.")

        val path = Path.of("build/reports/live-semantics-arm.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, out.toString())
        println("[live-arm] 리포트: ${path.toAbsolutePath()}")
        assertTrue(out.contains("라이브 현행"))
    }

    private companion object {
        const val BARS_PER_DAY = 24 * 60 / IntradayFixtures.UNIT_MINUTES
    }
}
