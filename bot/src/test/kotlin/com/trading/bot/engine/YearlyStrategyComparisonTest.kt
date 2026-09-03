package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 운영 8종 1년 일봉에서 스윙 9종(재진입 2모드)·적립·단순보유를 같은 거래 구간·같은 지표로 비교한다.
 *
 * 무거운 비교는 `RUN_YEARLY_COMPARE=true` 뒤에서만 돌고 결과를 파일로 쓴다(`println` 은 testLogging 설정이 없어
 * 콘솔에 안 나온다). 게이트 없는 스모크 1건이 하네스 자체를 CI 에서 지킨다 — 건너뛴 검증은 통과가 아니다.
 *
 * 실행: `RUN_YEARLY_COMPARE=true ./gradlew :bot:test --tests "*YearlyStrategyComparisonTest*" --rerun-tasks`
 */
class YearlyStrategyComparisonTest {

    private val strategies = YearlyStrategyComparison.ALL_STRATEGIES
    private val harness = YearlyStrategyComparison(BacktestEngine(strategies, TradingProperties()), strategies)

    @Test
    fun `every window trades exactly the input minus the swing warmup, and the split tiles the full window`() {
        for (w in YearlyStrategyComparison.Window.entries) {
            assertEquals(BacktestEngine.MIN_CANDLES, w.tradeRange.first - w.inputRange.first, "$w 워밍업")
            assertEquals(w.inputRange.last, w.tradeRange.last, "$w 끝")
            assertTrue(w.inputRange.first >= 0 && w.inputRange.last < YearlyFixtures.BARS, "$w fixture 범위")
        }
        val full = YearlyStrategyComparison.Window.FULL.tradeRange
        val select = YearlyStrategyComparison.Window.SELECT.tradeRange
        val validate = YearlyStrategyComparison.Window.VALIDATE.tradeRange
        assertEquals(full.first, select.first)
        assertEquals(select.last + 1, validate.first, "선택/검증은 겹치지 않고 붙는다")
        assertEquals(full.last, validate.last)
    }

    @Test
    fun `mark-to-market equity drawdown counts unrealized dips between entry and exit`() {
        // 100 → 보유 중 80 까지 눌렸다 105 에 청산: 청산 시점만 보는 엔진 MDD 는 0, 봉단위 equity 는 −20.
        val closes = listOf(100.0, 100.0, 80.0, 105.0)
        val trades = listOf(BacktestTrade(buyIndex = 1, sellIndex = 3, buyPrice = 100.0, sellPrice = 105.0, pnlPercent = 4.9, holdDays = 2, reason = "TAKE_PROFIT"))
        val curve = YearlyStrategyComparison.swingEquityCurve(closes, trades)
        assertEquals(listOf(100.0, 99.9, 79.9, 104.9), curve.map { Math.round(it * 10) / 10.0 })
        assertEquals(20.1, YearlyStrategyComparison.maxDrawdownPct(curve), 1e-9)
    }

    @Test
    fun `same-bar re-entry is marked on the exit bar and the curve ends at 100 plus realized pnl`() {
        val closes = listOf(100.0, 100.0, 80.0, 105.0, 110.0)
        val trades = listOf(
            BacktestTrade(buyIndex = 1, sellIndex = 3, buyPrice = 100.0, sellPrice = 105.0, pnlPercent = 4.9, holdDays = 2, reason = "TAKE_PROFIT"),
            BacktestTrade(buyIndex = 3, sellIndex = 4, buyPrice = 105.0, sellPrice = 110.0, pnlPercent = 4.66, holdDays = 1, reason = "END"),
        )
        val curve = YearlyStrategyComparison.swingEquityCurve(closes, trades)
        assertEquals(104.8, curve[3], 1e-9, "청산 봉에 다음 거래의 미실현(0 − 수수료)이 실린다")
        assertEquals(YearlyStrategyComparison.EQUITY_BASE + 4.9 + 4.66, curve.last(), 1e-9)
    }

    @Test
    fun `rank helpers give ties the same competition rank and the mean mid-rank`() {
        val values = listOf(3.0, 5.0, 5.0, 1.0)
        assertEquals(listOf(3, 1, 1, 4), YearlyStrategyComparison.competitionRanks(values))
        assertEquals(listOf(3.0, 1.5, 1.5, 4.0), YearlyStrategyComparison.midRanks(values))
        assertEquals(1.0, YearlyStrategyComparison.spearman(listOf(1.0, 2.0, 3.0), listOf(1.0, 2.0, 3.0)), 1e-9)
        assertEquals(-1.0, YearlyStrategyComparison.spearman(listOf(1.0, 2.0, 3.0), listOf(3.0, 2.0, 1.0)), 1e-9)
    }

    @Test
    fun `smoke — one market, one window runs end to end and yields every candidate`() = runTest {
        val rows = harness.compare(mapOf("KRW-BTC" to YearlyFixtures.load("KRW-BTC")), YearlyStrategyComparison.Window.VALIDATE)
        // 9 전략 × 2 재진입 모드 + 적립 + 단순보유 = 20 후보.
        assertEquals(20, rows.size)
        assertTrue(rows.all { it.market == "KRW-BTC" })
        assertTrue(rows.all { it.exposure in 0.0..1.0 }, "노출 비율")
        assertTrue(rows.all { it.netReturnPct.isFinite() && it.mddPct.isFinite() && it.mddPct >= 0.0 }, "지표 유한")
        assertEquals(18, rows.count { it.swing })
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_YEARLY_COMPARE", matches = "true")
    fun `full comparison writes the report`() = runTest {
        val report = harness.report(YearlyFixtures.loadAll())
        val out = Path.of("build/reports/yearly-strategy-comparison.md")
        Files.createDirectories(out.parent)
        Files.writeString(out, report)

        assertTrue(report.contains("## 전체(거래봉 315)"), "전체 창 표")
        assertTrue(report.contains("## 선택(거래봉 193)") && report.contains("## 검증(거래봉 122)"), "분할 표")
        assertTrue(report.contains("스피어만") && report.contains("상위 ${YearlyStrategyComparison.TOP_N_FOR_RETENTION}"), "순위 안정성 절")
        assertTrue(report.contains("| 거래수 |") && report.contains("| 노출 |"), "필수 열")
    }
}
