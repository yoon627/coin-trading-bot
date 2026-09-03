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

    data class NullSummary(
        val seeds: Int,
        val gridSize: Int,
        val anyPassRate: Double,
        val meanPassCount: Double,
        val maxStatQ95: Double,
        val maxStatQ99: Double,
        val entryRate: Double,
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
                    "| ${s.point.label()} | %.2f | %d/8 | %.2f | %d/8 | %.2f | %.2f | %.2f | %.1f | %d | %.2f | %.1f | %.1f | %.0f%% | %s | %.0f%% |".format(
                        s.selectMedian, s.selectPositive, s.validateMedian, s.validatePositive,
                        s.bullMedian, s.bearMedian, s.mddMedianDelta, s.worstMdd,
                        s.trades, s.exposure, s.winRate, s.breakEvenWinRate, s.priceGateShare * 100,
                        s.feeSensitivity, s.plateauRatio * 100,
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
            appendLine("- seed ${nullSummary.seeds}개 × coarse grid ${nullSummary.gridSize} 좌표, 진입 확률 %.3f(= baseline 원시 신호 발생률, 근사).".format(nullSummary.entryRate))
            appendLine("- seed 별 \"1건 이상 통과\" 비율 = %.1f%% (≈FWER), 평균 통과 수 = %.2f.".format(nullSummary.anyPassRate * 100, nullSummary.meanPassCount))
            appendLine("- max-statistic(seed 별 최고 선택창 중앙 delta) 95%% 분위 = %.2f%%p, 99%% 분위 = %.2f%%p.".format(nullSummary.maxStatQ95, nullSummary.maxStatQ99))
            appendLine("- **판정 규칙**: 실제 통과 후보의 선택창 중앙 delta 가 위 95%% 분위를 넘지 못하면 발견으로 보고하지 않는다.")
            appendLine()
        }

        appendLine("## 재현")
        appendLine()
        for ((k, v) in metadata) appendLine("- $k: $v")
    }

    private val GATE_MEANING = mapOf(
        "G1" to "선택창 paired delta 중앙 ≥ +2.0%p 이고 양수 마켓 ≥ 6/8",
        "G2" to "검증창 중앙 ≥ 0 이고 양수 마켓 ≥ 5/8 (기대 통과율 ≈50%)",
        "G3" to "이웃 좌표 ≥70% 가 G1 통과 (단일 peak 배제)",
        "G4a" to "bull 국면(시간 독립) 중앙 ≥ −1.0%p",
        "G4b" to "bear 국면(구간 중복 — robustness) 중앙 ≥ −1.0%p",
        "G5" to "창별 거래수 ≥ 8, 0거래 마켓 ≤ 1",
        "G6" to "MDD 중앙 delta ≤ +2.0%p, 최악 MDD ≤ baseline×1.5",
        "G7" to "수수료 2배·4배에서도 G1 유지",
    )
}
