package com.trading.bot.events

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLongArray
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 사용자별 "바뀜" 신호. 종류마다 번호 하나만 올리고, 스트림이 그 번호를 당겨 읽는다 — 발행 쪽(엔진·체결 커밋)은 기다리지 않고
 * 느린 화면에도 쌓이지 않는다. 데이터는 보내지 않는다(화면이 REST 로 다시 읽는다). 번호는 메모리라 재시작 때 0 이다.
 */
@Component
class UserEventHub {
    enum class Kind(val event: String) {
        /** 체결 기록이 커밋됐다 — 잔고·거래 내역·상태. */
        TRADE("trade"),

        /** 봇·포지션 상태가 바뀌었을 수 있다 — 상태(대시보드는 잔고도). */
        STATE("state"),
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val versions = ConcurrentHashMap<Long, AtomicLongArray>()

    /** 손절 경로 안에서도 부르므로 어떤 경우에도 던지지 않는다. */
    fun publish(userId: Long, kind: Kind) {
        try {
            versions.computeIfAbsent(userId) { AtomicLongArray(Kind.entries.size) }.incrementAndGet(kind.ordinal)
        } catch (e: Exception) {
            log.warn("user event not published (user {}, {}): {}", userId, kind, e.message)
        }
    }

    /** 이 사용자의 종류별 번호(없으면 모두 0). */
    fun versions(userId: Long): LongArray {
        val v = versions[userId] ?: return LongArray(Kind.entries.size)
        return LongArray(Kind.entries.size) { v.get(it) }
    }
}
