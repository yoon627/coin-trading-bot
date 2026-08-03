package com.trading.bot.domain

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 거래일 = 09:00 KST 에 바뀌는 하루. 09:00 이전은 전날 거래일에 속한다.
 *
 * 진입 시점 기록(TradingState.boughtDate)과 일일 리셋 판정(DailyResetManager)이 같은 경계를 써야
 * 재시작 후 "오늘 이미 매수했는가"가 어긋나지 않는다.
 */
object TradingDay {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")
    private val RESET_TIME: LocalTime = LocalTime.of(9, 0)

    fun of(clock: Clock): LocalDate = of(ZonedDateTime.now(clock).toLocalDateTime())

    fun of(dateTime: LocalDateTime): LocalDate =
        if (dateTime.toLocalTime().isBefore(RESET_TIME)) dateTime.toLocalDate().minusDays(1) else dateTime.toLocalDate()
}
