package com.trading.bot.engine

import com.trading.common.strategy.AdxTrend
import com.trading.common.strategy.CciReversal
import com.trading.common.strategy.DonchianBreakout
import com.trading.common.strategy.KeltnerBreakout
import com.trading.common.strategy.MoneyFlowIndex
import com.trading.common.strategy.ObvTrend
import com.trading.common.strategy.StochasticCross
import com.trading.common.strategy.Supertrend
import com.trading.common.strategy.TradingStrategy
import com.trading.common.strategy.VwapBand
import com.trading.common.strategy.WilliamsR

/**
 * 스윕 그리드의 **좌표계**. 사전고정 plan `2026-09-03-strategy-search-yearly` Decisions 3) 이 단일 소스다.
 *
 * 평평한 `List<BacktestConfig>` 위에서는 "각 축 ±1 스텝 이웃"(plateau 게이트 G3)이 정의되지 않아 판정이 루프 안쪽에
 * 눌러붙고 단위 테스트가 불가능해진다. 그래서 좌표 타입을 명시하고 이웃을 순수 함수로 둔다.
 *
 * **Cartesian 이 아니라 conditional 이다** — 비활성 축 조합을 만들면 다중비교 분모가 부풀고 plateau 가 무력화된다.
 */
internal data class SweepPoint(
    val strategy: String,
    val kValue: Double,
    val takeProfitPct: Double,
    val maxLossPct: Double,
    val trailingStopPct: Double,
    val trailingArmPct: Double,
    val maxHoldDays: Int,
    /** 0 = 필터 off. Stage A 는 {0, 50} 만 — 그 외 기간은 엔진 노브가 필요하다(Stage B). */
    val marketFilterMa: Int,
) {
    fun toConfig(reentryMode: ReentryMode = ReentryMode.LIVE_SAME_BAR, feeRate: Double = DEFAULT_FEE_RATE) = BacktestConfig(
        takeProfitPct = takeProfitPct,
        maxLossPct = maxLossPct,
        kValue = kValue,
        feeRate = feeRate,
        trailingStopPct = trailingStopPct,
        trailingArmPct = trailingArmPct,
        maxHoldDays = maxHoldDays,
        useMarketFilter = marketFilterMa > 0,
        reentryMode = reentryMode,
    )

    fun label(): String = "$strategy k$kValue TP${fmt(takeProfitPct)}/SL${fmt(maxLossPct)}/tr${fmt(trailingStopPct)}" +
        "/arm${fmt(trailingArmPct)}/h$maxHoldDays/f${if (marketFilterMa > 0) "MA$marketFilterMa" else "off"}"

    private fun fmt(v: Double) = if (v >= StrategySearchGrid.TAKE_PROFIT_OFF) "off" else "%.1f".format(v)

    companion object {
        const val DEFAULT_FEE_RATE = 0.0005
    }
}

internal class SweepGrid(val points: List<SweepPoint>) {
    private val index = points.toHashSet()

    /**
     * 한 축에서 한 스텝 움직인 유효 좌표들. 축 값 목록이 좌표에 의존하는 경우(arm 은 trail 에 종속)
     * 이동 후 무효가 되는 이웃은 **보정하지 않고 버린다** — 보정하면 두 축이 동시에 움직여 "한 스텝" 정의가 깨진다.
     */
    fun neighbours(point: SweepPoint): List<SweepPoint> =
        StrategySearchGrid.oneStepCandidates(point).filter { it in index }
}

internal object StrategySearchGrid {

    /** 절대 발동하지 않는 익절선 — 프로덕션에 `takeProfitEnabled` 를 추가하지 않으려는 하네스 전용 센티널. */
    const val TAKE_PROFIT_OFF = 1_000.0

    /** 절대 발동하지 않는 트레일링 폭(고점 대비 −1000% 하락은 불가능). */
    const val TRAILING_OFF = 1_000.0

    /**
     * Stage A 탐색 대상 — 라이브에 등록된 기존 9종.
     *
     * 신규 지표 전략([STAGE_D_STRATEGIES])은 여기 넣지 않는다. `ALL_STRATEGIES` 는 선행 측정
     * ([[yearly-strategy-comparison]])의 모집단이라 늘리면 그 리포트가 조용히 달라진다.
     */
    val STRATEGIES = YearlyStrategyComparison.ALL_STRATEGIES.map { it.name }

    /** Stage D — repo 에 없던 지표로 만든 진입 전략. 백테 탐색 전용이며 라이브·연간비교에 등록하지 않는다. */
    val STAGE_D: List<TradingStrategy> = listOf(
        AdxTrend(), DonchianBreakout(), KeltnerBreakout(), Supertrend(), StochasticCross(),
        WilliamsR(), ObvTrend(), MoneyFlowIndex(), CciReversal(), VwapBand(),
    )

    val STAGE_D_STRATEGIES = STAGE_D.map { it.name }

    val TAKE_PROFITS = listOf(2.0, 3.0, 5.0, 8.0, 12.0, TAKE_PROFIT_OFF)
    val STOP_LOSSES = listOf(2.0, 3.0, 5.0, 7.0, 10.0)
    val TRAILING_STOPS = listOf(1.5, 2.0, 3.0, 5.0, TRAILING_OFF)
    val ARM_CANDIDATES = listOf(0.0, 2.0, 3.0, 5.0)
    val HOLD_DAYS = listOf(1, 2, 3, 5, 10, 365)
    val MARKET_FILTERS_STAGE_A = listOf(0, 50)

    /** `kValue` 를 신호에서 읽는 전략만 그 축을 갖는다(엔진이 신호에 넘기는 유일한 config 필드). */
    fun kValuesFor(strategy: String): List<Double> =
        if (strategy == "volatility_breakout" || strategy == "combined") listOf(0.3, 0.5, 0.7) else listOf(0.5)

    /**
     * 주어진 트레일링 폭에서 **서로 다른 동작을 내는** arm 값만.
     *
     * `ExitGates.isTrailingStopTriggered` 는 `pnlPct > 0` 을 요구하는데 그 `pnlPct` 는 트레일링 체결선 기준이라,
     * 트레일링은 `peakPnl > trail/(1−trail/100)` 일 때만 발동한다. 그 하한 이하의 arm 은 전부 같은 동작(alias)이라
     * 그리드에 두면 다중비교 분모가 부풀고 plateau 이웃이 자기 자신이 된다.
     */
    fun armValuesFor(trailingStopPct: Double): List<Double> {
        if (trailingStopPct >= TRAILING_OFF) return listOf(0.0)
        val floor = trailingStopPct / (1 - trailingStopPct / 100.0)
        return listOf(0.0) + ARM_CANDIDATES.filter { it > floor }
    }

    /** 라이브 현행 설정 — 모든 게이트·리포트가 참조하는 대조군. */
    fun baselinePoint() = SweepPoint(
        strategy = BASELINE_STRATEGY,
        kValue = 0.5,
        takeProfitPct = 5.0,
        maxLossPct = 5.0,
        trailingStopPct = 2.0,
        trailingArmPct = 3.0,
        maxHoldDays = 1,
        marketFilterMa = 0,
    )

    const val BASELINE_STRATEGY = "combined"

    fun stageA(): SweepGrid = build(TAKE_PROFITS, STOP_LOSSES, TRAILING_STOPS, HOLD_DAYS, MARKET_FILTERS_STAGE_A)

    /** Stage D — 같은 exit 공간을 신규 전략 10종에 그대로 적용한다(kValue 축 없음: 이 전략들은 신호에서 읽지 않는다). */
    fun stageD(): SweepGrid =
        build(TAKE_PROFITS, STOP_LOSSES, TRAILING_STOPS, HOLD_DAYS, MARKET_FILTERS_STAGE_A, STAGE_D_STRATEGIES)

    private fun build(
        takeProfits: List<Double>,
        stopLosses: List<Double>,
        trailingStops: List<Double>,
        holdDays: List<Int>,
        filters: List<Int>,
        strategies: List<String> = STRATEGIES,
    ): SweepGrid = SweepGrid(
        buildList {
            for (strategy in strategies)
                for (k in kValuesFor(strategy))
                    for (tp in takeProfits)
                        for (sl in stopLosses)
                            for (trail in trailingStops)
                                for (arm in armValuesFor(trail))
                                    for (hold in holdDays)
                                        for (filter in filters)
                                            add(SweepPoint(strategy, k, tp, sl, trail, arm, hold, filter))
        },
    )

    /** 한 축만 한 스텝 움직인 좌표 후보(유효성은 [SweepGrid.neighbours] 가 거른다). 전략은 축이 아니다 — 이웃 관계가 없다. */
    fun oneStepCandidates(point: SweepPoint): List<SweepPoint> = buildList {
        step(kValuesFor(point.strategy), point.kValue) { add(point.copy(kValue = it)) }
        step(TAKE_PROFITS, point.takeProfitPct) { add(point.copy(takeProfitPct = it)) }
        step(STOP_LOSSES, point.maxLossPct) { add(point.copy(maxLossPct = it)) }
        step(TRAILING_STOPS, point.trailingStopPct) { add(point.copy(trailingStopPct = it)) }
        step(armValuesFor(point.trailingStopPct), point.trailingArmPct) { add(point.copy(trailingArmPct = it)) }
        step(HOLD_DAYS, point.maxHoldDays) { add(point.copy(maxHoldDays = it)) }
        step(MARKET_FILTERS_STAGE_A, point.marketFilterMa) { add(point.copy(marketFilterMa = it)) }
    }

    /** 두 좌표가 몇 축에서 몇 스텝 떨어졌는지. 축 값 목록 밖이거나 전략이 다르면 [Int.MAX_VALUE]. */
    fun axisDistance(a: SweepPoint, b: SweepPoint): Int {
        if (a.strategy != b.strategy) return Int.MAX_VALUE
        val steps = listOf(
            distance(kValuesFor(a.strategy), a.kValue, b.kValue),
            distance(TAKE_PROFITS, a.takeProfitPct, b.takeProfitPct),
            distance(STOP_LOSSES, a.maxLossPct, b.maxLossPct),
            distance(TRAILING_STOPS, a.trailingStopPct, b.trailingStopPct),
            distance(armValuesFor(a.trailingStopPct), a.trailingArmPct, b.trailingArmPct),
            distance(HOLD_DAYS, a.maxHoldDays, b.maxHoldDays),
            distance(MARKET_FILTERS_STAGE_A, a.marketFilterMa, b.marketFilterMa),
        )
        return if (steps.any { it == Int.MAX_VALUE }) Int.MAX_VALUE else steps.sum()
    }

    private fun <T> distance(axis: List<T>, from: T, to: T): Int {
        val i = axis.indexOf(from)
        val j = axis.indexOf(to)
        return if (i < 0 || j < 0) Int.MAX_VALUE else kotlin.math.abs(i - j)
    }

    private inline fun <T> step(axis: List<T>, current: T, emit: (T) -> Unit) {
        val i = axis.indexOf(current)
        if (i < 0) return
        if (i > 0) emit(axis[i - 1])
        if (i < axis.size - 1) emit(axis[i + 1])
    }
}
