package com.trading.bot.kis.market

import com.trading.bot.domain.TradeSide
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KrxTradingCalendarTest {

    private val calendar = KrxTradingCalendar(KrxSchedule.load())

    private fun kst(date: String, time: String): Instant =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(KrxTradingCalendar.KST).toInstant()

    private fun openAt(date: String, vararg times: String): List<Boolean> = times.map { calendar.isSessionOpen(kst(date, it)) }

    @Test
    fun `정규 세션은 09시 00분부터 15시 30분 전까지 열린다`() {
        assertThat(openAt("2026-10-21", "08:59:59", "09:00:00", "15:29:59", "15:30:00")).containsExactly(false, true, true, false)
    }

    @Test
    fun `휴장일과 주말은 닫혀 있고 세션이 없다`() {
        assertThat(openAt("2026-10-09", "10:00")).containsExactly(false)
        assertThat(openAt("2026-10-24", "10:00")).containsExactly(false)
        assertThat(calendar.session(LocalDate.of(2026, 10, 9))).isNull()
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 10, 25))).isFalse()
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 10, 21))).isTrue()
    }

    @Test
    fun `특수 세션이 정규 세션을 대신한다`() {
        assertThat(openAt("2026-01-02", "09:30", "09:59:59", "10:00", "15:29:59", "15:30")).containsExactly(false, false, true, true, false)
        assertThat(calendar.session(LocalDate.of(2026, 11, 19))).isEqualTo(KrxSession(LocalTime.of(10, 0), LocalTime.of(15, 30)))
    }

    @Test
    fun `자료 범위 밖의 날은 평일 규칙과 정규 세션을 쓴다`() {
        val friday = LocalDate.of(2027, 1, 1)

        assertThat(calendar.covers(LocalDate.of(2026, 12, 31))).isTrue()
        assertThat(calendar.covers(friday)).isFalse()
        assertThat(calendar.isTradingDay(friday)).isTrue()
        assertThat(calendar.isTradingDay(LocalDate.of(2027, 1, 2))).isFalse()
        assertThat(calendar.session(friday)).isEqualTo(KrxSession(LocalTime.of(9, 0), LocalTime.of(15, 30)))
        assertThat(openAt("2027-01-01", "09:00")).containsExactly(true)
    }

    @Test
    fun `자료 범위 앞의 날도 범위 밖이라 휴장일을 모르는 평일로 본다`() {
        // 다음 해 자료만 남기고 갈아끼우면 그 앞 해의 남은 휴장일이 범위 밖이 된다 — 매수 게이트가 이것으로 막는다.
        val nextYearOnly = KrxSchedule(
            coveredFrom = LocalDate.of(2027, 1, 1),
            coveredThrough = LocalDate.of(2027, 12, 31),
            regularSession = KrxSession(LocalTime.of(9, 0), LocalTime.of(15, 30)),
            holidays = emptyList(),
            specialSessions = emptyList(),
        )
        val replaced = KrxTradingCalendar(nextYearOnly)
        val christmas = LocalDate.of(2026, 12, 25)

        assertThat(replaced.covers(christmas)).isFalse()
        assertThat(replaced.covers(LocalDate.of(2027, 1, 1))).isTrue()
        assertThat(KrxOrderGate(replaced).mayPlace("KRX-005930", TradeSide.BUY, kst("2026-12-25", "10:00"))).isFalse()
    }

    @Test
    fun `거래일은 시계 zone 과 무관하게 KST 00시에 바뀐다`() {
        val before = Instant.parse("2026-10-21T14:59:59Z")
        val after = Instant.parse("2026-10-21T15:00:00Z")

        listOf(ZoneOffset.UTC, KrxTradingCalendar.KST, ZoneId.of("America/New_York")).forEach { zone ->
            assertThat(calendar.tradingDate(Clock.fixed(before, zone))).isEqualTo(LocalDate.of(2026, 10, 21))
            assertThat(calendar.tradingDate(Clock.fixed(after, zone))).isEqualTo(LocalDate.of(2026, 10, 22))
        }
        // Upbit 와 달리 09시 전도 그날이다.
        assertThat(calendar.tradingDate(LocalDateTime.of(2026, 10, 22, 0, 0))).isEqualTo(LocalDate.of(2026, 10, 22))
        assertThat(calendar.tradingDate(LocalDateTime.of(2026, 10, 22, 8, 59, 59))).isEqualTo(LocalDate.of(2026, 10, 22))
    }

    @Test
    fun `보유 거래일 수는 추석 연휴와 주말을 건너뛰고 앞뒤가 바뀌면 음수다`() {
        val beforeChuseok = LocalDate.of(2026, 9, 23)
        val afterChuseok = LocalDate.of(2026, 9, 28)

        assertThat(calendar.tradingDaysBetween(beforeChuseok, afterChuseok)).isEqualTo(1)
        assertThat(calendar.tradingDaysBetween(LocalDate.of(2026, 9, 18), afterChuseok)).isEqualTo(4)
        assertThat(calendar.tradingDaysBetween(afterChuseok, beforeChuseok)).isEqualTo(-1)
        assertThat(calendar.tradingDaysBetween(afterChuseok, afterChuseok)).isEqualTo(0)
        // 휴장일에 산 경우 그날은 세지 않고 다음 거래일부터 센다.
        assertThat(calendar.tradingDaysBetween(LocalDate.of(2026, 9, 25), afterChuseok)).isEqualTo(1)
    }
}
