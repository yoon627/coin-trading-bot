package com.trading.bot.engine

import com.trading.common.domain.Candle

/**
 * Stage A 오케스트레이션 — 사전고정 그리드를 yearly fixture 에 돌리고 게이트를 순서대로 적용한 뒤 리포트를 만든다.
 *
 * 게이트 순서는 **비용 순**이다: 값싼 판정(선택창 지표)으로 먼저 걸러야 검증창·국면·수수료 재실행이 소수 후보에만 든다.
 * 순서는 판정 결과를 바꾸지 않는다(모든 게이트는 AND) — 바꾸는 것은 "어디서 죽었나" 집계뿐이다.
 */
internal class StrategySearchStageA(private val search: StrategySearch = StrategySearch()) {

    data class Outcome(
        val report: String,
        val survivors: List<StrategySearchReport.Survivor>,
        val nominalConfigs: Int,
        val uniqueBehaviours: Int,
        val eliminations: Map<String, Int>,
    )

    suspend fun run(
        grid: SweepGrid = StrategySearchGrid.stageA(),
        baseline: SweepPoint = StrategySearchGrid.baselinePoint(),
        yearly: Map<String, List<Candle>>,
        bull: Map<String, List<Candle>>,
        bear: Map<String, List<Candle>>,
        nullSummary: StrategySearchReport.NullSummary?,
        metadata: Map<String, String>,
        log: (String) -> Unit = {},
    ): Outcome {
        val points = grid.points
        log("[stageA] 좌표 ${points.size} · 마켓 ${yearly.size}")

        val select = search.measure(yearly, StrategySearch.SELECT, points)
        val baseSelect = select.getValue(baseline)
        log("[stageA] 선택창 측정 완료 — baseline 거래 ${baseSelect.trades}건")

        // 서로 같은 거래를 내는 좌표는 하나로 접는다 — 이게 다중비교의 진짜 분모다.
        val representatives = LinkedHashMap<Long, SweepPoint>()
        for (p in points) representatives.putIfAbsent(select.getValue(p).fingerprint, p)
        val unique = representatives.values.toList()
        log("[stageA] 고유 행동 ${unique.size} / 명목 ${points.size}")

        // plateau 는 **모든** 좌표의 통과 여부를 봐야 한다(이웃이 대표 좌표가 아닐 수 있다).
        // G5 를 함께 건다 — 하락 국면에서는 **거래를 거의 안 한 좌표**의 paired delta 가 양수가 되므로,
        // G5 없이 세면 "효과가 이웃으로 이어진다" 가 아니라 "이웃이 아무것도 안 한다" 로 plateau 가 채워진다.
        val g1Pass = points.filter {
            val m = select.getValue(it)
            StrategySearchGates.g5(m.trades, m.zeroTradeMarkets) && StrategySearchGates.g1(returnDeltas(select, it, baseline))
        }.toHashSet()

        val eliminations = LinkedHashMap<String, Int>()
        var alive = unique
        fun stage(name: String, keep: (SweepPoint) -> Boolean) {
            val kept = alive.filter(keep)
            eliminations[name] = alive.size - kept.size
            alive = kept
            log("[stageA] $name 통과 ${kept.size}")
        }

        stage("G5") { select.getValue(it).let { m -> StrategySearchGates.g5(m.trades, m.zeroTradeMarkets) } }
        stage("G1") { it in g1Pass }
        stage("G3") { StrategySearchGates.plateau(grid.neighbours(it), g1Pass) }
        stage("G6") {
            val m = select.getValue(it)
            StrategySearchGates.g6(mddDeltas(select, it, baseline), m.worstMdd, baseSelect.worstMdd)
        }

        val validate = measureIfAny(yearly, StrategySearch.VALIDATE, alive, baseline)
        stage("G2") { p -> validate?.let { StrategySearchGates.g2(returnDeltas(it, p, baseline)) } ?: false }

        val bullMetrics = measureIfAny(bull, StrategySearch.REGIME, alive, baseline)
        stage("G4a") { p ->
            bullMetrics?.let {
                StrategySearchGates.g4(returnDeltas(it, p, baseline)) &&
                    StrategySearchGates.g4(returnDeltas(it, p, baseline, BacktestFixtures.PAIRED_MARKETS))
            } ?: false
        }

        val bearMetrics = measureIfAny(bear, StrategySearch.REGIME, alive, baseline)
        stage("G4b") { p -> bearMetrics?.let { StrategySearchGates.g4(returnDeltas(it, p, baseline)) } ?: false }

        val feeRuns = FEE_LEVELS.associateWith { fee ->
            measureIfAny(yearly, StrategySearch.SELECT, alive, baseline, StrategySearch.Options(feeRate = fee))
        }
        stage("G7") { p ->
            feeRuns.values.all { run -> run?.let { StrategySearchGates.g1(returnDeltas(it, p, baseline)) } ?: false }
        }

        val survivors = alive.map { p ->
            val s = select.getValue(p)
            StrategySearchReport.Survivor(
                point = p,
                selectMedian = SwingMetrics.median(returnDeltas(select, p, baseline)),
                selectPositive = returnDeltas(select, p, baseline).count { it > 0 },
                validateMedian = SwingMetrics.median(returnDeltas(validate!!, p, baseline)),
                validatePositive = returnDeltas(validate, p, baseline).count { it > 0 },
                bullMedian = SwingMetrics.median(returnDeltas(bullMetrics!!, p, baseline)),
                bearMedian = SwingMetrics.median(returnDeltas(bearMetrics!!, p, baseline)),
                mddMedianDelta = SwingMetrics.median(mddDeltas(select, p, baseline)),
                worstMdd = s.worstMdd,
                trades = s.trades,
                exposure = s.exposure,
                winRate = s.winRate,
                breakEvenWinRate = s.breakEvenWinRate,
                priceGateShare = s.priceGateShare,
                feeSensitivity = FEE_LEVELS.joinToString(" / ") { fee ->
                    "%.2f".format(SwingMetrics.median(returnDeltas(feeRuns.getValue(fee)!!, p, baseline)))
                },
                plateauRatio = grid.neighbours(p).let { n -> if (n.isEmpty()) 0.0 else n.count { it in g1Pass }.toDouble() / n.size },
            )
        }

        val report = StrategySearchReport.render(
            title = "Stage A — 진입·청산 파라미터 스윕 (yearly fixture, 2025-09-03~2026-09-02)",
            nominalConfigs = points.size,
            uniqueBehaviours = unique.size,
            eliminations = eliminations,
            survivors = survivors,
            nullSummary = nullSummary,
            metadata = metadata,
        )
        return Outcome(report, survivors, points.size, unique.size, eliminations)
    }

    /**
     * null 대조군 — 진입 신호를 무작위로 바꾼 뒤 **같은 exit 그리드**를 뒤진다. 각 seed 가 곧 한 번의 탐색이고,
     * seed 별 최고 선택창 delta(max-statistic)의 분포가 "잡음만 있을 때 이 정도는 나온다"의 기준선이다.
     *
     * 실제 탐색은 전략×kValue 13조합 × exit 3,960 = 51,480 좌표이고 seed 하나는 3,960 좌표뿐이다. 즉 seed 단위
     * max-statistic 은 실제 탐색보다 **좁은** 탐색의 값이므로, 13배 넓은 탐색의 95% 분위는
     * `q(0.95^(1/13)) ≈ q(0.9961)` 로 환산해 함께 보고한다(경험 분포의 사실상 최댓값).
     */
    suspend fun runNull(
        yearly: Map<String, List<Candle>>,
        seeds: List<Int>,
        entryRate: Double,
        exitGrid: SweepGrid = defaultNullExitGrid(),
        log: (String) -> Unit = {},
    ): StrategySearchReport.NullSummary {
        val noiseBaseline = StrategySearchGrid.baselinePoint().copy(strategy = RandomEntryStrategy.NAME)
        val liveBaseline = StrategySearchGrid.baselinePoint()

        // 라이브 baseline 은 실제 전략으로 한 번만 잰다 — null 후보를 **실제 기준**과 겨루게 하려면 이게 있어야 한다.
        val liveSelect = search.measure(yearly, StrategySearch.SELECT, listOf(liveBaseline)).getValue(liveBaseline)
        val liveValidate = search.measure(yearly, StrategySearch.VALIDATE, listOf(liveBaseline)).getValue(liveBaseline)

        val vsLive = VariantAccumulator()
        val vsNoise = VariantAccumulator()

        for (seed in seeds) {
            val options = StrategySearch.Options(strategyFor = { market -> RandomEntryStrategy(seed, market, entryRate) })
            val select = search.measure(yearly, StrategySearch.SELECT, exitGrid.points, options)

            // 변종 A(주 판정) — 후보만 무작위, 기준은 실제 라이브 baseline. 실제 탐색이 던지는 질문과 같은 질문이다.
            val liveDeltas = exitGrid.points.associateWith { StrategySearchGates.pairedDeltas(select.getValue(it).returnByMarket, liveSelect.returnByMarket) }
            val liveG1 = exitGrid.points.filter {
                val m = select.getValue(it)
                StrategySearchGates.g5(m.trades, m.zeroTradeMarkets) && StrategySearchGates.g1(liveDeltas.getValue(it))
            }.toHashSet()
            val liveAlive = exitGrid.points.filter { p ->
                val m = select.getValue(p)
                StrategySearchGates.g5(m.trades, m.zeroTradeMarkets) &&
                    p in liveG1 &&
                    StrategySearchGates.plateau(exitGrid.neighbours(p), liveG1) &&
                    StrategySearchGates.g6(
                        StrategySearchGates.pairedDeltas(m.mddByMarket, liveSelect.mddByMarket),
                        m.worstMdd,
                        liveSelect.worstMdd,
                    )
            }
            val liveValidateRun = if (liveAlive.isEmpty()) null else search.measure(yearly, StrategySearch.VALIDATE, liveAlive, options)
            val livePassed = liveAlive.count { p ->
                liveValidateRun?.let {
                    StrategySearchGates.g2(StrategySearchGates.pairedDeltas(it.getValue(p).returnByMarket, liveValidate.returnByMarket))
                } ?: false
            }
            val eligible = exitGrid.points.filter { select.getValue(it).let { m -> StrategySearchGates.g5(m.trades, m.zeroTradeMarkets) } }
            vsLive.add(livePassed, eligible.maxOfOrNull { SwingMetrics.median(liveDeltas.getValue(it)) } ?: Double.NEGATIVE_INFINITY)

            // 변종 B(진단용) — 후보·기준 둘 다 무작위. 게이트 스택이 순수 잡음 환경에서 어떻게 움직이는지 보여줄 뿐,
            // 실제 탐색의 임계로 쓰면 사과-오렌지 비교다(기준이 형편없고 변동이 커서 delta 분포가 부푼다).
            val noiseG1 = exitGrid.points.filter {
                val m = select.getValue(it)
                StrategySearchGates.g5(m.trades, m.zeroTradeMarkets) && StrategySearchGates.g1(returnDeltas(select, it, noiseBaseline))
            }.toHashSet()
            val noiseAlive = exitGrid.points.filter { p ->
                val m = select.getValue(p)
                StrategySearchGates.g5(m.trades, m.zeroTradeMarkets) &&
                    p in noiseG1 &&
                    StrategySearchGates.plateau(exitGrid.neighbours(p), noiseG1) &&
                    StrategySearchGates.g6(mddDeltas(select, p, noiseBaseline), m.worstMdd, select.getValue(noiseBaseline).worstMdd)
            }
            val noiseValidate = measureIfAny(yearly, StrategySearch.VALIDATE, noiseAlive, noiseBaseline, options)
            val noisePassed = noiseAlive.count { p -> noiseValidate?.let { StrategySearchGates.g2(returnDeltas(it, p, noiseBaseline)) } ?: false }
            vsNoise.add(noisePassed, eligible.maxOfOrNull { SwingMetrics.median(returnDeltas(select, it, noiseBaseline)) } ?: Double.NEGATIVE_INFINITY)

            log("[null] seed=$seed vsLive 통과 $livePassed(max %.2f) · vsNoise 통과 $noisePassed(max %.2f)"
                .format(vsLive.lastMax, vsNoise.lastMax))
        }

        return StrategySearchReport.NullSummary(
            seeds = seeds.size,
            gridSize = exitGrid.points.size,
            entryRate = entryRate,
            vsLive = vsLive.build(),
            vsNoise = vsNoise.build(),
        )
    }

    private class VariantAccumulator {
        private val maxStats = ArrayList<Double>()
        private var anyPass = 0
        private var passTotal = 0
        var lastMax = 0.0
            private set

        fun add(passed: Int, maxStat: Double) {
            maxStats += maxStat
            lastMax = maxStat
            if (passed > 0) anyPass++
            passTotal += passed
        }

        fun build(): StrategySearchReport.NullVariant {
            val sorted = maxStats.sorted()
            fun q(p: Double) = if (sorted.isEmpty()) Double.NaN else sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]
            return StrategySearchReport.NullVariant(
                anyPassRate = anyPass.toDouble() / maxStats.size,
                meanPassCount = passTotal.toDouble() / maxStats.size,
                maxStatQ95 = q(0.95),
                // 실제 탐색은 전략×kValue 13조합만큼 넓다 — 13배 넓은 탐색의 95% 분위 = q(0.95^(1/13)).
                maxStatQ95Scaled = q(Math.pow(0.95, 1.0 / 13.0)),
            )
        }
    }

    private suspend fun measureIfAny(
        fixtures: Map<String, List<Candle>>,
        segment: StrategySearch.Segment,
        alive: List<SweepPoint>,
        baseline: SweepPoint,
        options: StrategySearch.Options = StrategySearch.Options(),
    ): Map<SweepPoint, StrategySearch.Metrics>? {
        if (alive.isEmpty()) return null
        return search.measure(fixtures, segment, (alive + baseline).distinct(), options)
    }

    private fun returnDeltas(
        metrics: Map<SweepPoint, StrategySearch.Metrics>,
        point: SweepPoint,
        baseline: SweepPoint,
        markets: List<String>? = null,
    ): List<Double> {
        val candidate = metrics.getValue(point).returnByMarket.filterKeys { markets == null || it in markets }
        val base = metrics.getValue(baseline).returnByMarket.filterKeys { markets == null || it in markets }
        return StrategySearchGates.pairedDeltas(candidate, base)
    }

    private fun mddDeltas(
        metrics: Map<SweepPoint, StrategySearch.Metrics>,
        point: SweepPoint,
        baseline: SweepPoint,
    ): List<Double> = StrategySearchGates.pairedDeltas(
        metrics.getValue(point).mddByMarket,
        metrics.getValue(baseline).mddByMarket,
    )

    companion object {
        /** G7 비용 민감도 — 왕복 0.2% / 0.4%. 거래가 잦은 후보의 우위가 비용에서 오는지 본다. */
        val FEE_LEVELS = listOf(0.001, 0.002)

        /**
         * null 대조군이 뒤지는 exit 공간 — 실제 탐색의 전략×kValue 한 조합분과 같은 넓이다.
         * kValue 축은 `random_entry` 가 신호에서 읽지 않으므로 이웃에서 빠지고, 그만큼 plateau 분모가 실제보다 작다.
         */
        fun defaultNullExitGrid() = SweepGrid(
            StrategySearchGrid.stageA().points
                .filter { it.strategy == StrategySearchGrid.BASELINE_STRATEGY && it.kValue == 0.5 }
                .map { it.copy(strategy = RandomEntryStrategy.NAME) },
        )
    }
}
