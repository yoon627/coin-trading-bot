package com.trading.bot.engine

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/**
 * 매수는 원화 잔고를 나눠 쓰므로 엔진에서 한 번에 하나만 나간다(잔고 조회부터 체결 확인까지).
 * 창구 앞에서 기다리는 종목은 멈춤이 아니라 정지 감시에서 뺀다 — 창구를 쥔 종목이 멈추면 그쪽이 걸린다.
 */
internal class BuyWindow(
    // 오래 기다렸다가 막 창구를 잡은 종목을 멈춤으로 보지 않게 잡은 시각을 진행으로 남긴다.
    private val onAcquired: (ticker: String) -> Unit,
) {
    private val mutex = Mutex()
    private val waiting = ConcurrentHashMap.newKeySet<String>()

    fun isWaiting(ticker: String): Boolean = ticker in waiting

    fun clearWaiting() = waiting.clear()

    suspend fun <T> withWindow(ticker: String, block: suspend () -> T): T {
        waiting += ticker
        try {
            mutex.lock()
        } finally {
            waiting -= ticker
        }
        try {
            onAcquired(ticker)
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
