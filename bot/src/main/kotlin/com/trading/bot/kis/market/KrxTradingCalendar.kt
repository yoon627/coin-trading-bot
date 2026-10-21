package com.trading.bot.kis.market

import com.trading.bot.exchange.TradingCalendar
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * KRX 정규장 캘린더. 시계의 zone 과 무관하게 늘 KST 로 보고, 거래일은 KST 00:00 에 바뀐다.
 * [KrxSchedule.coveredFrom]–[KrxSchedule.coveredThrough] 밖의 날짜는 휴장일을 모르므로 평일이면 정규 세션으로 본다.
 */
class KrxTradingCalendar(private val schedule: KrxSchedule) : TradingCalendar {

    val coveredFrom: LocalDate get() = schedule.coveredFrom

    val coveredThrough: LocalDate get() = schedule.coveredThrough

    fun covers(date: LocalDate): Boolean = !date.isBefore(schedule.coveredFrom) && !date.isAfter(schedule.coveredThrough)

    fun isTradingDay(date: LocalDate): Boolean =
        !KrxSchedule.isWeekend(date) && !(covers(date) && schedule.isHoliday(date))

    /** 거래일이 아니면 null. */
    fun session(date: LocalDate): KrxSession? {
        if (!isTradingDay(date)) return null
        return schedule.specialSession(date) ?: schedule.regularSession
    }

    override fun isSessionOpen(now: Instant): Boolean {
        val local = LocalDateTime.ofInstant(now, KST)
        val session = session(local.toLocalDate()) ?: return false
        val time = local.toLocalTime()
        return !time.isBefore(session.open) && time.isBefore(session.close)
    }

    override fun tradingDate(clock: Clock): LocalDate = LocalDate.ofInstant(clock.instant(), KST)

    override fun tradingDate(dateTime: LocalDateTime): LocalDate = dateTime.toLocalDate()

    /** (from, to] 의 거래일 수. to 가 앞서면 (to, from] 의 거래일 수에 음수 부호를 붙인다. */
    override fun tradingDaysBetween(from: LocalDate, to: LocalDate): Long = when {
        to.isBefore(from) -> -countTradingDays(to, from)
        else -> countTradingDays(from, to)
    }

    private fun countTradingDays(after: LocalDate, through: LocalDate): Long =
        generateSequence(after.plusDays(1)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(through) }
            .count(::isTradingDay)
            .toLong()

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
