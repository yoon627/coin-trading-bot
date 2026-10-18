package com.trading.bot.domain

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 09:00 KST 에 바뀌는 거래일. 진입 기록과 일일 리셋이 같은 경계를 써야
 * 재시작 후 당일 매수 여부가 어긋나지 않는다.
 */
object TradingDay {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")
    private val RESET_TIME: LocalTime = LocalTime.of(9, 0)

    fun of(clock: Clock): LocalDate = of(ZonedDateTime.now(clock).toLocalDateTime())

    fun of(dateTime: LocalDateTime): LocalDate =
        if (dateTime.toLocalTime().isBefore(RESET_TIME)) dateTime.toLocalDate().minusDays(1) else dateTime.toLocalDate()
}
