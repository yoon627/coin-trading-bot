package com.trading.bot.kis.marketdata

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/**
 * 국내 정규장 개장 판단. 장외엔 시세폴링·매매를 skip(plan D17). MVP 는 평일+09:00~15:30(KST) 하드코딩.
 * 임시휴장/단축거래는 chk-holiday(CTCA0903R) 보완이 후속(스펙 응답구조 스모크 확인 필요).
 */
fun interface KisMarketCalendar {
    fun isTradingNow(): Boolean
}

@Component
class KisMarketCalendarImpl(private val clock: Clock) : KisMarketCalendar {
    override fun isTradingNow(): Boolean {
        val now = clock.instant().atZone(KST)
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return false
        val t = now.toLocalTime()
        return !t.isBefore(OPEN) && !t.isAfter(CLOSE)
    }

    private companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val OPEN: LocalTime = LocalTime.of(9, 0)
        val CLOSE: LocalTime = LocalTime.of(15, 30)
    }
}
