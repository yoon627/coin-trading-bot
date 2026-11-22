package com.trading.bot.engine

import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * 체결 나이는 거래소 시계(체결 시각)와 이 호스트 시계의 차이라 호스트 시계가 어긋나면 체결 판단이 조용히 멈추거나(모두 낡음)
 * 낡은 체결을 신선하게 본다(미래 시각). 엔진의 수집기 하나가 부르며, 둘 다 1분에 한 번만 알린다.
 */
internal class TradeAgeMonitor(private val clock: Clock, private val userId: Long) {
    companion object {
        private const val AHEAD_TOLERANCE_MS = 5_000L
        private const val WINDOW_MS = 60_000L
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private var windowStartMs: Long? = null
    private var fresh = 0L
    private var stale = 0L
    private var aheadWarnedAtMs: Long? = null
    private var staleWarnedAtMs: Long? = null

    fun observe(tradeTime: Instant) {
        val now = clock.millis()
        val start = windowStartMs
        if (start == null || now - start >= WINDOW_MS) {
            if (start != null && fresh == 0L && stale > 0 && due(staleWarnedAtMs, now)) {
                staleWarnedAtMs = now
                log.warn(
                    "All {} trades for user {} in the last minute were stale (older than {} ms) — trades decide nothing; check the host clock (NTP) or the feed delay",
                    stale, userId, TickerTrader.PRICE_STALE_THRESHOLD_MS,
                )
            }
            windowStartMs = now
            fresh = 0
            stale = 0
        }
        val ageMs = now - tradeTime.toEpochMilli()
        if (ageMs < TickerTrader.PRICE_STALE_THRESHOLD_MS) fresh++ else stale++
        if (ageMs < -AHEAD_TOLERANCE_MS && due(aheadWarnedAtMs, now)) {
            aheadWarnedAtMs = now
            log.warn("A trade for user {} is {} ms ahead of this host's clock — check the host clock (NTP)", userId, -ageMs)
        }
    }

    private fun due(lastMs: Long?, now: Long): Boolean = lastMs == null || now - lastMs >= WINDOW_MS
}
