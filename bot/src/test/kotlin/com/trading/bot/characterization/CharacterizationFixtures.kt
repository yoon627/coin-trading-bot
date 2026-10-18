package com.trading.bot.characterization

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.domain.Account
import com.trading.bot.domain.TradeRecord
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.slf4j.LoggerFactory

/**
 * 거래일은 clock 의 zone 으로 계산되므로 zone 을 기본값 없이 받는다.
 * withZone 은 같은 시각을 공유하는 사본을 만들 수 없어 부르면 실패시킨다.
 */
internal class MutableClock(var now: Instant, private val zone: ZoneId) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId?): Clock = throw UnsupportedOperationException("MutableClock.withZone")

    fun advance(seconds: Long) {
        now = now.plus(Duration.ofSeconds(seconds))
    }
}

/** commitFill 호출을 다른 mock 호출과 같은 순서 검증에 넣으려고 둔 sink. */
internal interface FillSink {
    suspend fun commit(record: TradeRecord): Boolean
}

internal fun krw(balance: String = "1000000") = Account(currency = "KRW", balance = balance)

internal fun btc(balance: String, avg: String = "50000000", locked: String = "0") =
    Account(currency = "BTC", balance = balance, locked = locked, avgBuyPrice = avg)

/** [type] 로거에 남은 메시지를 [block] 동안 모은다. */
internal inline fun <T> capturingLogs(type: Class<*>, block: (ListAppender<ILoggingEvent>) -> T): T {
    val logger = LoggerFactory.getLogger(type) as Logger
    val logs = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(logs)
    try {
        return block(logs)
    } finally {
        logger.detachAppender(logs)
    }
}
