package com.trading.bot.kis.market

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** KST 벽시계 기준 [open, close) 세션. */
data class KrxSession(val open: LocalTime, val close: LocalTime)

data class KrxHoliday(val date: LocalDate, val reason: String, val source: String)

/** 이 날은 정규 세션 대신 [session] 으로 연다. */
data class KrxSpecialSession(val date: LocalDate, val session: KrxSession, val reason: String, val source: String)

/**
 * KRX 휴장일·특수 세션 자료. [coveredFrom]–[coveredThrough] 의 휴장일만 안다.
 * 자료 오타가 주문 창을 조용히 바꾸지 않게 만들 때 모두 검사하고, 하나라도 틀리면 틀린 항목을 모두 담아 예외를 던진다.
 * 하한은 연도를 잘못 적은 휴장일(갱신한 해가 아닌 해의 평일)이 검사를 통과해 실제 휴장일을 거래일로 만드는 것을 막는다.
 * 새 해 자료는 기존 행을 남긴 채 이어 붙이고 coveredThrough 만 늘린다. coveredFrom 을 옮기면 그 앞의 날은 범위 밖이 되어 신규 매수가 막힌다.
 * 중복 키·뒤에 붙은 내용·모르는 키는 편집 실수로 휴장일 블록을 통째로 잃게 하므로 받지 않는다.
 */
class KrxSchedule(
    val coveredFrom: LocalDate,
    val coveredThrough: LocalDate,
    val regularSession: KrxSession,
    val holidays: List<KrxHoliday>,
    val specialSessions: List<KrxSpecialSession>,
) {
    private val holidayDates: Set<LocalDate> = holidays.mapTo(HashSet()) { it.date }
    private val specialByDate: Map<LocalDate, KrxSession> = specialSessions.associate { it.date to it.session }

    init {
        val problems = problems()
        require(problems.isEmpty()) { "KRX 캘린더 자료가 잘못됐다: ${problems.joinToString("; ")}" }
    }

    fun isHoliday(date: LocalDate): Boolean = date in holidayDates

    fun specialSession(date: LocalDate): KrxSession? = specialByDate[date]

    private fun problems(): List<String> = buildList {
        if (coveredFrom.isAfter(coveredThrough)) add("coveredFrom $coveredFrom 이 coveredThrough $coveredThrough 뒤다")
        checkSession("정규 세션", regularSession)
        holidays.forEach { h ->
            checkDate("휴장일 ${h.date}", h.date)
        }
        specialSessions.forEach { s ->
            checkDate("특수 세션 ${s.date}", s.date)
            if (s.date in holidayDates) add("특수 세션 ${s.date} 이 휴장일과 겹친다")
            checkSession("특수 세션 ${s.date}", s.session)
        }
        duplicates(holidays.map { it.date }).forEach { add("휴장일 $it 이 중복됐다") }
        duplicates(specialSessions.map { it.date }).forEach { add("특수 세션 $it 이 중복됐다") }
    }

    private fun MutableList<String>.checkDate(label: String, date: LocalDate) {
        if (date.isBefore(coveredFrom)) add("$label 이 coveredFrom $coveredFrom 앞이다")
        if (date.isAfter(coveredThrough)) add("$label 이 coveredThrough $coveredThrough 뒤다")
        if (isWeekend(date)) add("$label 이 주말(${date.dayOfWeek})이다")
    }

    private fun MutableList<String>.checkSession(label: String, session: KrxSession) {
        if (!session.open.isBefore(session.close)) add("$label 개장 ${session.open} 이 폐장 ${session.close} 보다 늦거나 같다")
        listOf(session.open, session.close).forEach { t ->
            if (t.isBefore(EARLIEST) || t.isAfter(LATEST)) add("$label 시각 $t 이 $EARLIEST–$LATEST 밖이다")
        }
    }

    private fun duplicates(dates: List<LocalDate>): Set<LocalDate> =
        dates.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    companion object {
        const val RESOURCE = "kis/krx-calendar.json"

        // 밖이면 시각을 잘못 적은 것으로 본다. 정규장 전 세션(프리마켓)을 자료에 넣게 되면 EARLIEST 를 다시 정해야 한다.
        private val EARLIEST: LocalTime = LocalTime.of(8, 0)
        private val LATEST: LocalTime = LocalTime.of(20, 0)
        private val MAPPER = ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

        private val ROOT_KEYS = setOf("coveredFrom", "coveredThrough", "regularSession", "holidays", "specialSessions")
        private val SESSION_KEYS = setOf("open", "close")
        private val HOLIDAY_KEYS = setOf("date", "reason", "source")
        private val SPECIAL_KEYS = setOf("date", "open", "close", "reason", "source")

        fun isWeekend(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

        /** 클래스패스 자료를 읽는다. 없거나 읽지 못하면 예외. */
        fun load(resource: String = RESOURCE): KrxSchedule {
            val stream = KrxSchedule::class.java.classLoader.getResourceAsStream(resource)
                ?: throw IllegalStateException("KRX 캘린더 자료 $resource 를 찾지 못했다")
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            return parse(text)
        }

        fun parse(json: String): KrxSchedule {
            val root = runCatching { MAPPER.readTree(json) }
                .getOrElse { throw IllegalArgumentException("KRX 캘린더 자료를 JSON 으로 읽지 못했다: ${it.message}", it) }
            require(root != null && root.isObject) { "KRX 캘린더 자료의 최상위가 객체가 아니다" }
            root.onlyKeys(ROOT_KEYS, "최상위")
            return KrxSchedule(
                coveredFrom = root.date("coveredFrom", "최상위"),
                coveredThrough = root.date("coveredThrough", "최상위"),
                regularSession = root.required("regularSession", "최상위")
                    .also { it.onlyKeys(SESSION_KEYS, "regularSession") }
                    .session("regularSession"),
                holidays = root.array("holidays").mapIndexed { i, node ->
                    val where = "holidays[$i]"
                    node.onlyKeys(HOLIDAY_KEYS, where)
                    KrxHoliday(node.date("date", where), node.text("reason", where), node.text("source", where))
                },
                specialSessions = root.array("specialSessions").mapIndexed { i, node ->
                    val where = "specialSessions[$i]"
                    node.onlyKeys(SPECIAL_KEYS, where)
                    KrxSpecialSession(node.date("date", where), node.session(where), node.text("reason", where), node.text("source", where))
                },
            )
        }

        private fun JsonNode.onlyKeys(allowed: Set<String>, where: String) {
            require(isObject) { "KRX 캘린더 자료 $where 가 객체가 아니다" }
            val unknown = fieldNames().asSequence().filterNot { it in allowed }.toList()
            require(unknown.isEmpty()) { "KRX 캘린더 자료 $where 에 모르는 키 $unknown 가 있다(오타인지 확인)" }
        }

        private fun JsonNode.required(field: String, where: String): JsonNode =
            get(field)?.takeUnless { it.isNull } ?: throw IllegalArgumentException("KRX 캘린더 자료 $where 에 $field 가 없다")

        private fun JsonNode.text(field: String, where: String): String {
            val node = required(field, where)
            require(node.isTextual && node.asText().isNotBlank()) { "KRX 캘린더 자료 $where.$field 가 빈 문자열이거나 문자열이 아니다" }
            return node.asText()
        }

        private fun JsonNode.date(field: String, where: String): LocalDate = text(field, where).let { raw ->
            runCatching { LocalDate.parse(raw) }
                .getOrElse { throw IllegalArgumentException("KRX 캘린더 자료 $where.$field=$raw 가 yyyy-MM-dd 날짜가 아니다", it) }
        }

        private fun JsonNode.time(field: String, where: String): LocalTime = text(field, where).let { raw ->
            runCatching { LocalTime.parse(raw) }
                .getOrElse { throw IllegalArgumentException("KRX 캘린더 자료 $where.$field=$raw 가 HH:mm 시각이 아니다", it) }
        }

        private fun JsonNode.session(where: String): KrxSession = KrxSession(time("open", where), time("close", where))

        private fun JsonNode.array(field: String): List<JsonNode> {
            val node = required(field, "최상위")
            require(node.isArray) { "KRX 캘린더 자료 $field 가 배열이 아니다" }
            return node.toList()
        }
    }
}
