package com.trading.bot.engine

/**
 * 스윕 리포트 렌더러.
 *
 * **통과 0건도 산출물이다** — 분모(명목 config 수 / 고유 행동 수)와 게이트별 탈락 수를 항상 찍는다. 그게 없으면
 * "없었다"와 "못 찾았다"를 독자가 구분할 수 없다.
 */
internal object StrategySearchReport {

    data class Survivor(
        val point: SweepPoint,
        val selectMedian: Double,
        val selectPositive: Int,
        val validateMedian: Double,
        val validatePositive: Int,
        val bullMedian: Double,
        val bearMedian: Double,
        val mddMedianDelta: Double,
        val worstMdd: Double,
        val trades: Int,
        val exposure: Double,
        val winRate: Double,
        val breakEvenWinRate: Double,
        val priceGateShare: Double,
        val feeSensitivity: String,
        val plateauRatio: Double,
    )

    data class NullVariant(
        val anyPassRate: Double,
        val meanPassCount: Double,
        val maxStatQ95: Double,
        /** 실제 탐색은 전략×kValue 13조합만큼 넓다 — 그 폭으로 환산한 95% 분위. */
        val maxStatQ95Scaled: Double,
    )

    data class NullSummary(
        val seeds: Int,
        val gridSize: Int,
        val entryRate: Double,
        /** 주 판정 — 후보만 무작위, 기준은 실제 라이브 baseline. */
        val vsLive: NullVariant,
        /** 진단용 — 후보·기준 둘 다 무작위. 임계로 쓰지 않는다. */
        val vsNoise: NullVariant,
    )

    fun render(
        title: String,
        nominalConfigs: Int,
        uniqueBehaviours: Int,
        eliminations: Map<String, Int>,
        survivors: List<Survivor>,
        nullSummary: NullSummary?,
        metadata: Map<String, String>,
    ): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("**통과 ${survivors.size} / $uniqueBehaviours** (고유 행동 기준, 명목 $nominalConfigs config)")
        appendLine()
        appendLine("판정 기준은 실행 전에 고정됐다(plan `2026-09-03-strategy-search-yearly`, 커밋 391118e). 임계값은 운영상 최소효과·리스크 허용치이지 통계적 유의 임계가 아니다 — 마켓 상관 0.49, 실효 독립 표본 2~3.")
        appendLine()

        appendLine("## 게이트별 탈락")
        appendLine()
        appendLine("| 게이트 | 의미 | 여기서 탈락 |")
        appendLine("|---|---|---|")
        for ((gate, count) in eliminations) appendLine("| $gate | ${GATE_MEANING[gate] ?: ""} | $count |")
        appendLine()

        if (survivors.isEmpty()) {
            appendLine("## 결론 — 통과 후보 없음")
            appendLine()
            appendLine("이 그리드·이 fixture·이 게이트에서 라이브 baseline 을 사전고정 기준으로 이긴 좌표는 없다. \"더 나은 설정이 존재하지 않는다\"가 아니라 **이 공간에서 이 표본으로는 잡히지 않는다**는 뜻이다.")
            appendLine()
        } else {
            appendLine("## 통과 후보")
            appendLine()
            appendLine("| 후보 | 선택 중앙 %p | 선택 +마켓 | 검증 중앙 %p | 검증 +마켓 | bull %p | bear %p | MDD Δ중앙 %p | 최악 MDD %p | 거래수 | 노출 | 승률 % | 손익분기 % | TP/SL 비중 | 비용민감도 | plateau |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
            for (s in survivors.sortedByDescending { it.selectMedian }) {
                appendLine(
                    // 손익분기 승률은 승/패 한쪽이 0 이면 정의되지 않는다 — 표에 NaN 을 흘리지 않고 —로 적는다.
                    "| %s | %.2f | %d/8 | %.2f | %d/8 | %.2f | %.2f | %.2f | %.1f | %d | %.2f | %.1f | %s | %.0f%% | %s | %.0f%% |".format(
                        s.point.label(), s.selectMedian, s.selectPositive, s.validateMedian, s.validatePositive,
                        s.bullMedian, s.bearMedian, s.mddMedianDelta, s.worstMdd,
                        s.trades, s.exposure, s.winRate,
                        if (s.breakEvenWinRate.isFinite()) "%.1f".format(s.breakEvenWinRate) else "—",
                        s.priceGateShare * 100, s.feeSensitivity, s.plateauRatio * 100,
                    ),
                )
            }
            appendLine()
            appendLine("⚠️ bear 열은 **독립 증거가 아니다** — bear fixture(2026-01~08)는 yearly 구간(2025-09~2026-09) 안에 통째로 들어간다. 시간 독립 holdout 은 bull(2023-11~2024-06) 뿐이다.")
            appendLine()
        }

        if (nullSummary != null) {
            appendLine("## null 대조군 (무작위 진입)")
            appendLine()
            appendLine("seed ${nullSummary.seeds}개 × exit 그리드 ${nullSummary.gridSize} 좌표, 진입 확률 %.3f(= baseline 원시 신호 발생률, 근사). 진입은 `(seed, market, 봉)` 의 순수 해시라 exit config 를 바꿔도 진입 시점이 고정된다.".format(nullSummary.entryRate))
            appendLine()
            appendLine("| 변종 | 기준(baseline) | 1건 이상 통과한 seed | 평균 통과 수 | max-stat 95% | 13배 폭 환산 95% |")
            appendLine("|---|---|---|---|---|---|")
            appendLine("| **A (주 판정)** | 실제 라이브 `combined` | %.1f%% | %.2f | %.2f%%p | %.2f%%p |".format(nullSummary.vsLive.anyPassRate * 100, nullSummary.vsLive.meanPassCount, nullSummary.vsLive.maxStatQ95, nullSummary.vsLive.maxStatQ95Scaled))
            appendLine("| B (진단용) | 같은 무작위 진입 | %.1f%% | %.2f | %.2f%%p | %.2f%%p |".format(nullSummary.vsNoise.anyPassRate * 100, nullSummary.vsNoise.meanPassCount, nullSummary.vsNoise.maxStatQ95, nullSummary.vsNoise.maxStatQ95Scaled))
            appendLine()
            appendLine("- **판정에 쓰는 것은 A 뿐이다.** 통과 후보의 선택창 중앙 delta 가 A 의 13배 폭 환산 95% 분위를 넘지 못하면 발견으로 보고하지 않는다.")
            appendLine("- B 는 후보·기준이 모두 무작위라 기준 자체가 형편없고 변동이 커서 delta 분포가 부푼다 — 그 분위수를 실제 탐색의 임계로 쓰면 사과-오렌지 비교다. 게이트 스택이 순수 잡음 환경에서 어떻게 움직이는지 보여주는 진단으로만 읽는다.")
            appendLine()
        }

        appendLine("## 재현")
        appendLine()
        for ((k, v) in metadata) appendLine("- $k: $v")
    }

    private val GATE_MEANING = mapOf(
        "G1" to "선택창 paired delta 중앙 ≥ +2.0%p 이고 양수 마켓 ≥ 6/8",
        "G2" to "검증창 중앙 ≥ 0 이고 양수 마켓 ≥ 5/8 (기대 통과율 ≈50%)",
        "G3" to "이웃 좌표 ≥70% 가 G5+G1 통과 (단일 peak 배제 — 거래를 안 한 이웃은 세지 않는다)",
        "G4a" to "bull 국면(시간 독립) 중앙 ≥ −1.0%p",
        "G4b" to "bear 국면(구간 중복 — robustness) 중앙 ≥ −1.0%p",
        "G5" to "창별 거래수 ≥ 8, 0거래 마켓 ≤ 1",
        "G6" to "MDD 중앙 delta ≤ +2.0%p, 최악 MDD ≤ baseline×1.5",
        "G7" to "수수료 2배·4배에서도 G1 유지",
    )
}
