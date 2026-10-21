package com.trading.bot.kis.client

import java.util.TreeSet
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** 리미터 대기 순서. 앞에 있을수록 먼저 허가받는다. */
enum class KisLane(internal val shortWait: Boolean) {
    HELD_QUOTE(false),
    ORDER(false),
    RECONCILE(false),
    QUOTE(true),
    QUERY(true),
}

/**
 * (환경, 키 지문) 하나의 요청 간격을 [minIntervalMs] 이상으로 벌린다.
 *
 * 슬롯은 허가하는 순간에 배정한다. 기다리는 요청이 미래 슬롯을 미리 잡지 않으므로, 슬롯이 열릴 때 그때 가장 높은 레인의
 * 대기자가 받는다. 주문은 보유 시세 대기분만 앞에 두고 늦어도 다음 슬롯에 허가된다.
 * 대기는 코루틴 지연으로 하고 현재 시각은 [nowMs] 로 읽어, 테스트가 가상 시간으로 돌릴 수 있다.
 */
class KisRateLimiter(
    private val minIntervalMs: Long,
    private val shortWaitLimitMs: Long,
    private val longWaitLimitMs: Long,
    private val nowMs: () -> Long = ::monotonicMs,
) : AutoCloseable {

    init {
        require(minIntervalMs >= 1) { "minIntervalMs 는 1 이상이어야 한다: $minIntervalMs" }
        require(shortWaitLimitMs >= 1 && longWaitLimitMs >= 1) { "대기 상한은 1 이상이어야 한다" }
    }

    private class Waiter(val lane: KisLane, val seq: Long) {
        val wake = Channel<Unit>(Channel.CONFLATED)
    }

    private val lock = Any()
    private val waiters = TreeSet(compareBy<Waiter>({ it.lane.ordinal }, { it.seq }))
    private var nextSeq = 0L
    private var lastGrantMs: Long? = null
    private var closed = false

    suspend fun acquire(lane: KisLane, endpoint: String = ENDPOINT) {
        val deadline = nowMs() + if (lane.shortWait) shortWaitLimitMs else longWaitLimitMs
        val waiter = synchronized(lock) {
            if (closed) throw closedException(endpoint)
            val now = nowMs()
            if (waiters.isEmpty() && slotOpen(now)) {
                lastGrantMs = now
                return
            }
            Waiter(lane, nextSeq++).also { waiters.add(it) }
        }
        try {
            while (true) {
                val sleepMs = synchronized(lock) {
                    if (closed) throw closedException(endpoint)
                    val now = nowMs()
                    val head = waiters.first() === waiter
                    if (head && slotOpen(now)) {
                        waiters.remove(waiter)
                        lastGrantMs = now
                        wakeHead()
                        return
                    }
                    if (now >= deadline) {
                        throw KisTransientException(endpoint, throttled = true, reason = "$lane 레인 리미터 대기 상한 초과")
                    }
                    // 선두만 슬롯 시각에 깨어난다. 나머지는 선두가 바뀌었다는 신호나 자기 상한을 기다린다.
                    if (head) minOf(nextSlotMs() - now, deadline - now) else deadline - now
                }
                withTimeoutOrNull(sleepMs) { waiter.wake.receive() }
            }
        } finally {
            synchronized(lock) {
                if (waiters.remove(waiter)) wakeHead()
            }
        }
    }

    /** 닫혔으면 바로 실패한다. 허가 전에 하는 토큰 발급을 닫힌 세션에서 시작하지 않게 한다. */
    fun checkOpen(endpoint: String = ENDPOINT) {
        synchronized(lock) { if (closed) throw closedException(endpoint) }
    }

    /** 대기 중인 요청과 이후 요청을 바로 실패시킨다. 정지 중에 새 허가를 기다리지 않게 한다. */
    override fun close() {
        synchronized(lock) {
            closed = true
            waiters.forEach { it.wake.trySend(Unit) }
        }
    }

    private fun slotOpen(now: Long): Boolean {
        val last = lastGrantMs ?: return true
        return now - last >= minIntervalMs
    }

    private fun nextSlotMs(): Long = (lastGrantMs ?: Long.MIN_VALUE) + minIntervalMs

    private fun wakeHead() {
        waiters.firstOrNull()?.wake?.trySend(Unit)
    }

    private fun closedException(endpoint: String) =
        KisTransientException(endpoint, throttled = false, reason = "리미터가 닫혔다")

    private companion object {
        const val ENDPOINT = "rate-limiter"

        fun monotonicMs(): Long = System.nanoTime() / 1_000_000
    }
}
