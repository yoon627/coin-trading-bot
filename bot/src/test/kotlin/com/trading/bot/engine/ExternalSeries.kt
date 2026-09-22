package com.trading.bot.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate
import java.util.TreeMap

/**
 * 외부 일별 스칼라 시계열(`backtest/external/<name>.json`, `scripts/collect_external_series.py` 산출) — plan `2026-09-10-external-regime-gate`.
 *
 * 날짜 키는 **값을 만든 데이터의 UTC 날짜**다. 거래일 D(KST 09:00 경계 = UTC 00:00 D)의 게이트는 D−1 값을 쓴다([lagged]) —
 * D 의 값은 D 가 끝나야 확정되므로 D 안에서 쓰면 look-ahead 다. 롤링 통계([median], [sma])도 같은 이유로 D−1 까지만 본다.
 */
class ExternalSeries private constructor(val name: String, private val byDate: TreeMap<LocalDate, Double>) {

    val dates: List<LocalDate> get() = byDate.keys.toList()
    val size: Int get() = byDate.size

    /** 거래일 [day] 의 게이트 값 = 데이터 날짜 D−1. 결측이면 최대 [maxCarry] 일 더 거슬러 이월하고, 그래도 없으면 null. */
    fun lagged(day: LocalDate, maxCarry: Int = MAX_CARRY): Double? {
        var d = day.minusDays(1)
        repeat(maxCarry + 1) {
            byDate[d]?.let { return it }
            d = d.minusDays(1)
        }
        return null
    }

    /** [lagged] 와 같은 규약으로 D−1−[lag] 의 값. */
    fun laggedBy(day: LocalDate, lag: Int, maxCarry: Int = MAX_CARRY): Double? = lagged(day.minusDays(lag.toLong()), maxCarry)

    /** D−1 을 **제외한** 그 이전 [n] 일(데이터 날짜 D−1−n ~ D−2) 값들의 중앙값. 값이 절반 미만이면 null. */
    fun median(day: LocalDate, n: Int): Double? {
        val v = window(day, n) ?: return null
        val s = v.sorted()
        return (s[s.size / 2] + s[(s.size - 1) / 2]) / 2
    }

    fun sma(day: LocalDate, n: Int): Double? = window(day, n)?.average()

    private fun window(day: LocalDate, n: Int): List<Double>? {
        val from = day.minusDays(1L + n)
        val to = day.minusDays(2)
        val v = byDate.subMap(from, true, to, true).values.toList()
        return if (v.size * 2 < n) null else v
    }

    /**
     * 위상 이동 대조군 — **달력** 위에서 [shiftDays] 만큼 순환 이동한다: 새 값(d) = 원본 값(wrap(d + shift)), wrap 은 첫 날짜~마지막 날짜 구간 순환.
     * 행 인덱스가 아니라 달력으로 옮겨야 결측이 있는 시계열(fng 1일)도 다른 시계열과 **같은 칸**으로 움직인다(사전고정 4·8d).
     * 값 다중집합·날짜 개수는 보존되고, 결측 칸만 함께 이동한다. 상태 없는 순수 함수라 셀·seed 가 바뀌어도 대조군 정의가 흔들리지 않는다.
     */
    fun shifted(shiftDays: Int): ExternalSeries {
        val first = byDate.firstKey()
        val span = java.time.temporal.ChronoUnit.DAYS.between(first, byDate.lastKey()) + 1
        val out = TreeMap<LocalDate, Double>()
        for (i in 0 until span) {
            val d = first.plusDays(i)
            val src = first.plusDays(Math.floorMod(i + shiftDays, span))
            byDate[src]?.let { out[d] = it }
        }
        return ExternalSeries("$name+$shiftDays", out)
    }

    /** 값 다중집합(정렬) — 위상 이동 불변식 검사용. */
    fun sortedValues(): List<Double> = byDate.values.sorted()
    val firstDate: LocalDate get() = byDate.firstKey()
    val lastDate: LocalDate get() = byDate.lastKey()

    companion object {
        const val MAX_CARRY = 3
        private val mapper = jacksonObjectMapper()

        fun load(name: String): ExternalSeries {
            val stream = ExternalSeries::class.java.getResourceAsStream("/backtest/external/$name.json")
                ?: error("external fixture 부재: $name — scripts/collect_external_series.py --write")
            val root = stream.use { mapper.readTree(it) }
            val map = TreeMap<LocalDate, Double>()
            for (row in root["data"]) map[LocalDate.parse(row["date"].asText())] = row["value"].asDouble()
            require(map.isNotEmpty()) { "external fixture 비어 있음: $name" }
            return ExternalSeries(name, map)
        }
    }
}

/** 다섯 시계열 묶음과 13셀 게이트(사전고정 2). 결측(null)은 배관 오류라 예외로 올린다 — 조용히 false 가 되면 차단율에 섞인다. */
class ExternalRegime(
    val kimp: ExternalSeries,
    val funding: ExternalSeries,
    val fng: ExternalSeries,
    val ethbtc: ExternalSeries,
    val taker: ExternalSeries,
) {
    enum class Cell(val label: String) {
        KIMP_LOW("KIMP_LOW"), KIMP_HIGH("KIMP_HIGH"), KIMP_RISING("KIMP_RISING"),
        FUND_NEG("FUND_NEG"), FUND_LOW("FUND_LOW"), FUND_HIGH("FUND_HIGH"),
        FNG_FEAR("FNG_FEAR"), FNG_GREED("FNG_GREED"),
        ETHBTC_UP("ETHBTC_UP"), ETHBTC_DOWN("ETHBTC_DOWN"),
        TAKER_HIGH("TAKER_HIGH"), TAKER_LOW("TAKER_LOW"),
        COMBO("COMBO"),
    }

    fun allows(cell: Cell, day: LocalDate): Boolean = when (cell) {
        Cell.KIMP_LOW -> v(kimp, day) <= med(kimp, day)
        Cell.KIMP_HIGH -> v(kimp, day) > med(kimp, day)
        Cell.KIMP_RISING -> v(kimp, day) > req(kimp.laggedBy(day, 5), kimp, day)
        Cell.FUND_NEG -> v(funding, day) <= 0.0
        Cell.FUND_LOW -> v(funding, day) <= med(funding, day)
        Cell.FUND_HIGH -> v(funding, day) > med(funding, day)
        Cell.FNG_FEAR -> v(fng, day) <= FNG_FEAR_MAX
        Cell.FNG_GREED -> v(fng, day) >= FNG_GREED_MIN
        Cell.ETHBTC_UP -> v(ethbtc, day) > req(ethbtc.sma(day, SMA_DAYS), ethbtc, day)
        Cell.ETHBTC_DOWN -> v(ethbtc, day) <= req(ethbtc.sma(day, SMA_DAYS), ethbtc, day)
        Cell.TAKER_HIGH -> v(taker, day) > med(taker, day)
        Cell.TAKER_LOW -> v(taker, day) <= med(taker, day)
        Cell.COMBO -> allows(Cell.KIMP_LOW, day) && allows(Cell.FUND_LOW, day)
    }

    /** 모든 시계열이 [day] 에 값(이월 포함)과 롤링 통계를 가지는가 — 배관 단정 8b. */
    fun complete(day: LocalDate): Boolean = try { Cell.values().forEach { allows(it, day) }; true } catch (e: IllegalStateException) { false }

    fun all() = listOf(kimp, funding, fng, ethbtc, taker)

    fun shifted(shiftDays: Int) = ExternalRegime(kimp.shifted(shiftDays), funding.shifted(shiftDays), fng.shifted(shiftDays), ethbtc.shifted(shiftDays), taker.shifted(shiftDays))

    private fun v(s: ExternalSeries, day: LocalDate) = req(s.lagged(day), s, day)
    private fun med(s: ExternalSeries, day: LocalDate) = req(s.median(day, MEDIAN_DAYS), s, day)
    private fun req(x: Double?, s: ExternalSeries, day: LocalDate): Double = x ?: error("${s.name}: $day 에 값·롤링 통계 없음(이월 ${ExternalSeries.MAX_CARRY}일 초과) — 사전고정 8b")

    companion object {
        const val MEDIAN_DAYS = 90
        const val SMA_DAYS = 20
        const val FNG_FEAR_MAX = 30.0
        const val FNG_GREED_MIN = 70.0
        fun load() = ExternalRegime(ExternalSeries.load("kimp_btc"), ExternalSeries.load("funding_btcusdt"), ExternalSeries.load("fng"), ExternalSeries.load("ethbtc"), ExternalSeries.load("taker_btcusdt"))
    }
}
