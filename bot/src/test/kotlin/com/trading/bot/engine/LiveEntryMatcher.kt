package com.trading.bot.engine

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * 라이브 매수와 계기 진입의 (마켓, UTC 거래일) 매칭과 사전고정 통계 — [LiveEntryResolutionTest] 의 순수 부분.
 * 파일·네트워크·계기 실행이 없어 합성 데이터로 단위테스트한다([LiveEntryMatcherTest]).
 *
 * ## 사전고정 판정 규칙 (#190 잔여 — 결과를 보기 전에 커밋)
 * - 표본 = 라이브 매수 중 **제외되지 않은** (마켓, 일). 제외 = 어느 해상도든 그날 봉이 없거나 첫 봉이 `T00:00:00` 이 아닌 (마켓, 일) —
 *   첫 봉이 빠지면 당일 시가·돌파선이 달라져 그날은 해상도 비교가 아니다. 제외 건수는 리포트에 낸다.
 * - 해상도별 precision·recall·F1. recall 은 격자가 고울수록 구조적으로 오르므로(후보 진입이 는다) 단독으로 쓰지 않는다.
 * - 기록가격 오차 = (라이브 price − 계기 entryPrice)/계기 × 100. 크기는 **공통 매칭 표본**(세 해상도 모두 매칭된 라이브 매수)의 `median(|Δ|)` —
 *   해상도마다 표본이 다르면 정확도와 표본 구성이 섞이고, `|median|` 은 부호 상쇄를 잰다. 부호 있는 중앙값은 방향 진단용.
 * - 시각 오차 = 라이브 created_at − 계기 진입 봉 시작(분). `combined` 는 시가 ≤ 돌파선 봉을 거부해 계기 진입 봉은 항상 돌파 뒤에 열리므로
 *   라이브(돌파 tick)가 앞선다 — 방향 진단용, 판정에 넣지 않는다("봉 안 비율"은 240분에 48배 유리).
 * - **가장 가까운 해상도** = F1 최대 **이고** 공통 표본 `median(|Δ|)` 최소. F1 1위가 2위보다 [MIN_F1_LEAD] 미만 앞서면 동률 → 유보.
 *   공통 표본 < [MIN_COMMON] 이면 가격 축 유보 → 전체 유보. 두 축이 갈리면 유보(둘 다 보고).
 *   가격 축 1위가 2위보다 [MIN_FILL_LEAD] 미만 앞서면 동률 → 유보 (리뷰 지적으로 결과 후 추가 — 첫 실행 0.212 vs 0.366 에는 미발현, 결론 불변).
 * - 결과 전 예측: F1 최대는 240분이 아니다(15 또는 5). `median(|Δ|)` 최소는 5분. 부호 중앙값은 240분에서 음(계기 240 체결가가 높다).
 */
internal object LiveEntryMatcher {

    const val MIN_F1_LEAD = 0.05
    const val MIN_COMMON = 20
    const val MIN_FILL_LEAD = 0.02

    data class LiveBuy(val id: Long, val market: String, val createdUtc: String, val price: Double) {
        /** UTC 날짜 = 거래일(00:00 UTC = 09:00 KST 경계). KST 로 바꾼 뒤 날짜를 취하면 15:00 UTC 이후 매수가 하루 밀린다. */
        val day: String get() = createdUtc.substring(0, 10)
        val key: Key get() = Key(market, day)
    }

    /** 계기 진입 하나. [barUtc]·[exitBarUtc] 는 봉 시작 시각(`yyyy-MM-ddTHH:mm:ss`) — 보유 여부는 일이 아니라 이 시각으로 판정한다(h1 청산은 청산일 첫 봉이다). */
    data class ArmEntry(val market: String, val day: String, val barUtc: String, val price: Double, val exitBarUtc: String) {
        val key: Key get() = Key(market, day)
    }

    data class Key(val market: String, val day: String)

    data class Match(val live: LiveBuy, val arm: ArmEntry) {
        val fillPct: Double get() = (live.price - arm.price) / arm.price * 100.0
        val minutes: Long get() = Duration.between(LocalDateTime.parse(arm.barUtc), LocalDateTime.parse(live.createdUtc)).toMinutes()
    }

    class Result(
        val unit: Int, val armCount: Int, val matched: List<Match>, val liveOnly: List<LiveBuy>, val armOnly: List<ArmEntry>, val liveSize: Int,
        /** 같은 (마켓, 일) 의 둘째 이후 라이브 매수 — [liveOnly] 에도 들어 있다. 사전고정: 실패시키지 않고 건수로 보고. */
        val duplicateSameDay: List<LiveBuy>,
    ) {
        val recall: Double get() = if (liveSize == 0) 0.0 else matched.size.toDouble() / liveSize
        val precision: Double get() = if (armCount == 0) 0.0 else matched.size.toDouble() / armCount
        val f1: Double get() = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        val signedMedianFill: Double get() = median(matched.map { it.fillPct })
        val medianMinutes: Double get() = median(matched.map { it.minutes.toDouble() })
    }

    /** 라이브 매수 하나에 계기 진입 하나. 같은 (마켓, 일) 라이브 매수가 둘이면(09:00 리셋 직후 재매수 등) 첫 건만 매칭 후보다. */
    fun match(unit: Int, liveBuys: List<LiveBuy>, arm: List<ArmEntry>): Result {
        val armByKey = arm.associateBy { it.key }
        require(armByKey.size == arm.size) { "$unit m: 계기 (마켓, 진입일) 이 유일하지 않다" }
        val matched = ArrayList<Match>(); val liveOnly = ArrayList<LiveBuy>(); val dup = ArrayList<LiveBuy>(); val seen = HashSet<Key>()
        for (b in liveBuys.sortedWith(compareBy({ it.createdUtc }, { it.id }))) {
            if (!seen.add(b.key)) { dup += b; liveOnly += b; continue }
            val a = armByKey[b.key]
            if (a == null) liveOnly += b else matched += Match(b, a)
        }
        val liveKeys = liveBuys.map { it.key }.toSet()
        return Result(unit, arm.size, matched, liveOnly, arm.filter { it.key !in liveKeys }, liveBuys.size, dup)
    }

    /** 세 해상도 모두 매칭된 라이브 매수 id — 가격 축의 공통 표본. */
    fun commonIds(results: Collection<Result>): Set<Long> =
        results.map { r -> r.matched.map { it.live.id }.toSet() }.reduceOrNull { a, b -> a intersect b } ?: emptySet()

    fun medianAbsFill(r: Result, ids: Set<Long>): Double = median(r.matched.filter { it.live.id in ids }.map { abs(it.fillPct) })

    data class Verdict(val f1Best: Int?, val fillBest: Int?, val commonN: Int, val closest: Int?, val note: String)

    fun verdict(results: List<Result>): Verdict {
        val byF1 = results.sortedByDescending { it.f1 }
        val f1Best = byF1.firstOrNull()?.takeIf { byF1.size < 2 || it.f1 - byF1[1].f1 >= MIN_F1_LEAD }?.unit
        val ids = commonIds(results)
        val byFill = results.sortedBy { medianAbsFill(it, ids) }
        val fillTie = byFill.size >= 2 && medianAbsFill(byFill[1], ids) - medianAbsFill(byFill[0], ids) < MIN_FILL_LEAD
        val fillBest = if (ids.size < MIN_COMMON || fillTie) null else byFill.firstOrNull()?.unit
        val closest = if (f1Best != null && f1Best == fillBest) f1Best else null
        val note = when {
            closest != null -> "F1 과 공통 표본 median(|Δ|) 이 일치 — ${closest}분봉이 라이브에 가장 가깝다"
            f1Best == null && fillBest == null -> "유보 — F1 동률(1위−2위 < $MIN_F1_LEAD) 이고 가격 축도 유보(공통 표본 ${ids.size} < $MIN_COMMON 또는 동률 < $MIN_FILL_LEAD)"
            f1Best == null -> "유보 — F1 동률(1위−2위 < $MIN_F1_LEAD)"
            fillBest == null -> if (ids.size < MIN_COMMON) "유보 — 공통 표본 ${ids.size} < $MIN_COMMON" else "유보 — median(|Δ|) 동률(1위−2위 < $MIN_FILL_LEAD)"
            else -> "유보 — F1 최대 ${f1Best}분 ≠ median(|Δ|) 최소 ${fillBest}분"
        }
        return Verdict(f1Best, fillBest, ids.size, closest, note)
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
