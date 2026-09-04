package com.trading.bot.engine

/**
 * 사전고정 판정 게이트 G1~G7 — plan `2026-09-03-strategy-search-yearly` Decisions 1) 이 단일 소스이고,
 * 그 plan 은 **결과를 보기 전에** 커밋됐다(391118e). 여기 상수를 바꾸는 것은 사전고정을 바꾸는 것이다.
 *
 * 관측치는 후보−baseline 의 **마켓별 paired delta** 다. 8마켓 공통이므로 paired 가 마켓 효과를 제거한다 —
 * `median(후보) − median(baseline)` 은 부호까지 갈릴 수 있다.
 *
 * 임계값(2.0%p·6/8·70%·1.5배)은 **운영상 최소효과·리스크 허용치**이지 통계적 유의 임계가 아니다.
 * 실효 독립 표본이 2~3(마켓 상관 0.49)이라 이 표본에서 유의성을 주장할 수 없다.
 */
internal object StrategySearchGates {

    const val G1_MEDIAN_MIN = 2.0
    const val G1_POSITIVE_MARKETS_MIN = 6
    const val G2_POSITIVE_MARKETS_MIN = 5
    const val G4_MEDIAN_MIN = -1.0
    const val G5_MIN_TRADES = SwingMetrics.MIN_TRADES_FOR_RANK
    const val G5_MAX_ZERO_TRADE_MARKETS = 1
    const val G6_MEDIAN_MDD_DELTA_MAX = 2.0
    const val G6_WORST_MDD_RATIO_MAX = 1.5
    const val PLATEAU_MIN_RATIO = 0.7

    /** 후보와 baseline 을 마켓별로 짝지어 뺀다. 두 쪽에 모두 있는 마켓만. */
    fun pairedDeltas(candidate: Map<String, Double>, baseline: Map<String, Double>): List<Double> =
        candidate.keys.intersect(baseline.keys).sorted().map { candidate.getValue(it) - baseline.getValue(it) }

    /** G1 선택창 — 운영 최소효과 + 마켓 다수결. */
    fun g1(deltas: List<Double>): Boolean =
        deltas.isNotEmpty() &&
            SwingMetrics.median(deltas) >= G1_MEDIAN_MIN &&
            deltas.count { it > 0 } >= G1_POSITIVE_MARKETS_MIN

    /**
     * G2 검증창 — 방향 일관성만 본다. 검증창 baseline 자체가 −0.4% 라 기준선이 거의 0 이고,
     * 순위상관 ρ=0.32 인 이상 **기대 통과율은 대략 50%** 다. 단독 근거로 쓰지 않는다.
     */
    fun g2(deltas: List<Double>): Boolean =
        deltas.isNotEmpty() &&
            SwingMetrics.median(deltas) >= 0.0 &&
            deltas.count { it > 0 } >= G2_POSITIVE_MARKETS_MIN

    /** G4 국면 — bull 은 시간 독립 holdout, bear 는 yearly 구간에 포함되므로 robustness 표기용. */
    fun g4(deltas: List<Double>): Boolean =
        deltas.isNotEmpty() && SwingMetrics.median(deltas) >= G4_MEDIAN_MIN

    fun g5(trades: Int, zeroTradeMarkets: Int): Boolean =
        trades >= G5_MIN_TRADES && zeroTradeMarkets <= G5_MAX_ZERO_TRADE_MARKETS

    /**
     * G6 낙폭 — 중앙값을 주 기준으로 둔다. 최악 마켓 MDD 는 8개 중 최대라는 극단 순서통계량이라 잡음이 지배한다.
     * MDD 는 예산 100 기준 **절대 %p** 이지 peak 대비 비율이 아니다.
     */
    fun g6(mddDeltas: List<Double>, worst: Double, baselineWorst: Double): Boolean =
        mddDeltas.isNotEmpty() &&
            SwingMetrics.median(mddDeltas) <= G6_MEDIAN_MDD_DELTA_MAX &&
            worst <= baselineWorst * G6_WORST_MDD_RATIO_MAX

    /**
     * G3 plateau — 이웃 대부분이 함께 통과해야 한다. 이웃이 없으면 **통과가 아니다**(고립 좌표는 plateau 를 주장할 수 없다).
     */
    fun plateau(neighbours: List<SweepPoint>, passing: Set<SweepPoint>): Boolean {
        if (neighbours.isEmpty()) return false
        return neighbours.count { it in passing }.toDouble() / neighbours.size >= PLATEAU_MIN_RATIO
    }

}
