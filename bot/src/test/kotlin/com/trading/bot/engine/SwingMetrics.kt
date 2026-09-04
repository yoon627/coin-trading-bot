package com.trading.bot.engine

import com.trading.common.domain.Candle
import kotlin.math.abs
import kotlin.math.max

/**
 * 스윙 백테 결과를 **고정 노셔널 예산** 기준 지표로 환산하는 공용 측정 단위.
 *
 * [YearlyStrategyComparison](전략 줄세우기)과 [StrategySearch](파라미터 스윕)가 같은 함수를 써야 두 리포트를 나란히
 * 놓을 수 있다. 갈라지면 스윕 결과를 선행 측정과 비교할 수 없다.
 *
 * - 수익률 = `Σ 거래별 net pnl%`. 엔진의 `totalReturnPct` 는 all-in 복리라 전략 줄세우기에 쓰지 않는다
 *   (wiki `strategy-evolution-expectations`; 라이브도 `maxInvestAmount` 고정 노셔널이다).
 * - MDD = 봉단위 mark-to-market equity(예산=100)의 peak-to-trough **절대 %p**. 엔진 MDD 는 청산 시점만 봐 미실현 낙폭을 빼먹는다.
 * - 노출 = 예산×시간 평균 투입 비율. 부분 청산이 있으면 잔여 비중으로 가중한다([BacktestTrade.partialFraction]).
 */
internal object SwingMetrics {

    const val EQUITY_BASE = 100.0
    const val ROUND_TRIP_FEE_PCT = 0.1
    const val MIN_TRADES_FOR_RANK = 8

    data class Measurement(
        val netReturnPct: Double,
        val mddPct: Double,
        val exposure: Double,
        /** 참고열 — all-in 복리. 줄세우기 금지. */
        val compoundedPct: Double,
        /** 거래 구간 기준으로 인덱스가 보정된 거래 목록. */
        val trades: List<BacktestTrade>,
    )

    /**
     * @param input 시간순 캔들. 앞 [warmup] 봉은 엔진 워밍업으로 소진되고 거래 구간에서 제외된다.
     */
    suspend fun measure(
        engine: BacktestEngine,
        strategyName: String,
        market: String,
        input: List<Candle>,
        warmup: Int,
        config: BacktestConfig,
    ): Measurement {
        val result = engine.run(strategyName, input.reversed(), market, config)
            ?: error("$strategyName/$market: 결과 없음 (입력 ${input.size}봉)")
        // 엔진의 거래 인덱스는 입력 기준 — 워밍업만큼 당겨 거래 구간 기준으로 맞춘다(부분 체결 인덱스도 같이).
        val trades = result.trades.map {
            it.copy(
                buyIndex = it.buyIndex - warmup,
                sellIndex = it.sellIndex - warmup,
                partialIndex = it.partialIndex?.minus(warmup),
            )
        }
        val closes = input.drop(warmup).map { it.tradePrice }
        // 수수료는 config 를 따른다 — 상수로 박으면 G7(수수료 2·4배) 실행에서 미실현 항만 0.1% 를 빼 MDD 가 부푼다.
        val feePct = config.feeRate * 2 * 100
        return Measurement(
            netReturnPct = trades.sumOf { it.pnlPercent },
            mddPct = maxDrawdownPct(swingEquityCurve(closes, trades, feePct)),
            exposure = trades.sumOf { it.weightedHoldDays() } / closes.size,
            compoundedPct = result.totalReturnPct,
            trades = trades,
        )
    }

    /**
     * 거래 구간 종가와 거래 목록(거래 구간 기준 인덱스)으로 예산=100 의 봉단위 equity. 보유 중엔 진입가 대비 미실현(왕복 수수료
     * 선차감), 청산 봉부터는 net pnl 이 실현 누적에 들어간다. LIVE 재진입은 청산 봉에 곧바로 다음 거래가 열리므로 한 봉에서
     * 거래를 계속 소진한다.
     */
    @JvmOverloads
    fun swingEquityCurve(closes: List<Double>, trades: List<BacktestTrade>, feePct: Double = ROUND_TRIP_FEE_PCT): List<Double> {
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
            // 부분 체결 이후 구간은 그 다리가 이미 실현됐고 잔여 비중만 시장에 남는다 — 보유 전 구간에 평균 비중을
            // 곱하면 체결 **전** 낙폭까지 축소돼 부분익절 후보의 MDD·노출이 후보에게 유리한 쪽으로 과소평가된다.
            val unrealized = open?.let { trade ->
                val partialIndex = trade.partialIndex
                val fraction = trade.partialFraction
                val done = partialIndex != null && fraction != null && t >= partialIndex
                val banked = if (done) fraction!! * trade.partialLegPnlPct!! else 0.0
                val weight = if (done) 1.0 - fraction!! else 1.0
                banked + ((closes[t] / trade.buyPrice - 1.0) * 100.0 - feePct) * weight
            } ?: 0.0
            curve += EQUITY_BASE + realized + unrealized
        }
        val expected = EQUITY_BASE + trades.sumOf { it.pnlPercent }
        check(next == sorted.size && open == null && abs(curve.last() - expected) < 1e-6) {
            "equity 종점 ${curve.last()} ≠ 기대 $expected(= $EQUITY_BASE + Σ pnl) — 거래를 곡선에 다 싣지 못했다"
        }
        return curve
    }

    /** 예산=100 기준 곡선의 peak-to-trough 낙폭(%p). */
    fun maxDrawdownPct(curve: List<Double>): Double {
        var peak = Double.NEGATIVE_INFINITY
        var worst = 0.0
        for (v in curve) {
            peak = max(peak, v)
            worst = max(worst, peak - v)
        }
        return worst
    }

    fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "빈 목록의 중앙값" }
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

/**
 * 예산×시간 노출에 실릴 보유 봉수. 부분 익절이 있으면 체결 **전** 구간은 비중 1.0, 이후는 `1 − f` 로 나눠 센다 —
 * 보유 전 구간에 평균 비중을 곱하면 노출이 실제보다 작게 잡혀 부분익절 후보에게 유리해진다.
 */
internal fun BacktestTrade.weightedHoldDays(): Double {
    val f = partialFraction ?: return holdDays.toDouble()
    val partial = partialIndex ?: return holdDays.toDouble()
    val beforePartial = (partial - buyIndex).coerceIn(0, holdDays)
    return beforePartial + (holdDays - beforePartial) * (1.0 - f)
}
