package com.trading.bot.notification

/**
 * ERROR 알림 rate limit. 순수 로직(시간은 nowMs 파라미터 주입)이라 단위 테스트 가능.
 *
 * - dedup: 동일 fingerprint 는 [dedupCooldownMs] 동안 1회만 허용. 그 사이 발생은 카운트해
 *   다음 허용 시 "그동안 K회 더 발생" 으로, 그 전에 요약으로 나가면 요약 줄의 발생 횟수로 노출.
 * - 전역 상한: 최근 1분간 허용 건수가 [globalPerMinute] 이상이면 개별로 보내지 않고 요약용으로 보류해 [drainHeld] 가
 *   한 번에 돌려준다 — 버리면 한 번만 찍히는 ERROR 는 다시 오지 않아 영구 유실된다(#249). 요약으로 나간 fingerprint 도
 *   그 시각부터 쿨다운이다(요약과 개별로 두 번 보내지 않는다).
 * - 메모리: slot 은 [maxEntries] 건 — 마지막으로 허용·요약된 순서가 오래된 것부터 밀려난다. 보류는 [maxHeld] 건, 넘친 보류는
 *   로거별 발생 수만 센다.
 * - logback appender lock 안에서 불린다 — 여기서 로깅·블로킹하지 않고, [drainHeld] 는 사본을 돌려준다.
 */
class ErrorAlertRateLimiter(
    private val dedupCooldownMs: Long = 300_000,
    private val globalPerMinute: Int = 5,
    private val maxEntries: Int = 500,
    private val maxHeld: Int = 100,
) {
    /** [scheduleDigest] — 보류가 생겼는데 아직 요약 예약이 없다. 호출자가 [drainHeld] 실행을 예약한다. */
    data class Decision(val allow: Boolean, val suppressedSince: Int, val scheduleDigest: Boolean = false)

    /** [occurrences] — 직전 전달 이후 발생 횟수(보류 전 쿨다운 억제분 포함). */
    data class HeldAlert(val logger: String, val summary: String, val occurrences: Int)
    data class Digest(val alerts: List<HeldAlert>, val overflowByLogger: Map<String, Int>)

    private class Slot(val lastAllowedAt: Long, var suppressed: Int)
    private class Held(val logger: String, val summary: String, var occurrences: Int)

    // 메모리 bound: 삽입순(FIFO) size cap 으로 가장 오래된 entry 부터 evict, 무한 증가 차단.
    private val slots = object : LinkedHashMap<String, Slot>(16, 0.75f) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slot>): Boolean = size > maxEntries
    }
    private val recentAllows = ArrayDeque<Long>()
    private val held = LinkedHashMap<String, Held>()
    private val overflowByLogger = LinkedHashMap<String, Int>()
    private var digestPending = false

    /**
     * [summary] 는 보류할 때만 부른다 — 폭주 중 모든 ERROR 를 마스킹하지 않게. 기본값을 두지 않는다: 요약 줄은 Discord 로
     * 나가므로 마스킹한 문자열이어야 하고, 원문이 든 fingerprint 로 대신하면 비밀이 샌다.
     */
    @Synchronized
    fun decide(fingerprint: String, nowMs: Long, logger: String, summary: () -> String): Decision {
        while (recentAllows.isNotEmpty() && nowMs - recentAllows.first() >= 60_000L) {
            recentAllows.removeFirst()
        }
        // 요약에 실릴 알림 — 창이 풀렸다고 개별로 보내면 같은 알림이 두 번 나간다.
        held[fingerprint]?.let {
            it.occurrences++
            return Decision(allow = false, suppressedSince = 0, scheduleDigest = requestDigest())
        }
        val slot = slots[fingerprint]
        if (slot != null && nowMs - slot.lastAllowedAt < dedupCooldownMs) {
            slot.suppressed++
            return Decision(allow = false, suppressedSince = 0)
        }
        if (recentAllows.size >= globalPerMinute) return hold(fingerprint, slot, logger, summary)
        // remove 뒤 put — 제자리 put 은 삽입순을 그대로 둬, 방금 허용한 fingerprint 가 가장 먼저 밀려나 쿨다운을 잃는다.
        slots.remove(fingerprint)
        slots[fingerprint] = Slot(nowMs, 0)
        recentAllows.addLast(nowMs)
        return Decision(allow = true, suppressedSince = slot?.suppressed ?: 0)
    }

    private fun hold(fingerprint: String, slot: Slot?, logger: String, summary: () -> String): Decision {
        // 직전 전달 뒤 쿨다운 억제분을 요약으로 옮긴다 — slot 에 남기면 다음 개별 알림이 같은 건수를 또 싣는다.
        val occurrences = 1 + (slot?.suppressed ?: 0)
        slot?.suppressed = 0
        if (held.size < maxHeld) {
            held[fingerprint] = Held(logger, summary(), occurrences)
        } else {
            overflowByLogger.merge(logger, occurrences, Int::plus)
        }
        return Decision(allow = false, suppressedSince = 0, scheduleDigest = requestDigest())
    }

    private fun requestDigest(): Boolean {
        if (digestPending) return false
        digestPending = true
        return true
    }

    /** 보류를 비우고 사본을 돌려준다(없으면 null). 실린 fingerprint 는 지금부터 쿨다운이다. */
    @Synchronized
    fun drainHeld(nowMs: Long): Digest? {
        digestPending = false
        if (held.isEmpty() && overflowByLogger.isEmpty()) return null
        val alerts = held.map { (fingerprint, h) ->
            // remove 뒤 put — put 만 하면 삽입순 제자리라 곧바로 밀려나 쿨다운을 잃고, 같은 알림이 개별로 또 나간다.
            slots.remove(fingerprint)
            slots[fingerprint] = Slot(nowMs, 0)
            HeldAlert(h.logger, h.summary, h.occurrences)
        }
        val digest = Digest(alerts, LinkedHashMap(overflowByLogger))
        held.clear()
        overflowByLogger.clear()
        return digest
    }

    /** 요약 예약이 거부됐다 — 다음 보류가 다시 예약을 요청하게 한다. */
    @Synchronized
    fun digestNotScheduled() {
        digestPending = false
    }
}
