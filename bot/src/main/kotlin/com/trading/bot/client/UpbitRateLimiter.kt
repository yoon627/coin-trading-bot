package com.trading.bot.client

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 한 업비트 계좌의 private 요청 속도를 그룹 한도 아래로 맞춘다. 업비트는 계좌마다 주문 생성 초당 12·그 밖 초당 30 을 넘으면
 * 429 를, 되풀이하면 418(차단)을 준다. 429 를 받은 체결 확인은 결과 모름이 되어 손절이 늦으므로 받기 전에 기다린다.
 *
 * 그룹마다 토큰 버킷이다. 어떤 1초 창에서도 보내는 수는 용량 + 초당 보충을 넘지 않으므로 둘의 합을 공식 한도보다 낮게 둔다
 * (주문 2 + 8 = 10, 그 밖 5 + 20 = 25). 한도에 닿으면 거절하지 않고 차례가 올 때까지 suspend 로 기다린다 — 매수·매도의
 * 취소 불가 구간 안에서도 불린다.
 */
class UpbitRateLimiter(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    enum class Group(val capacity: Double, val perSecond: Double) {
        ORDER(capacity = 2.0, perSecond = 8.0),
        DEFAULT(capacity = 5.0, perSecond = 20.0),
    }

    private class Bucket(val group: Group) {
        val mutex = Mutex()
        var tokens = group.capacity
        var lastMs: Long? = null
    }

    private val buckets = Group.entries.associateWith { Bucket(it) }

    suspend fun acquire(group: Group) {
        val bucket = buckets.getValue(group)
        // 자리를 먼저 잡고(토큰이 음수가 될 수 있다) 잠금 밖에서 기다려, 기다리는 요청끼리 순서대로 한 칸씩 띄운다.
        val waitMs = bucket.mutex.withLock {
            val now = nowMs()
            val elapsed = now - (bucket.lastMs ?: now)
            bucket.tokens = min(group.capacity, bucket.tokens + elapsed * group.perSecond / 1_000)
            bucket.lastMs = now
            bucket.tokens -= 1
            if (bucket.tokens >= 0) 0L else ceil(-bucket.tokens * 1_000 / group.perSecond).toLong()
        }
        if (waitMs > 0) delay(waitMs)
    }
}

/** 업비트 계좌(접근 키)마다 제한기 하나. 엔진·화면·대조가 같은 계좌로 만든 클라이언트들이 한도를 함께 쓴다. */
class UpbitRateLimiters {
    private val byAccount = ConcurrentHashMap<String, UpbitRateLimiter>()

    fun forAccount(accessKey: String): UpbitRateLimiter = byAccount.computeIfAbsent(accessKey) { UpbitRateLimiter() }
}
