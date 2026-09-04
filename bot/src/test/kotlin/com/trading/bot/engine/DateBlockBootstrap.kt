package com.trading.bot.engine

import kotlin.random.Random

/**
 * 청산 **달력일**을 블록으로 삼는 복원추출.
 *
 * 마켓을 표본 단위로 쓰면 안 된다 — `yearly/` 8마켓의 일간 로그수익률 평균 상관은 0.796 이라 실효 독립 표본이
 * **1.22** 이고(`backtest/README.md`), 같은 날 여러 마켓이 함께 청산되면 그건 관측 여러 개가 아니라
 * **같은 베팅 하나**다. 그래서 재추출 단위를 날짜로 둔다.
 *
 * [CandidateAnatomyTest] 와 [ExitHourSweepTest] 가 같은 통계량을 내야 두 리포트를 나란히 놓을 수 있어 여기 하나로 둔다.
 */
internal object DateBlockBootstrap {

    const val RESAMPLES = 20_000

    /** 고정 seed — 리포트 수치가 실행마다 흔들리면 정정·인용이 불가능하다. */
    const val SEED = 20260905L

    private const val LOWER_TAIL = 0.05
    private const val CI_TAIL = 0.025

    data class Result(
        val mean: Double,
        val ciLow: Double,
        val ciHigh: Double,
        /** 5% 하한 — 이 값이 0 을 넘으면 "격차가 잡음으로 설명되지 않는다"의 한쪽 근거다. */
        val p05: Double,
        val pLeZero: Double,
    )

    /** @param byDate 날짜 → 그 날의 격차 기여(전 마켓 합). 합이 곧 관측 격차다. */
    fun of(byDate: Map<String, Double>): Result {
        val values = byDate.values.toDoubleArray()
        require(values.isNotEmpty()) { "재추출할 날짜가 없다" }
        val rng = Random(SEED)
        val sums = DoubleArray(RESAMPLES)
        for (b in sums.indices) {
            var s = 0.0
            for (i in values.indices) s += values[rng.nextInt(values.size)]
            sums[b] = s
        }
        sums.sort()
        return Result(
            mean = sums.average(),
            ciLow = sums[(RESAMPLES * CI_TAIL).toInt()],
            ciHigh = sums[(RESAMPLES * (1 - CI_TAIL)).toInt()],
            p05 = sums[(RESAMPLES * LOWER_TAIL).toInt()],
            pLeZero = sums.count { it <= 0.0 }.toDouble() / RESAMPLES,
        )
    }
}
