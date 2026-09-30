package com.trading.bot.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import com.trading.bot.config.ErrorAlertProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/** 분당 상한에 걸린 알림의 요약 예약·전송. 예약은 캡처해 손으로 실행한다 — 실시간 지연을 기다리면 "정확히 1회"를 증명할 수 없다. */
class DiscordErrorLogAppenderTest {

    private val notifier = mockk<DiscordNotifier>(relaxed = true)
    private val props = ErrorAlertProperties(enabled = true, webhookUrl = "https://discord.com/api/webhooks/1/abc")
    private val executor = mockk<ScheduledExecutorService>(relaxed = true)
    private val scheduled = mutableListOf<Pair<Runnable, Long>>()

    @BeforeEach
    fun captureSchedules() {
        every { executor.schedule(any<Runnable>(), any(), any()) } answers {
            scheduled += firstArg<Runnable>() to thirdArg<TimeUnit>().toMillis(secondArg())
            mockk(relaxed = true)
        }
    }

    private fun appender(limiter: ErrorAlertRateLimiter = ErrorAlertRateLimiter()) =
        DiscordErrorLogAppender(notifier, props, limiter, executor)

    // notification 패키지 로거는 appender 가 무시하므로 엔진 로거로 찍는다.
    private fun error(message: String, logger: String = "com.trading.bot.engine.PositionManager") =
        LoggingEvent().apply {
            level = Level.ERROR
            loggerName = logger
            this.message = message
        }

    @Test
    fun `분당 상한을 넘은 새 에러는 개별로 나가지 않고 60초 뒤 요약 1건에 실린다`() {
        val appender = appender()
        (1..5).forEach { appender.handle(error("장애 $it")) }
        appender.handle(error("[HALT] KRW-XRP pending reconcile failed 5 times"))
        appender.handle(error("봇 미복원: 1개 유저 복원 실패", logger = "com.trading.bot.engine.UserTradingManager"))

        verify(exactly = 5) { notifier.sendErrorAlert(any(), any(), any(), any(), any()) }
        assertEquals(listOf(60_000L), scheduled.map { it.second }) // 같은 창의 두 번째 보류는 다시 예약하지 않는다
        verify(exactly = 0) { notifier.sendErrorDigest(any(), any()) }

        scheduled.single().first.run()

        val digest = slot<ErrorAlertRateLimiter.Digest>()
        verify(exactly = 1) { notifier.sendErrorDigest(capture(digest), props.webhookUrl) }
        assertEquals(
            listOf("[HALT] KRW-XRP pending reconcile failed 5 times", "봇 미복원: 1개 유저 복원 실패"),
            digest.captured.alerts.map { it.summary },
        )
    }

    @Test
    fun `요약을 보낸 뒤의 보류는 다시 예약하고 보류가 없으면 보내지 않는다`() {
        val appender = appender(ErrorAlertRateLimiter(globalPerMinute = 1))
        appender.handle(error("a"))
        appender.handle(error("b"))
        scheduled.single().first.run()
        appender.handle(error("c")) // 같은 창 — 다시 보류된다
        assertEquals(2, scheduled.size)

        scheduled.last().first.run()
        appender.flushDigest() // 보류 없음
        verify(exactly = 2) { notifier.sendErrorDigest(any(), any()) }
    }

    @Test
    fun `예약이 실패하면 다음 보류가 다시 예약하고 앞의 보류도 그 요약에 실린다`() {
        every { executor.schedule(any<Runnable>(), any(), any()) } throws
            RejectedExecutionException("shut down") andThenThrows IllegalStateException("boom") andThenAnswer {
                scheduled += firstArg<Runnable>() to thirdArg<TimeUnit>().toMillis(secondArg())
                mockk(relaxed = true)
            }
        val appender = appender(ErrorAlertRateLimiter(globalPerMinute = 1))
        appender.handle(error("a"))
        appender.handle(error("b")) // 예약 거부
        appender.handle(error("c")) // 예약 중 다른 예외
        appender.handle(error("d"))
        scheduled.single().first.run()

        val digest = slot<ErrorAlertRateLimiter.Digest>()
        verify(exactly = 1) { notifier.sendErrorDigest(capture(digest), any()) }
        assertEquals(listOf("b", "c", "d"), digest.captured.alerts.map { it.summary })
    }

    @Test
    fun `요약 줄은 마스킹한 첫 비지 않은 줄을 200자로 자르고 예외 클래스를 붙인다`() {
        val appender = appender(ErrorAlertRateLimiter(globalPerMinute = 1))
        appender.handle(error("창을 채운다"))
        appender.handle(
            error("Upbit auth failed: Bearer abc.def-123\nsecond line").apply {
                setThrowableProxy(ThrowableProxy(IllegalStateException("x")))
            },
        )
        appender.handle(error("\n" + "y".repeat(300)))
        scheduled.single().first.run()

        val digest = slot<ErrorAlertRateLimiter.Digest>()
        verify { notifier.sendErrorDigest(capture(digest), any()) }
        assertEquals(
            listOf("Upbit auth failed: Bearer *** [IllegalStateException]", "y".repeat(199) + "…"),
            digest.captured.alerts.map { it.summary },
        )
    }

    @Test
    fun `종료는 붙어 있지 않아도 executor 를 멈추고 남은 보류를 그 자리에서 보내지 않는다`() {
        val appender = appender(ErrorAlertRateLimiter(globalPerMinute = 1))
        appender.handle(error("a"))
        appender.handle(error("b"))

        appender.detach() // ApplicationReadyEvent 전이라 attach 된 적 없다

        verify { executor.shutdownNow() }
        verify(exactly = 0) { notifier.sendErrorDigest(any(), any()) }
    }

    @Test
    fun `Spring 은 빈 두 개만 받는 생성자로 appender 를 만든다`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ctx.registerBean(DiscordNotifier::class.java, Supplier { notifier })
            ctx.registerBean(ErrorAlertProperties::class.java, Supplier { props })
            ctx.register(DiscordErrorLogAppender::class.java)
            ctx.refresh()
            assertNotNull(ctx.getBean(DiscordErrorLogAppender::class.java))
        }
    }
}
