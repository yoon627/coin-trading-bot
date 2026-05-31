package com.trading.bot.notification

/**
 * ERROR 알림 rate limit. 순수 로직(시간은 nowMs 파라미터 주입)이라 단위 테스트 가능.
 *
 * - dedup: 동일 fingerprint 는 [dedupCooldownMs] 동안 1회만 허용. 그 사이 발생은 카운트해
 *   다음 허용 시 "그동안 K회 더 발생" 요약으로 노출.
 * - 전역 상한: 최근 1분간 허용 건수가 [globalPerMinute] 이상이면 억제(폭주 방지).
 * - 메모리: 내부 상태는 [maxEntries] LRU 로 bound (무한 증가 차단).
 */
class ErrorAlertRateLimiter(
    private val dedupCooldownMs: Long = 300_000,
    private val globalPerMinute: Int = 5,
    private val maxEntries: Int = 500,
) {
    data class Decision(val allow: Boolean, val suppressedSince: Int)

    // lastAllowedAt == null: 아직 한 번도 허용된 적 없음(global 억제만 받은 신규 fp).
    // sentinel 값을 쓰면 nowMs - sentinel 산술 오버플로우로 쿨다운 오판이 생기므로 nullable 로 명시.
    private class Slot(var lastAllowedAt: Long?, var suppressed: Int)

    // 메모리 bound: 삽입순(FIFO) size cap 으로 가장 오래된 entry 부터 evict, 무한 증가 차단.
    private val slots = object : LinkedHashMap<String, Slot>(16, 0.75f) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slot>): Boolean = size > maxEntries
    }
    private val recentAllows = ArrayDeque<Long>()

    @Synchronized
    fun decide(fingerprint: String, nowMs: Long): Decision {
        while (recentAllows.isNotEmpty() && nowMs - recentAllows.first() >= 60_000L) {
            recentAllows.removeFirst()
        }
        val slot = slots[fingerprint]
        val last = slot?.lastAllowedAt
        val inCooldown = last != null && nowMs - last < dedupCooldownMs
        val globalExceeded = recentAllows.size >= globalPerMinute

        if (inCooldown || globalExceeded) {
            if (slot != null) slot.suppressed++ else slots[fingerprint] = Slot(null, 1)
            return Decision(allow = false, suppressedSince = 0)
        }
        val suppressed = slot?.suppressed ?: 0
        slots[fingerprint] = Slot(nowMs, 0)
        recentAllows.addLast(nowMs)
        return Decision(allow = true, suppressedSince = suppressed)
    }
}
