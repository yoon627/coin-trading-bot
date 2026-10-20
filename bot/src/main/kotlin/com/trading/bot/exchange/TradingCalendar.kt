package com.trading.bot.exchange

import com.trading.bot.domain.TradingDay
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

interface TradingCalendar {
    /** false 면 엔진은 평가·주문을 하지 않고 미해소 주문만 확정한다. */
    fun isSessionOpen(now: Instant): Boolean

    /** [clock] 의 zone 으로 본 지금의 거래일. */
    fun tradingDate(clock: Clock): LocalDate

    /** [dateTime] 은 KST 벽시계 시각이다. */
    fun tradingDate(dateTime: LocalDateTime): LocalDate

    /** 보유 기간 상한에 쓰는 거래일 수. */
    fun tradingDaysBetween(from: LocalDate, to: LocalDate): Long
}

/** 코인은 쉬는 날이 없다. 거래일은 KST 09:00 에 바뀌고 보유일은 달력 날짜로 센다. */
object UpbitTradingCalendar : TradingCalendar {
    override fun isSessionOpen(now: Instant): Boolean = true

    override fun tradingDate(clock: Clock): LocalDate = TradingDay.of(clock)

    override fun tradingDate(dateTime: LocalDateTime): LocalDate = TradingDay.of(dateTime)

    override fun tradingDaysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)
}
