package com.trading.bot.kis.marketdata

import java.util.concurrent.ConcurrentHashMap

/**
 * 엔진의 REST 폴백 전용 TTL 캐시 + 실패 backoff.
 *
 * [com.trading.bot.marketdata.MarketDataStore] 를 쓰지 않는 이유: store 의 `addCandle` 은 put+size+trim 이
 * 비원자적이라 단일 writer(@Scheduled 폴러)를 전제한다. 엔진까지 쓰면 writer 가 둘이 된다.
 *
 * TTL·backoff 는 단조시계 기준이다 — wall clock 은 NTP 보정으로 역행하면 만료된 값이 계속 fresh 로 보인다.
 * [nowNanos] 를 주입하면 시간 경과를 테스트에서 제어할 수 있다.
 */
class FallbackCache<T : Any>(
    private val ttlMs: Long,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private class Entry<T>(val value: T, val atNanos: Long)

    private class Failure(val count: Int, val retryAtNanos: Long)

    private val entries = ConcurrentHashMap<String, Entry<T>>()
    private val failures = ConcurrentHashMap<String, Failure>()

    /** TTL 이 지나지 않은 값만 돌려준다. */
    fun get(key: String): T? {
        val e = entries[key] ?: return null
        return if ((nowNanos() - e.atNanos) / NANOS_PER_MS < ttlMs) e.value else null
    }

    fun put(key: String, value: T) {
        entries[key] = Entry(value, nowNanos())
    }

    /** backoff 중이면 false — rate limit 을 실패 재시도로 더 악화시키지 않는다. */
    fun shouldAttempt(key: String): Boolean {
        val f = failures[key] ?: return true
        return nowNanos() - f.retryAtNanos >= 0
    }

    fun recordSuccess(key: String) {
        failures.remove(key)
    }

    fun recordFailure(key: String) {
        val count = (failures[key]?.count ?: 0) + 1
        val delayMs = minOf(BACKOFF_BASE_MS shl minOf(count - 1, BACKOFF_MAX_SHIFT), BACKOFF_CAP_MS)
        failures[key] = Failure(count, nowNanos() + delayMs * NANOS_PER_MS)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val BACKOFF_BASE_MS = 1_000L
        const val BACKOFF_CAP_MS = 60_000L
        const val BACKOFF_MAX_SHIFT = 6
    }
}
