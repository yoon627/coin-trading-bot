package com.trading.bot.notification

/**
 * 같은 fingerprint 는 쿨다운 동안 한 번만 보내고, 분당 상한을 넘은 알림은 버리지 않고 요약용으로 보류한다.
 * 한 번만 찍히는 ERROR 는 버리면 다시 오지 않기 때문이다.
 * logback appender lock 안에서 불리므로 여기서 로깅하거나 블로킹하지 않는다.
 */
class ErrorAlertRateLimiter(
    private val dedupCooldownMs: Long = 300_000,
    private val globalPerMinute: Int = 5,
    private val maxEntries: Int = 500,
    private val maxHeld: Int = 100,
) {
    /** [scheduleDigest] 가 true 면 호출자가 [drainHeld] 실행을 예약한다. */
    data class Decision(val allow: Boolean, val suppressedSince: Int, val scheduleDigest: Boolean = false)

    /** [occurrences] 는 직전 전달 이후 발생 횟수다. */
    data class HeldAlert(val logger: String, val summary: String, val occurrences: Int)
    data class Digest(val alerts: List<HeldAlert>, val overflowByLogger: Map<String, Int>)

    private class Slot(val lastAllowedAt: Long, var suppressed: Int)
    private class Held(val logger: String, val summary: String, var occurrences: Int)

    private val slots = object : LinkedHashMap<String, Slot>(16, 0.75f) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slot>): Boolean = size > maxEntries
    }
    private val recentAllows = ArrayDeque<Long>()
    private val held = LinkedHashMap<String, Held>()
    private val overflowByLogger = LinkedHashMap<String, Int>()
    private var digestPending = false

    /**
     * [summary] 는 보류할 때만 부른다. 요약 줄은 Discord 로 나가므로 마스킹한 문자열이어야 하고,
     * 원문이 든 fingerprint 로 대신하면 비밀이 샌다.
     */
    @Synchronized
    fun decide(fingerprint: String, nowMs: Long, logger: String, summary: () -> String): Decision {
        while (recentAllows.isNotEmpty() && nowMs - recentAllows.first() >= 60_000L) {
            recentAllows.removeFirst()
        }
        // 이미 요약에 실릴 알림을 개별로 보내면 두 번 나간다.
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
        // 제자리 put 은 삽입순을 그대로 둬서 방금 허용한 항목이 먼저 밀려나므로 지우고 다시 넣는다.
        slots.remove(fingerprint)
        slots[fingerprint] = Slot(nowMs, 0)
        recentAllows.addLast(nowMs)
        return Decision(allow = true, suppressedSince = slot?.suppressed ?: 0)
    }

    private fun hold(fingerprint: String, slot: Slot?, logger: String, summary: () -> String): Decision {
        // 억제분을 slot 에 남기면 다음 개별 알림이 같은 건수를 또 싣는다.
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

    /** 보류를 비우고 사본을 돌려준다. 실린 fingerprint 는 지금부터 쿨다운이다. */
    @Synchronized
    fun drainHeld(nowMs: Long): Digest? {
        digestPending = false
        if (held.isEmpty() && overflowByLogger.isEmpty()) return null
        val alerts = held.map { (fingerprint, h) ->
            slots.remove(fingerprint)
            slots[fingerprint] = Slot(nowMs, 0)
            HeldAlert(h.logger, h.summary, h.occurrences)
        }
        val digest = Digest(alerts, LinkedHashMap(overflowByLogger))
        held.clear()
        overflowByLogger.clear()
        return digest
    }

    /** 요약 예약이 거부됐을 때 부른다. 다음 보류가 다시 예약을 요청한다. */
    @Synchronized
    fun digestNotScheduled() {
        digestPending = false
    }
}
