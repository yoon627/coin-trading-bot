package com.trading.bot.kis.market

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.domain.TradeSide
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class KrxOrderGateTest {

    private val gate = KrxOrderGate(KrxTradingCalendar(KrxSchedule.load()))
    private val logger = LoggerFactory.getLogger(KrxOrderGate::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setup() {
        logger.addAppender(logs)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(logs)
    }

    private fun kst(date: String, time: String): Instant =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(KrxTradingCalendar.KST).toInstant()

    private fun allowed(side: TradeSide, date: String, vararg times: String): List<Boolean> =
        times.map { gate.mayPlace("KRX-005930", side, kst(date, it)) }

    private fun errors(): List<ILoggingEvent> = logs.list.filter { it.level == Level.ERROR }

    @Test
    fun `매수는 09시 01분부터 15시 15분 전까지만 낸다`() {
        assertThat(allowed(TradeSide.BUY, "2026-10-21", "09:00:59", "09:01:00", "15:14:59", "15:15:00"))
            .containsExactly(false, true, true, false)
    }

    @Test
    fun `매도는 09시 01분부터 종가 단일가가 시작되는 15시 20분 전까지만 낸다`() {
        assertThat(allowed(TradeSide.SELL, "2026-10-21", "09:00:59", "09:01:00", "15:19:59", "15:20:00", "15:25:00"))
            .containsExactly(false, true, true, false, false)
    }

    @Test
    fun `특수 세션 날은 창이 개장 시각을 따라 옮겨진다`() {
        assertThat(allowed(TradeSide.BUY, "2026-01-02", "09:30", "10:00:59", "10:01", "15:14:59", "15:15"))
            .containsExactly(false, false, true, true, false)
        assertThat(allowed(TradeSide.SELL, "2026-11-19", "09:01", "10:00:59", "10:01", "15:19:59", "15:20"))
            .containsExactly(false, false, true, true, false)
    }

    @Test
    fun `휴장일과 주말에는 매수도 매도도 내지 않는다`() {
        TradeSide.entries.forEach { side ->
            assertThat(allowed(side, "2026-09-24", "10:00")).containsExactly(false)
            assertThat(allowed(side, "2026-10-24", "10:00")).containsExactly(false)
        }
        assertThat(errors()).isEmpty()
    }

    @Test
    fun `자료 범위 밖의 날은 매수를 막고 날짜마다 ERROR 를 한 번 남기며 매도는 연다`() {
        assertThat(allowed(TradeSide.BUY, "2027-01-04", "10:00", "11:00", "14:00")).containsOnly(false)
        assertThat(allowed(TradeSide.SELL, "2027-01-04", "10:00", "15:19:59", "15:20")).containsExactly(true, true, false)
        assertThat(errors()).hasSize(1)
        assertThat(errors().single().formattedMessage)
            .isEqualTo("KRX 휴장일 자료가 2026-01-01–2026-12-31 만 담고 있어 2027-01-04 의 신규 매수를 막는다 — krx-calendar.json 을 갱신해야 열린다")

        assertThat(allowed(TradeSide.BUY, "2027-01-05", "10:00", "11:00")).containsOnly(false)
        assertThat(errors()).hasSize(2)
    }

    @Test
    fun `동시에 불러도 범위 밖 ERROR 는 하루 한 번이다`() {
        val threads = 16
        // 확인과 기록 사이의 짧은 경합은 한 번에 잘 드러나지 않아 날짜를 바꿔 가며 여러 번 겨룬다.
        val days = 200
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val barrier = CyclicBarrier(threads)
            repeat(days) { day ->
                val now = kst("2027-01-04", "10:00").plus(Duration.ofDays(day.toLong()))
                val tasks = (1..threads).map {
                    Callable {
                        barrier.await(5, TimeUnit.SECONDS)
                        gate.mayPlace("KRX-005930", TradeSide.BUY, now)
                    }
                }

                val results = pool.invokeAll(tasks, 10, TimeUnit.SECONDS).map { it.get(0, TimeUnit.SECONDS) }

                assertThat(results).hasSize(threads).containsOnly(false)
                assertThat(errors()).hasSize(day + 1)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `확정 거절을 받아도 창은 그대로다`() {
        val now = kst("2026-10-21", "10:00")
        TradeSide.entries.forEach { side ->
            gate.onDefinitiveRejection("KRX-005930", side, now)
            assertThat(gate.mayPlace("KRX-005930", side, now)).isTrue()
        }
    }
}
