package com.trading.bot.engine

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 진입일 페어링 격차의 **창별 이동블록 재추출 + studentized maxT**.
 *
 * [DateBlockBootstrap] 과 다른 점 셋 — (1) frame 이 청산일 합집합이 아니라 **워밍업 이후 전 거래일**(0 기여일 포함)이고,
 * (2) 길이 1 이 아니라 **창 경계를 넘지 않는 길이 [BLOCK] 의 겹치는 블록**을 뽑으며, (3) 관측 격차의 꼬리비율이 아니라
 * **재추출 평균에서 중심화한 studentized 통계량의 family 최대값** 으로 동시 판정한다(FWER). 같은 seed 의 draw 를 모든 셀·family 가 공유한다.
 *
 * 사전고정: plan `2026-09-09-tp-sl-grid` `# Acceptance` 4~6.
 */
internal object PairedMaxTBootstrap {

    const val BLOCK = 5
    const val RESAMPLES = 20_000
    const val SEED = 20260909L
    const val ALPHA = 0.05

    /** 시간순 창 목록 — 각 창은 거래일 키(시간순). 키는 frame 전체에서 유일해야 한다(`window/date` 권장). */
    class Frame(val windows: List<List<String>>) {
        val size: Int = windows.sumOf { it.size }
        private val offsets: IntArray = IntArray(windows.size).also { o ->
            var acc = 0
            windows.forEachIndexed { i, w -> o[i] = acc; acc += w.size }
        }
        private val index: Map<String, Int> = buildMap {
            windows.forEachIndexed { i, w -> w.forEachIndexed { j, key -> require(put(key, offsets[i] + j) == null) { "frame 키 중복: $key" } } }
        }

        fun indexOf(key: String): Int = index.getValue(key)

        /** 창 [w] 에 속한 전역 인덱스 범위. */
        fun rangeOf(w: Int): IntRange = offsets[w] until offsets[w] + windows[w].size

        /** [keys] 에 [values] 를 더한 frame 정렬 배열. 없는 키는 [require] 로 막는다 — 조용히 버리면 격차가 사라진다. */
        fun align(pairs: Map<String, Double>): DoubleArray {
            val out = DoubleArray(size)
            for ((k, v) in pairs) out[indexOf(k)] += v
            return out
        }

        /**
         * 창별 stratified 이동블록 draw 하나. 창 w 의 n 일에서 길이 [block] 의 겹치는 블록 시작점을 `[0, n−block]` 에서 균등하게
         * ⌈n/block⌉ 개 뽑아 이어붙이고 n 으로 절단한다(n < block 이면 창 전체 1블록). 비순환, 창 경계를 넘지 않는다.
         */
        fun draw(rng: Random, block: Int): IntArray {
            val out = IntArray(size)
            var pos = 0
            for (w in windows.indices) {
                val n = windows[w].size
                val base = offsets[w]
                if (n <= block) {
                    for (j in 0 until n) out[pos++] = base + j
                    continue
                }
                val blocks = ceil(n.toDouble() / block).toInt()
                var taken = 0
                repeat(blocks) {
                    val start = rng.nextInt(n - block + 1)
                    var j = 0
                    while (j < block && taken < n) {
                        out[pos++] = base + start + j
                        taken++; j++
                    }
                }
            }
            return out
        }
    }

    /** 셀 하나의 판정값. [pass] 는 family 동시 판정(`t > q`), [marginalP] 는 보고용 한계 단측 p — 판정에 쓰지 않는다. */
    data class Cell(
        val g: Double,
        val se: Double,
        val t: Double,
        val lowerBound: Double,
        val marginalP: Double,
        val pass: Boolean,
        /** 재추출 합의 2.5 / 97.5 백분위(관측 격차 + 중심화 편차) — 단일 셀 구간 보고용. */
        val ciLow: Double,
        val ciHigh: Double,
    )

    data class Family(val q: Double, val cells: List<Cell>)

    /**
     * 모든 [contributions] 배열(frame 정렬)에 **같은 draw** 를 적용해 재추출 합 행렬을 만든다. family 가 여럿이어도 한 번만 부르면
     * draw 공유가 구조로 보장된다. 반환 `[array][b]`.
     */
    fun resampleSums(
        frame: Frame,
        contributions: List<DoubleArray>,
        resamples: Int = RESAMPLES,
        block: Int = BLOCK,
        seed: Long = SEED,
    ): Array<DoubleArray> {
        contributions.forEach { require(it.size == frame.size) { "기여 배열 길이 ${it.size} ≠ frame ${frame.size}" } }
        val rng = Random(seed)
        val sums = Array(contributions.size) { DoubleArray(resamples) }
        for (b in 0 until resamples) {
            val idx = frame.draw(rng, block)
            for (c in contributions.indices) {
                val arr = contributions[c]
                var s = 0.0
                for (i in idx) s += arr[i]
                sums[c][b] = s
            }
        }
        return sums
    }

    /**
     * 단일단계 maxT 동시 판정(Westfall–Young — se 는 재추출마다가 아니라 상수). [observed] 는 각 셀의 관측 격차 G_c, [sums] 는 [resampleSums] 의 해당 행.
     * 중심화 D = S − mean(S), se = sd(S), T^b = D/se, q = family 의 max_c T^b 의 (1−[alpha]) 분위. 통과 = G/se > q.
     * se = 0(기준과 완전히 같은 행동)인 셀은 max 에서 빼고 미통과로 둔다 — NaN 이 max 로 번지면 family 전체가 조용히 미통과가 된다.
     */
    fun maxT(observed: DoubleArray, sums: Array<DoubleArray>, alpha: Double = ALPHA): Family {
        require(observed.size == sums.size && sums.isNotEmpty())
        val b = sums[0].size
        val mean = DoubleArray(sums.size) { c -> sums[c].average() }
        val se = DoubleArray(sums.size) { c ->
            val m = mean[c]
            sqrt(sums[c].sumOf { (it - m) * (it - m) } / (b - 1))
        }
        val maxT = DoubleArray(b) { i ->
            var m = Double.NEGATIVE_INFINITY
            for (c in sums.indices) if (se[c] > 0) m = max(m, (sums[c][i] - mean[c]) / se[c])
            m
        }
        maxT.sort()
        val q = maxT[(b * (1 - alpha)).toInt()]
        val cells = sums.indices.map { c ->
            val g = observed[c]
            val degenerate = se[c] == 0.0
            val t = if (degenerate) Double.NaN else g / se[c]
            val centered = DoubleArray(b) { i -> sums[c][i] - mean[c] }
            val exceed = centered.count { it >= g }
            centered.sort()
            Cell(
                g = g, se = se[c], t = t, lowerBound = if (degenerate) Double.NaN else g - q * se[c],
                marginalP = (exceed + 1).toDouble() / (b + 1),
                pass = !degenerate && t > q,
                ciLow = g + centered[(b * 0.025).toInt()],
                ciHigh = g + centered[(b * 0.975).toInt()],
            )
        }
        return Family(q, cells)
    }

    /** 단일 배열의 관측값·구간만 필요할 때(bhPos/bhNeg 등) — [maxT] 의 1셀 family 와 같은 계산이다. */
    fun interval(observed: Double, sums: DoubleArray): Cell =
        maxT(doubleArrayOf(observed), arrayOf(sums)).cells.single()

    /** Spearman ρ — 동률은 평균 순위. */
    fun spearman(x: DoubleArray, y: DoubleArray): Double {
        require(x.size == y.size && x.size >= 2)
        val rx = ranks(x)
        val ry = ranks(y)
        val mx = rx.average()
        val my = ry.average()
        var num = 0.0; var dx = 0.0; var dy = 0.0
        for (i in x.indices) {
            num += (rx[i] - mx) * (ry[i] - my)
            dx += (rx[i] - mx) * (rx[i] - mx)
            dy += (ry[i] - my) * (ry[i] - my)
        }
        return if (dx == 0.0 || dy == 0.0) 0.0 else num / sqrt(dx * dy)
    }

    private fun ranks(v: DoubleArray): DoubleArray {
        val order = v.indices.sortedBy { v[it] }
        val r = DoubleArray(v.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && v[order[j + 1]] == v[order[i]]) j++
            val avg = (i + j) / 2.0 + 1
            for (k in i..j) r[order[k]] = avg
            i = j + 1
        }
        return r
    }
}
