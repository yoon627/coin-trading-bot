package com.trading.bot.marketdata

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * SSE 스트림의 연결 자리(전체·사용자당 상한). 스트림마다 따로 만든다 — 상한의 뜻이 스트림마다 다르다.
 * 자리는 본문을 읽기 시작할 때 [tryOpen] 으로 잡는다 — HEAD 처럼 본문을 읽지 않는 요청은 자리를 잡지 않는다.
 */
class StreamSlots(
    private val name: String,
    private val maxConnections: Int,
    private val maxPerUser: Int,
) {
    /** 열린 스트림 한 자리. 두 번 돌려줘도 한 번만 센다. */
    inner class Lease internal constructor(private val userId: Long) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (!released.compareAndSet(false, true)) return
            total.decrementAndGet()
            perUser.computeIfPresent(userId) { _, count -> (count - 1).takeIf { it > 0 } }
        }
    }

    private val total = AtomicInteger()
    private val perUser = ConcurrentHashMap<Long, Int>()
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastRefusalWarnAt = AtomicLong()

    @Volatile
    var closing = false

    /** 지금 이 사용자에게 자리가 있는가. 응답 전에 503 을 정하는 데만 쓴다. */
    fun hasRoom(userId: Long): Boolean {
        val room = !closing && total.get() < maxConnections && (perUser[userId] ?: 0) < maxPerUser
        if (!room) refused(userId)
        return room
    }

    /** 자리가 있으면 잡는다. 없으면(전체·사용자 상한, 종료 중) null. */
    fun tryOpen(userId: Long): Lease? {
        if (closing) return refused(userId)
        if (total.incrementAndGet() > maxConnections) {
            total.decrementAndGet()
            return refused(userId)
        }
        var admitted = false
        perUser.compute(userId) { _, count ->
            val next = (count ?: 0) + 1
            if (next > maxPerUser) count else next.also { admitted = true }
        }
        if (!admitted) {
            total.decrementAndGet()
            return refused(userId)
        }
        return Lease(userId)
    }

    fun openConnections(): Int = total.get()

    // 상한에 닿은 것이 보이게 남긴다(1분에 한 번).
    private fun refused(userId: Long): Lease? {
        val now = System.currentTimeMillis()
        val last = lastRefusalWarnAt.get()
        if (!closing && now - last >= 60_000 && lastRefusalWarnAt.compareAndSet(last, now)) {
            log.warn("{} refused a connection: open={}/{}, user {} open={}/{}", name, total.get(), maxConnections, userId, perUser[userId] ?: 0, maxPerUser)
        }
        return null
    }
}
