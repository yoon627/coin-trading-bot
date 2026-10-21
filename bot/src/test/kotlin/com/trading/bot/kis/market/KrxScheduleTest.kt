package com.trading.bot.kis.market

import java.time.LocalDate
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KrxScheduleTest {

    private val regular = KrxSession(LocalTime.of(9, 0), LocalTime.of(15, 30))

    private fun holiday(date: String) = KrxHoliday(LocalDate.parse(date), "사유", "출처")

    private fun special(date: String, open: String = "10:00", close: String = "15:30") =
        KrxSpecialSession(LocalDate.parse(date), KrxSession(LocalTime.parse(open), LocalTime.parse(close)), "사유", "출처")

    private fun schedule(
        holidays: List<KrxHoliday> = listOf(holiday("2026-10-09")),
        specials: List<KrxSpecialSession> = listOf(special("2026-11-19")),
        regularSession: KrxSession = regular,
    ) = KrxSchedule(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), regularSession, holidays, specials)

    private fun invalid(build: () -> KrxSchedule): String = assertThrows<IllegalArgumentException> { build() }.message.orEmpty()

    @Test
    fun `번들 자료는 검증을 통과하고 2026 휴장일 17일과 특수 세션 2개를 담는다`() {
        val schedule = KrxSchedule.load()

        assertThat(schedule.coveredFrom).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(schedule.coveredThrough).isEqualTo(LocalDate.of(2026, 12, 31))
        assertThat(schedule.regularSession).isEqualTo(regular)
        assertThat(schedule.holidays.map { it.date.toString() }).containsExactly(
            "2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18", "2026-03-02", "2026-05-01", "2026-05-05", "2026-05-25",
            "2026-06-03", "2026-07-17", "2026-08-17", "2026-09-24", "2026-09-25", "2026-10-05", "2026-10-09", "2026-12-25",
            "2026-12-31",
        )
        assertThat(schedule.specialSessions.map { it.date.toString() to it.session })
            .containsExactly(
                "2026-01-02" to KrxSession(LocalTime.of(10, 0), LocalTime.of(15, 30)),
                "2026-11-19" to KrxSession(LocalTime.of(10, 0), LocalTime.of(15, 30)),
            )
        assertThat(schedule.holidays.flatMap { listOf(it.reason, it.source) } + schedule.specialSessions.flatMap { listOf(it.reason, it.source) })
            .allMatch { it.isNotBlank() }
    }

    @Test
    fun `coveredFrom 앞이나 coveredThrough 뒤의 날짜는 거절한다`() {
        assertThat(invalid { schedule(holidays = listOf(holiday("2027-01-01"))) }).contains("휴장일 2027-01-01", "coveredThrough")
        assertThat(invalid { schedule(specials = listOf(special("2027-01-04"))) }).contains("특수 세션 2027-01-04", "coveredThrough")
        assertThat(invalid { schedule(holidays = listOf(holiday("2025-10-09"))) }).contains("휴장일 2025-10-09", "coveredFrom")
        assertThat(invalid { KrxSchedule(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 31), regular, emptyList(), emptyList()) })
            .contains("coveredFrom 2027-01-01 이 coveredThrough 2026-12-31 뒤다")
    }

    @Test
    fun `주말 휴장일과 주말 특수 세션은 거절한다`() {
        assertThat(invalid { schedule(holidays = listOf(holiday("2026-09-26"))) }).contains("휴장일 2026-09-26", "주말")
        assertThat(invalid { schedule(specials = listOf(special("2026-11-21"))) }).contains("특수 세션 2026-11-21", "주말")
    }

    @Test
    fun `휴장일과 겹친 특수 세션은 거절한다`() {
        assertThat(invalid { schedule(specials = listOf(special("2026-10-09"))) }).contains("특수 세션 2026-10-09", "휴장일과 겹친다")
    }

    @Test
    fun `같은 날짜가 두 번 나오면 거절한다`() {
        assertThat(invalid { schedule(holidays = listOf(holiday("2026-10-09"), holiday("2026-10-09"))) }).contains("휴장일 2026-10-09 이 중복")
        assertThat(invalid { schedule(specials = listOf(special("2026-11-19"), special("2026-11-19", open = "09:30"))) })
            .contains("특수 세션 2026-11-19 이 중복")
    }

    @Test
    fun `개장이 폐장보다 늦거나 같으면 거절한다`() {
        assertThat(invalid { schedule(specials = listOf(special("2026-11-19", open = "15:30", close = "15:30"))) })
            .contains("특수 세션 2026-11-19 개장 15:30")
        assertThat(invalid { schedule(regularSession = KrxSession(LocalTime.of(15, 30), LocalTime.of(9, 0))) }).contains("정규 세션 개장")
    }

    @Test
    fun `08시 이전이나 20시 이후 시각은 거절한다`() {
        assertThat(invalid { schedule(specials = listOf(special("2026-11-19", open = "07:59", close = "15:30"))) })
            .contains("특수 세션 2026-11-19 시각 07:59")
        assertThat(invalid { schedule(specials = listOf(special("2026-11-19", open = "10:00", close = "20:01"))) })
            .contains("시각 20:01")
        schedule(specials = listOf(special("2026-11-19", open = "08:00", close = "20:00")))
    }

    @Test
    fun `틀린 항목이 여럿이면 모두 메시지에 담는다`() {
        val message = invalid { schedule(holidays = listOf(holiday("2026-09-26"), holiday("2027-01-01"))) }

        assertThat(message).contains("휴장일 2026-09-26", "휴장일 2027-01-01")
    }

    @Test
    fun `필드가 빠졌거나 형식이 틀린 JSON 은 어느 필드인지 알려 준다`() {
        val missingSource = """
            {"coveredFrom":"2026-01-01","coveredThrough":"2026-12-31","regularSession":{"open":"09:00","close":"15:30"},
             "holidays":[{"date":"2026-10-09","reason":"한글날"}],"specialSessions":[]}
        """.trimIndent()
        assertThat(assertThrows<IllegalArgumentException> { KrxSchedule.parse(missingSource) }.message).contains("holidays[0]", "source")

        val badDate = missingSource.replace("\"2026-10-09\",\"reason\":\"한글날\"", "\"2026/10/09\",\"reason\":\"한글날\",\"source\":\"s\"")
        assertThat(assertThrows<IllegalArgumentException> { KrxSchedule.parse(badDate) }.message).contains("holidays[0].date=2026/10/09")

        assertThrows<IllegalArgumentException> { KrxSchedule.parse("{not json") }
    }

    @Test
    fun `중복 키·뒤에 붙은 내용·모르는 키는 휴장일을 조용히 잃게 하므로 거절한다`() {
        val valid = """
            {"coveredFrom":"2026-01-01","coveredThrough":"2026-12-31","regularSession":{"open":"09:00","close":"15:30"},
             "holidays":[{"date":"2026-10-09","reason":"한글날","source":"s"}],"specialSessions":[]}
        """.trimIndent()
        KrxSchedule.parse(valid)

        val duplicatedHolidays = valid.replace("\"specialSessions\":[]", "\"specialSessions\":[],\"holidays\":[]")
        val trailing = "$valid {\"coveredThrough\":\"2027-12-31\"}"
        val typo = valid.replace("\"specialSessions\":[]", "\"specialSessions\":[],\"specialSesssions\":[]")
        val itemTypo = valid.replace("\"reason\":\"한글날\"", "\"reason\":\"한글날\",\"dates\":\"2026-10-12\"")

        listOf(duplicatedHolidays, trailing).forEach { assertThrows<IllegalArgumentException> { KrxSchedule.parse(it) } }
        assertThat(assertThrows<IllegalArgumentException> { KrxSchedule.parse(typo) }.message).contains("최상위", "specialSesssions")
        assertThat(assertThrows<IllegalArgumentException> { KrxSchedule.parse(itemTypo) }.message).contains("holidays[0]", "dates")
    }

    @Test
    fun `자료 파일을 찾지 못하면 예외를 던진다`() {
        val error = assertThrows<IllegalStateException> { KrxSchedule.load("kis/missing-calendar.json") }

        assertThat(error.message).contains("kis/missing-calendar.json")
    }
}
