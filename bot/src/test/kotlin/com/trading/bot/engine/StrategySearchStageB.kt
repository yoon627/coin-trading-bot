package com.trading.bot.engine

import com.trading.common.domain.Candle

/**
 * Stage B — 라이브에 없는 신규 아이디어(레짐 필터 기간·ATR 가변 손절익절·부분 익절)를 Stage A 와 **같은 지표·같은 게이트**로 잰다.
 *
 * Stage A 와 다른 점 둘:
 * - **G3(plateau)를 적용하지 않는다.** 축당 값이 3개 미만이라 "한 축 ±1 스텝 이웃"이 정의되지 않는다(사전고정 plan Decisions 4).
 * - base config 집합이 사전고정돼 있다. 트레일링이 항상 먼저 평가되므로 **trail OFF 조합이 없으면 ATR 손절이 한 번도 발동하지 않는다.**
 */
internal class StrategySearchStageB(private val search: StrategySearch = StrategySearch()) {

    /** 한 실험 = (이름, config 변형). baseline 은 언제나 라이브 현행이다. */
    data class Cell(val group: String, val label: String, val config: BacktestConfig)

    data class Row(
        val group: String,
        val label: String,
        val selectMedian: Double,
        val selectPositive: Int,
        val validateMedian: Double,
        val bullMedian: Double,
        val trades: Int,
        val exposure: Double,
        val worstMdd: Double,
        val mddMedianDelta: Double,
        val gates: String,
    )

    suspend fun run(
        yearly: Map<String, List<Candle>>,
        bull: Map<String, List<Candle>>,
        log: (String) -> Unit = {},
    ): List<Row> {
        val cells = cells()
        log("[stageB] 셀 ${cells.size}")

        // Stage B 는 config 를 직접 다루므로 좌표(SweepPoint) 대신 config 로 측정한다 — 전략은 baseline 과 같은 combined 고정.
        val points = cells.mapIndexed { i, cell -> cell to syntheticPoint(i) }
        val baselinePoint = StrategySearchGrid.baselinePoint()

        val select = measure(yearly, StrategySearch.SELECT, points, baselinePoint)
        val validate = measure(yearly, StrategySearch.VALIDATE, points, baselinePoint)
        val bullMetrics = measure(bull, StrategySearch.REGIME, points, baselinePoint)

        return points.map { (cell, point) ->
            val s = select.getValue(point)
            val selectDeltas = StrategySearchGates.pairedDeltas(s.returnByMarket, select.getValue(baselinePoint).returnByMarket)
            val validateDeltas = StrategySearchGates.pairedDeltas(validate.getValue(point).returnByMarket, validate.getValue(baselinePoint).returnByMarket)
            val bullDeltas = StrategySearchGates.pairedDeltas(bullMetrics.getValue(point).returnByMarket, bullMetrics.getValue(baselinePoint).returnByMarket)
            val mddDeltas = StrategySearchGates.pairedDeltas(s.mddByMarket, select.getValue(baselinePoint).mddByMarket)
            val gates = buildList {
                if (StrategySearchGates.g5(s.trades, s.zeroTradeMarkets)) add("G5") else add("~G5")
                if (StrategySearchGates.g1(selectDeltas)) add("G1") else add("~G1")
                if (StrategySearchGates.g2(validateDeltas)) add("G2") else add("~G2")
                if (StrategySearchGates.g4(bullDeltas)) add("G4a") else add("~G4a")
                if (StrategySearchGates.g6(mddDeltas, s.worstMdd, select.getValue(baselinePoint).worstMdd)) add("G6") else add("~G6")
            }.joinToString(" ")
            Row(
                group = cell.group,
                label = cell.label,
                selectMedian = SwingMetrics.median(selectDeltas),
                selectPositive = selectDeltas.count { it > 0 },
                validateMedian = SwingMetrics.median(validateDeltas),
                bullMedian = SwingMetrics.median(bullDeltas),
                trades = s.trades,
                exposure = s.exposure,
                worstMdd = s.worstMdd,
                mddMedianDelta = SwingMetrics.median(mddDeltas),
                gates = gates,
            )
        }
    }

    /**
     * [StrategySearch.measure] 는 좌표(SweepPoint)에서 config 를 만든다. Stage B 는 그 좌표로 표현할 수 없는 노브를 쓰므로
     * config 를 직접 넘길 수 있게 좌표 → config 매핑을 여기서 준다.
     */
    private suspend fun measure(
        fixtures: Map<String, List<Candle>>,
        segment: StrategySearch.Segment,
        points: List<Pair<Cell, SweepPoint>>,
        baselinePoint: SweepPoint,
    ): Map<SweepPoint, StrategySearch.Metrics> {
        val byPoint = points.associate { (cell, point) -> point to cell.config }
        return search.measureWithConfigs(
            fixtures = fixtures,
            segment = segment,
            configs = byPoint + (baselinePoint to baselinePoint.toConfig()),
        )
    }

    /** 좌표계에 없는 셀을 식별만 하기 위한 더미 좌표 — 값 자체는 쓰이지 않는다(config 가 진실). */
    private fun syntheticPoint(index: Int) = StrategySearchGrid.baselinePoint().copy(maxHoldDays = 1000 + index)

    /**
     * 사전고정 셀 목록(plan Decisions 4~7).
     * base = 라이브 현행 + `{hold 1,3,365} × {TP 5, OFF}` 소집합, ATR·부분익절은 trail OFF 에서만 의미가 있다.
     */
    private fun cells(): List<Cell> = buildList {
        val live = StrategySearchGrid.baselinePoint().toConfig()

        // 1) 레짐 필터 기간 — 라이브 현행 위에 필터만 얹는다.
        for (period in listOf(10, 20, 50)) {
            add(Cell("레짐필터", "MA$period", live.copy(useMarketFilter = true, marketFilterMaPeriod = period)))
        }

        // 2) ATR 가변 손절·익절 — 트레일링이 먼저 평가되므로 trail OFF 에서 잰다.
        val atrBase = live.copy(trailingStopPct = StrategySearchGrid.TRAILING_OFF, trailingArmPct = 0.0)
        for (multiplier in listOf(1.0, 1.5, 2.0, 3.0)) {
            for (r in listOf(null, 1.0, 2.0, 3.0)) {
                for (hold in listOf(1, 3, 365)) {
                    val label = "SL ${multiplier}×ATR / TP ${r?.let { "${it}R" } ?: "5%"} / h$hold"
                    add(Cell("ATR", label, atrBase.copy(atrStopMultiplier = multiplier, atrTakeProfitR = r, maxHoldDays = hold)))
                }
            }
        }

        // 3) 부분 익절 — 잔여가 굴러갈 여지가 있어야 하므로 TP 를 넓히거나 끄고 본다.
        for (pct in listOf(2.0, 3.0)) {
            for (fraction in listOf(0.3, 0.5)) {
                for (tp in listOf(5.0, 12.0, StrategySearchGrid.TAKE_PROFIT_OFF)) {
                    for (hold in listOf(1, 3)) {
                        val label = "${pct}%@${(fraction * 100).toInt()}% / TP ${if (tp >= StrategySearchGrid.TAKE_PROFIT_OFF) "off" else "$tp%"} / h$hold"
                        add(
                            Cell(
                                "부분익절", label,
                                live.copy(
                                    takeProfitPct = tp, maxHoldDays = hold,
                                    partialTakeProfitPct = pct, partialTakeProfitFraction = fraction,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun render(rows: List<Row>): String = buildString {
            appendLine("## Stage B — 신규 아이디어")
            appendLine()
            appendLine("Stage A 와 같은 지표·같은 게이트(G3 plateau 제외 — 축당 값이 3개 미만이라 이웃이 정의되지 않는다). 기준은 라이브 현행 `combined`.")
            appendLine("ATR 셀은 트레일링을 끈 상태에서 잰다 — 트레일링이 항상 먼저 평가되므로 켜 두면 ATR 손절이 거의 발동하지 않는다.")
            appendLine()
            for (group in rows.map { it.group }.distinct()) {
                appendLine("### $group")
                appendLine()
                appendLine("| 셀 | 선택 중앙 %p | 선택 +마켓 | 검증 중앙 %p | bull %p | 거래수 | 노출 | 최악 MDD %p | MDD Δ중앙 %p | 게이트 |")
                appendLine("|---|---|---|---|---|---|---|---|---|---|")
                for (r in rows.filter { it.group == group }.sortedByDescending { it.selectMedian }) {
                    // label 에 % 가 들어가므로 포맷 문자열에 보간하지 말고 인자로 넘긴다.
                    appendLine(
                        "| %s | %.2f | %d/8 | %.2f | %.2f | %d | %.2f | %.1f | %.2f | %s |".format(
                            r.label, r.selectMedian, r.selectPositive, r.validateMedian, r.bullMedian,
                            r.trades, r.exposure, r.worstMdd, r.mddMedianDelta, r.gates,
                        ),
                    )
                }
                appendLine()
            }
        }
    }
}
