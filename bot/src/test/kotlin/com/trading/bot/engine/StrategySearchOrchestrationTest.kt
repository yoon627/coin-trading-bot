package com.trading.bot.engine

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 오케스트레이션(StageA·null 대조군·StageB)을 **게이트 없이** 한 번 돌린다.
 *
 * 전체 스윕은 env 게이트 뒤에 있어 CI 에서 한 번도 실행되지 않는다. 그러면 `stage()` 의 탈락 집계, 생존자가 나올 때만
 * 밟는 non-null 경로, `measureIfAny` 의 폴백이 전부 무검증으로 남는다 — 리포트 결론을 wiki 에 실었으므로
 * **계기의 회귀는 결론의 회귀다**. 여기서는 좌표 몇 개짜리 축소 그리드로 같은 경로를 태운다.
 */
class StrategySearchOrchestrationTest {

    private val yearly = YearlyFixtures.loadAll()
    private val holdouts = BacktestFixtures.TIME_INDEPENDENT.associate { it.dir to BacktestFixtures.loadAll(it) }
    private val bull = BacktestFixtures.loadAll(BacktestFixtures.Regime.BULL)
    private val bear = BacktestFixtures.loadAll(BacktestFixtures.Regime.BEAR)

    /** 라이브 현행보다 명백히 나쁜 기준 — 생존자가 나오는 경로(검증창·국면·수수료 재실행)를 실제로 태우려면 필요하다. */
    private val weakBaseline = StrategySearchGrid.baselinePoint().copy(takeProfitPct = 2.0, maxLossPct = 2.0)

    @Test
    fun `stage A runs end to end and reports where every coordinate died`() = runTest {
        val candidates = listOf(
            weakBaseline,
            StrategySearchGrid.baselinePoint(),
            StrategySearchGrid.baselinePoint().copy(maxLossPct = 7.0),
            StrategySearchGrid.baselinePoint().copy(maxLossPct = 7.0, takeProfitPct = 8.0),
        )
        val outcome = StrategySearchStageA().run(
            grid = SweepGrid(candidates),
            baseline = weakBaseline,
            yearly = yearly,
            holdouts = holdouts,
            bear = bear,
            nullSummary = null,
            metadata = mapOf("fixture" to "테스트"),
        )

        assertEquals(candidates.size, outcome.nominalConfigs)
        assertTrue(outcome.uniqueBehaviours in 1..candidates.size, "고유 행동 수는 명목 이하")
        assertEquals(
            listOf("G5", "G1", "G3", "G6", "G2", "G4a", "G4b", "G7"),
            outcome.eliminations.keys.toList(),
            "게이트가 사전고정 순서대로 전부 집계된다",
        )
        assertTrue(outcome.report.contains("통과 ${outcome.survivors.size} / ${outcome.uniqueBehaviours}"))
        // 생존자가 나온 경로면 검증창·국면·수수료 열이 채워졌는지 확인한다(non-null 경로의 유일한 커버리지).
        for (s in outcome.survivors) {
            assertTrue(s.validateMedian.isFinite() && s.bearMedian.isFinite())
            assertTrue(s.holdoutMedians.isNotEmpty() && s.holdoutMedians.values.all { it.isFinite() }, "국면별 중앙값이 채워진다")
            assertTrue(s.feeSensitivity.isNotBlank())
            assertTrue(s.plateauRatio in 0.0..1.0)
        }
    }

    @Test
    fun `coordinates that barely trade die at G5 before they can prop up a plateau`() = runTest {
        // 하락 국면에서는 **거래를 거의 안 한 좌표**의 paired delta 가 양수가 된다(잃지 않았으니까).
        // G5 를 G1 판정과 함께 걸지 않으면 그런 퇴화 좌표가 이웃 비율을 채워 "고립 peak 배제" 라는 G3 정의가 무너진다.
        // 익절·트레일링을 끄고 보유상한을 없애면 마켓당 거래가 1건뿐인 퇴화 좌표가 된다.
        val degenerate = StrategySearchGrid.baselinePoint().copy(
            takeProfitPct = StrategySearchGrid.TAKE_PROFIT_OFF,
            trailingStopPct = StrategySearchGrid.TRAILING_OFF,
            trailingArmPct = 0.0,
            maxLossPct = 10.0,
            maxHoldDays = 365,
        )
        val oneMarket = mapOf("KRW-BTC" to YearlyFixtures.load("KRW-BTC"))
        val m = StrategySearch().measure(oneMarket, StrategySearch.SELECT, listOf(degenerate)).getValue(degenerate)
        assertFalse(
            StrategySearchGates.g5(m.trades, m.zeroTradeMarkets),
            "표본이 얇아야 이 테스트가 의미 있다 — 거래 ${m.trades}건",
        )

        val outcome = StrategySearchStageA().run(
            grid = SweepGrid(listOf(weakBaseline, degenerate)),
            baseline = weakBaseline,
            yearly = oneMarket,
            holdouts = mapOf("bull" to BacktestFixtures.loadPaired(BacktestFixtures.Regime.BULL)),
            bear = BacktestFixtures.loadPaired(BacktestFixtures.Regime.BEAR),
            nullSummary = null,
            metadata = emptyMap(),
        )
        assertTrue(outcome.eliminations.getValue("G5") >= 1, "표본 미달 좌표는 첫 게이트에서 죽는다")
        assertTrue(outcome.survivors.none { it.point == degenerate }, "생존자에 남지 않는다")
    }

    @Test
    fun `the null control runs the same gate stack and yields both variants`() = runTest {
        val exitGrid = SweepGrid(
            listOf(
                StrategySearchGrid.baselinePoint(),
                StrategySearchGrid.baselinePoint().copy(maxLossPct = 7.0),
                StrategySearchGrid.baselinePoint().copy(takeProfitPct = 8.0),
            ).map { it.copy(strategy = RandomEntryStrategy.NAME) },
        )
        val summary = StrategySearchStageA().runNull(
            yearly = yearly,
            seeds = listOf(1, 2),
            entryRate = 0.06,
            exitGrid = exitGrid,
        )
        assertEquals(2, summary.seeds)
        assertEquals(exitGrid.points.size, summary.gridSize)
        for (variant in listOf(summary.vsLive, summary.vsNoise)) {
            assertTrue(variant.anyPassRate in 0.0..1.0)
            assertTrue(variant.meanPassCount >= 0.0)
            assertTrue(variant.maxStatQ95.isFinite(), "max-statistic 이 유한해야 임계로 쓸 수 있다")
        }
    }

    @Test
    fun `stage B measures all three idea groups against the live baseline`() = runTest {
        val rows = StrategySearchStageB().run(yearly, bull)
        assertEquals(listOf("레짐필터", "ATR", "부분익절"), rows.map { it.group }.distinct())
        assertTrue(rows.all { it.selectMedian.isFinite() && it.validateMedian.isFinite() && it.bullMedian.isFinite() })
        assertTrue(rows.all { it.gates.contains("G1") }, "게이트 문자열은 통과/미통과를 모두 표기한다")
        assertTrue(StrategySearchStageB.render(rows).contains("### ATR"))
    }
}
