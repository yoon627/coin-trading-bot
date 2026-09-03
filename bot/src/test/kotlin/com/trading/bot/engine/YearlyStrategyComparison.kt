package com.trading.bot.engine

import com.trading.common.domain.Candle
import com.trading.common.strategy.BollingerBounce
import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.GoldenCross
import com.trading.common.strategy.KneePullback
import com.trading.common.strategy.KneeReversal
import com.trading.common.strategy.MacdCross
import com.trading.common.strategy.MeanReversion
import com.trading.common.strategy.RsiBounce
import com.trading.common.strategy.TradingStrategy
import com.trading.common.strategy.VolatilityBreakout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 1년 fixture 위에서 스윙 9종(재진입 2모드)·적립 사다리·단순보유를 **같은 거래 구간·같은 지표**로 비교하는 분석 하네스.
 *
 * 지표는 세 계열이 원래 다르게 정의해 두었기 때문에 여기서 다시 계산한다:
 * - 수익률 = 고정 노셔널 예산 대비 순수익률. 스윙은 `Σ 거래별 net pnl%`(all-in 복리 `totalReturnPct` 는 전략 줄세우기에
 *   쓰지 않는다 — wiki strategy-evolution-expectations, 라이브도 `maxInvestAmount` 고정 노셔널), 적립은 `netReturnPct`,
 *   단순보유는 첫 거래봉 종가 → 마지막 종가(왕복 0.1% 차감).
 * - MDD = 봉단위 mark-to-market equity(예산=100) 의 peak-to-trough. 엔진의 스윙 MDD 는 청산 시점에만 갱신돼 미실현 낙폭을 빼먹는다.
 * - 노출 = 예산×시간 평균 투입 비율. 스윙 `Σ holdDays / 거래봉수`(same-bar 왕복은 0), 적립 `avgInvestedFraction`, 단순보유 1.
 * - 거래수 = 스윙 왕복, 적립 `buys + sells`, 단순보유 1 — 순위 적격 하한은 스윙에만 건다(나머지는 항상 시장에 있다).
 */
internal class YearlyStrategyComparison(
    private val engine: BacktestEngine,
    private val strategies: List<TradingStrategy>,
) {
    /** 거래 구간을 기준으로 정의한다 — 스윙 엔진이 입력의 앞 50봉을 워밍업으로 먹으므로 입력은 그만큼 앞에서 시작한다. */
    enum class Window(val inputRange: IntRange, val tradeRange: IntRange, val label: String) {
        FULL(0..364, 50..364, "전체"),
        SELECT(0..242, 50..242, "선택"),
        VALIDATE(193..364, 243..364, "검증"),
    }

    data class Row(
        val candidate: String,
        val market: String,
        val window: Window,
        val netReturnPct: Double,
        val mddPct: Double,
        val trades: Int,
        val exposure: Double,
        /** 참고열 — 스윙만, all-in 복리. */
        val compoundedPct: Double?,
        val swing: Boolean = false,
    )

    suspend fun compare(fixtures: Map<String, List<Candle>>, window: Window): List<Row> {
        require(window.tradeRange.first - window.inputRange.first == BacktestEngine.MIN_CANDLES && window.tradeRange.last == window.inputRange.last) {
            "$window: 거래 구간은 입력 구간에서 워밍업 ${BacktestEngine.MIN_CANDLES}봉을 뗀 것이어야 한다"
        }
        val rows = mutableListOf<Row>()
        for ((market, newestFirst) in fixtures) {
            val chronological = newestFirst.reversed()
            require(chronological.size == YearlyFixtures.BARS) { "$market: ${chronological.size}봉" }
            val input = chronological.subList(window.inputRange.first, window.inputRange.last + 1)
            val tradeBars = chronological.subList(window.tradeRange.first, window.tradeRange.last + 1)
            val closes = tradeBars.map { it.tradePrice }
            val warmup = window.tradeRange.first - window.inputRange.first

            for (strategy in strategies) for (mode in ReentryMode.entries) {
                val result = engine.run(strategy.name, input.reversed(), market, BacktestConfig(reentryMode = mode))
                    ?: error("${strategy.name}/$mode/$market: 결과 없음")
                // 엔진의 거래 인덱스는 입력 기준 — 워밍업만큼 당겨 거래 구간 기준으로 맞춘다.
                val trades = result.trades.map { it.copy(buyIndex = it.buyIndex - warmup, sellIndex = it.sellIndex - warmup) }
                rows += Row(
                    candidate = "${strategy.name}/${modeLabel(mode)}",
                    market = market,
                    window = window,
                    netReturnPct = trades.sumOf { it.pnlPercent },
                    mddPct = maxDrawdownPct(swingEquityCurve(closes, trades)),
                    trades = trades.size,
                    exposure = trades.sumOf { it.holdDays }.toDouble() / closes.size,
                    compoundedPct = result.totalReturnPct,
                    swing = true,
                )
            }

            val ladder = AccumulateBacktest().run(tradeBars.reversed(), market)
            rows += Row("accumulate/5-3-3", market, window, ladder.netReturnPct, ladder.maxDrawdownPct, ladder.buys + ladder.sells, ladder.avgInvestedFraction, null)

            val first = closes.first()
            val bhCurve = closes.map { it / first * EQUITY_BASE }
            rows += Row("buy-and-hold", market, window, (closes.last() / first - 1.0) * 100.0 - ROUND_TRIP_FEE_PCT, maxDrawdownPct(bhCurve), 1, 1.0, null)
        }
        return rows
    }

    private class Summary(val candidate: String, val median: Double, val mean: Double, val worstMdd: Double, val medianMdd: Double, val trades: Int, val zeroTradeMarkets: Int, val exposure: Double, val compounded: Double?, val swing: Boolean) {
        val eligible get() = !swing || trades >= MIN_TRADES_FOR_RANK
    }

    private fun summarize(rows: List<Row>): List<Summary> =
        rows.groupBy { it.candidate }.map { (candidate, rs) ->
            Summary(
                candidate,
                median = rs.map { it.netReturnPct }.median(),
                mean = rs.map { it.netReturnPct }.average(),
                worstMdd = rs.maxOf { it.mddPct },
                medianMdd = rs.map { it.mddPct }.median(),
                trades = rs.sumOf { it.trades },
                zeroTradeMarkets = rs.count { it.trades == 0 },
                exposure = rs.map { it.exposure }.average(),
                compounded = rs.mapNotNull { it.compoundedPct }.takeIf { it.isNotEmpty() }?.median(),
                swing = rs.all { it.swing },
            )
        }

    /** 순위 = 적격 후보만, 중앙값 내림차순 competition rank(동점은 같은 순위). 비적격은 null. */
    private fun ranks(summaries: List<Summary>): Map<String, Int?> {
        val eligible = summaries.filter { it.eligible }
        return summaries.associate { s -> s.candidate to if (s.eligible) eligible.count { it.median > s.median } + 1 else null }
    }

    private fun rankLabel(s: Summary, summaries: List<Summary>, rank: Int?): String = when {
        rank == null -> "—"
        summaries.count { it.eligible && it.median == s.median } > 1 -> "$rank="
        else -> rank.toString()
    }

    suspend fun report(fixtures: Map<String, List<Candle>>): String {
        val sb = StringBuilder()
        sb.appendLine("# 운영 8종 1년 전략 비교 (fixture yearly/, 2025-09-03 ~ 2026-09-02)")
        sb.appendLine()
        sb.appendLine("지표: 수익률 = 고정 노셔널 예산 대비 순수익률(수수료 왕복 0.1% 차감) · MDD = 봉단위 mark-to-market equity 의 예산 대비 낙폭 · 노출 = 예산×시간 평균 투입 비율(스윙 Σ보유봉/거래봉, 적립 평균 투입원가/예산, 보유 1) · 거래수 = 스윙 왕복 / 적립 매수+매도 / 보유 1. 스윙은 거래수 < $MIN_TRADES_FOR_RANK 이면 순위 제외(—), 동점은 같은 순위(=). 복리 열은 참고용(all-in 복리, 줄세우기 금지).")
        sb.appendLine()
        val perWindow = Window.entries.associateWith { compare(fixtures, it) }
        for (window in Window.entries) {
            val rows = perWindow.getValue(window)
            val summaries = summarize(rows)
            val rank = ranks(summaries)
            sb.appendLine("## ${window.label}(거래봉 ${window.tradeRange.count()})")
            sb.appendLine()
            sb.appendLine("| 순위 | 후보 | 중앙값 % | 평균 % | 최악 MDD % | 중앙 MDD % | 거래수 | 0거래 마켓 | 노출 | 복리(참고) % |")
            sb.appendLine("|---|---|---|---|---|---|---|---|---|---|")
            for (s in summaries.sortedWith(compareBy<Summary> { rank[it.candidate] ?: Int.MAX_VALUE }.thenByDescending { it.median }.thenBy { it.candidate })) {
                sb.appendLine(
                    "| ${rankLabel(s, summaries, rank[s.candidate])} | ${s.candidate} | %.2f | %.2f | %.1f | %.1f | %d | %d | %.2f | %s |"
                        .format(s.median, s.mean, s.worstMdd, s.medianMdd, s.trades, s.zeroTradeMarkets, s.exposure, s.compounded?.let { "%.2f".format(it) } ?: ""),
                )
            }
            sb.appendLine()
            if (window == Window.FULL) {
                sb.appendLine("### 마켓별 순수익률 % (전체)")
                sb.appendLine()
                val markets = fixtures.keys.toList()
                sb.appendLine("| 후보 | " + markets.joinToString(" | ") { it.removePrefix("KRW-") } + " |")
                sb.appendLine("|---|" + markets.joinToString("") { "---|" })
                for (candidate in summaries.sortedByDescending { it.median }.map { it.candidate }) {
                    val byMarket = rows.filter { it.candidate == candidate }.associateBy { it.market }
                    sb.appendLine("| $candidate | " + markets.joinToString(" | ") { "%.1f".format(byMarket.getValue(it).netReturnPct) } + " |")
                }
                sb.appendLine()
            }
        }

        // 양쪽 적격인 후보만 모아 그 안에서 다시 순위를 매긴다 — 창별 모집단이 다르면 순위값에 구멍이 나 상관·상위절반 임계가 틀어진다.
        val selectSummary = summarize(perWindow.getValue(Window.SELECT))
        val validateSummary = summarize(perWindow.getValue(Window.VALIDATE))
        val select = ranks(selectSummary)
        val validate = ranks(validateSummary)
        val both = select.keys.filter { select[it] != null && validate[it] != null }
        val selectMedian = both.map { c -> selectSummary.first { it.candidate == c }.median }
        val validateMedian = both.map { c -> validateSummary.first { it.candidate == c }.median }
        val rho = spearman(midRanks(selectMedian), midRanks(validateMedian))
        val selectRank = competitionRanks(selectMedian)
        val validateRank = competitionRanks(validateMedian)
        val top = both.indices.filter { selectRank[it] <= TOP_N_FOR_RETENTION }
        val half = (both.size + 1) / 2
        val retained = top.count { validateRank[it] <= half }
        sb.appendLine("## 순위 안정성 (선택 → 검증)")
        sb.appendLine()
        sb.appendLine("- 양쪽 적격 후보 ${both.size}개(그 안에서 재순위), 스피어만 순위상관 ρ = %.2f (동점은 평균순위; 기술통계 — 유의성 검정 없음, 실효 독립 표본 2~3).".format(rho))
        sb.appendLine("- 선택 창 상위 $TOP_N_FOR_RETENTION(동점 포함 ${top.size}개: ${top.joinToString(", ") { both[it] }}) 중 검증 창 상위 절반($half 위 이내) 잔류: $retained/${top.size} — 무작위 기준선 ≈ 50%.")
        sb.appendLine("- 검증 창 순위: " + both.indices.sortedBy { validateRank[it] }.joinToString(", ") { "${both[it]}(${validateRank[it]})" })
        sb.appendLine()
        sb.appendLine("각주: 각 창은 flat 에서 시작하고 창 끝의 열린 포지션은 END 강제 청산한다 — 선택/검증 경계(242→243)에 걸친 포지션은 두 창에서 다르게 처리되므로 전체 ≠ 선택+검증(122봉 창에서 END 비중 큼). LEGACY 재진입은 청산 후 2봉 공백(라이브보다 보수적), LIVE 는 09:00 즉시 재매수 근사. 적립은 프로덕션 OFF 인 기본값 5/3/3 의 백테. 8종은 지난 1년을 살아남은 운영 티커라 생존편향이 있다.")
        return sb.toString()
    }

    companion object {
        const val MIN_TRADES_FOR_RANK = 8
        const val ROUND_TRIP_FEE_PCT = 0.1
        /** equity 곡선의 예산 기준값 — 곡선 값이 곧 예산 대비 %. */
        const val EQUITY_BASE = 100.0
        /** 순위 안정성에서 "선택 창 상위 N" 의 N. */
        const val TOP_N_FOR_RETENTION = 3

        val ALL_STRATEGIES: List<TradingStrategy> = listOf(
            VolatilityBreakout(), GoldenCross(), BollingerBounce(), MeanReversion(), RsiBounce(),
            MacdCross(), CombinedStrategy(), KneeReversal(), KneePullback(),
        )

        private fun modeLabel(mode: ReentryMode) = when (mode) {
            ReentryMode.LEGACY_NEXT_BAR -> "legacy"
            ReentryMode.LIVE_SAME_BAR -> "live"
        }

        /**
         * 거래 구간 종가와 거래 목록(거래 구간 기준 인덱스)으로 예산=100 의 봉단위 equity. 보유 중엔 진입가 대비 미실현(왕복 수수료
         * 선차감), 청산 봉부터는 net pnl 이 실현 누적에 들어간다. LIVE 재진입은 청산 봉에 곧바로 다음 거래가 열리므로 한 봉에서
         * 거래를 계속 소진한다.
         */
        fun swingEquityCurve(closes: List<Double>, trades: List<BacktestTrade>): List<Double> {
            val sorted = trades.sortedBy { it.buyIndex }
            require(sorted.all { it.buyIndex in closes.indices && it.sellIndex in closes.indices }) { "거래 인덱스가 거래 구간 밖" }
            val curve = ArrayList<Double>(closes.size)
            var realized = 0.0
            var open: BacktestTrade? = null
            var next = 0
            for (t in closes.indices) {
                while (true) {
                    if (open == null && next < sorted.size && sorted[next].buyIndex <= t) open = sorted[next++]
                    val current = open ?: break
                    if (t < current.sellIndex) break
                    realized += current.pnlPercent
                    open = null
                }
                val unrealized = open?.let { (closes[t] / it.buyPrice - 1.0) * 100.0 - ROUND_TRIP_FEE_PCT } ?: 0.0
                curve += EQUITY_BASE + realized + unrealized
            }
            val expected = EQUITY_BASE + trades.sumOf { it.pnlPercent }
            check(next == sorted.size && open == null && abs(curve.last() - expected) < 1e-6) {
                "equity 종점 ${curve.last()} ≠ $EQUITY_BASE + Σ pnl $expected — 거래를 곡선에 다 싣지 못했다"
            }
            return curve
        }

        /** 예산=100 기준 곡선의 peak-to-trough 낙폭(%). */
        fun maxDrawdownPct(curve: List<Double>): Double {
            var peak = Double.NEGATIVE_INFINITY
            var worst = 0.0
            for (v in curve) {
                peak = max(peak, v)
                worst = max(worst, peak - v)
            }
            return worst
        }

        /** 값이 클수록 1위. 동점은 같은 순위(1,1,3,4). */
        fun competitionRanks(values: List<Double>): List<Int> = values.map { v -> values.count { it > v } + 1 }

        /** 값이 클수록 1위. 동점은 평균순위(1,2.5,2.5,4). */
        fun midRanks(values: List<Double>): List<Double> =
            values.map { v -> values.count { it > v } + (values.count { it == v } + 1) / 2.0 }

        /** 순위 벡터의 피어슨 상관 — 동점(평균순위)이 있어도 맞는 스피어만 정의. 분산 0 이면 NaN. */
        fun spearman(a: List<Double>, b: List<Double>): Double {
            require(a.size == b.size)
            val n = a.size
            if (n < 2) return Double.NaN
            val ma = a.average()
            val mb = b.average()
            val cov = a.indices.sumOf { (a[it] - ma) * (b[it] - mb) }
            val va = a.sumOf { (it - ma) * (it - ma) }
            val vb = b.sumOf { (it - mb) * (it - mb) }
            return if (va == 0.0 || vb == 0.0) Double.NaN else cov / sqrt(va * vb)
        }
    }
}

private fun List<Double>.median(): Double {
    val s = sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}
