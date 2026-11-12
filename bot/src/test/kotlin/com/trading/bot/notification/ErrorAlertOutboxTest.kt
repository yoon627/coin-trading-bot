package com.trading.bot.notification

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.config.ErrorAlertProperties
import com.trading.bot.persistence.ErrorAlertOutboxRepository
import com.trading.bot.persistence.entity.ErrorAlertOutboxEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** 저장소·Discord 는 mock 이고 루프는 손으로 돌린다([ErrorAlertOutbox.runPass]). 실제 DB 는 CI 의 round-trip 시험이 본다. */
class ErrorAlertOutboxTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock(Instant.parse("2026-11-12T12:00:00Z"))
    private val repository = mockk<ErrorAlertOutboxRepository>()
    private val notifier = mockk<DiscordNotifier>(relaxed = true)
    private val props = ErrorAlertProperties(enabled = true, webhookUrl = "https://discord.com/api/webhooks/1/abc")
    private val timings = ErrorAlertOutbox.Timings(insert = Duration.ofMillis(200), stopWait = Duration.ofMillis(200), flush = Duration.ofMillis(300))
    private val outbox = ErrorAlertOutbox(repository, notifier, props, jacksonObjectMapper(), clock, timings)
    private val rows = mutableListOf<ErrorAlertOutboxEntity>()

    init {
        every { notifier.errorAlertPayload(any(), any(), any(), any(), any()) } answers {
            mapOf("embeds" to listOf(mapOf("description" to secondArg<String>(), "timestamp" to arg<Instant?>(4).toString())))
        }
    }

    private fun alert(message: String = "boom") = ErrorAlert.Single("com.trading.bot.engine.X", message, null, 0, clock.now.minusSeconds(30))

    private fun row(id: Long) = rows.single { it.id == id }

    private fun storingRows() {
        every { repository.save(any()) } answers {
            val row = firstArg<ErrorAlertOutboxEntity>().let { it.copy(id = it.id ?: (rows.size + 1L)) }
            rows.removeIf { it.id == row.id }
            rows += row
            Mono.just(row)
        }
        every { repository.findDue(any(), any()) } answers { Flux.fromIterable(rows.filter { it.status == ErrorAlertOutboxEntity.PENDING }.sortedBy { it.id }) }
        every { repository.deleteFinishedBefore(any()) } returns Mono.just(0L)
    }

    @Test
    fun `nothing is queued until the loop runs, so the caller sends directly`() {
        assertFalse(outbox.offer(alert()))
    }

    // appender 는 logback lock 안에서 부른다 — 저장소가 끝나지 않아도 바로 돌아와야 한다.
    @Test
    fun `offering returns at once even when the repository never answers`() {
        every { repository.save(any()) } returns Mono.never()
        outbox.markRunning()

        val started = System.nanoTime()
        assertTrue(outbox.offer(alert()))
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofMillis(100))
    }

    @Test
    fun `a queued alert is stored, sent, and recorded as sent with the event time in the embed`() = runBlocking {
        storingRows()
        val payload = slot<Map<String, Any>>()
        coEvery { notifier.deliverPayload(capture(payload), props.webhookUrl) } returns TradeDelivery.Sent
        outbox.markRunning()
        val a = alert()
        outbox.offer(a)

        outbox.runPass()

        assertEquals(ErrorAlertOutboxEntity.SENT, rows.single().status)
        assertEquals(a.at.toString(), ((payload.captured["embeds"] as List<*>).single() as Map<*, *>)["timestamp"])
    }

    // DB 장애를 알리는 ERROR 는 그 DB 에 남길 수 없다. 멈춘 DB 가 알림을 붙잡지도 않게 한다.
    @Test
    fun `an alert the database cannot take in time is sent directly`() = runBlocking {
        every { repository.save(any()) } returns Mono.never()
        every { repository.findDue(any(), any()) } returns Flux.empty()
        every { repository.deleteFinishedBefore(any()) } returns Mono.just(0L)
        outbox.markRunning()
        outbox.offer(alert())

        outbox.runPass()

        verify(exactly = 1) { notifier.sendErrorPayload(any(), props.webhookUrl) }
        assertEquals(0, outbox.queued())
    }

    @Test
    fun `a retryable failure backs off and stops the pass, and a permanent one fails`() = runBlocking {
        storingRows()
        outbox.markRunning()
        outbox.offer(alert("a"))
        outbox.offer(alert("b"))
        coEvery { notifier.deliverPayload(any(), any()) } returns TradeDelivery.Retryable("429")

        outbox.runPass()

        assertEquals(1, row(1).attempts)
        assertEquals(clock.now.plusSeconds(5), row(1).nextAttemptAt)
        assertEquals(0, row(2).attempts, "다시 보낼 실패면 그 패스를 멈춘다")

        coEvery { notifier.deliverPayload(any(), any()) } returns TradeDelivery.Permanent("404")
        clock.now = clock.now.plusSeconds(10)
        outbox.runPass()
        assertTrue(rows.all { it.status == ErrorAlertOutboxEntity.FAILED })
    }

    @Test
    fun `alerts older than a day are given up on, and with alerts disabled or no error webhook pending rows are skipped`() = runBlocking {
        storingRows()
        rows += ErrorAlertOutboxEntity(id = 1, payload = "{}", nextAttemptAt = clock.now, createdAt = clock.now.minus(Duration.ofHours(25)))
        outbox.runPass()
        assertEquals(ErrorAlertOutboxEntity.FAILED, rows.single().status)

        val disabled = ErrorAlertOutbox(repository, notifier, props.copy(enabled = false), jacksonObjectMapper(), clock, timings)
        rows += ErrorAlertOutboxEntity(id = 2, payload = "{}", nextAttemptAt = clock.now, createdAt = clock.now)
        disabled.runPass()
        assertEquals(ErrorAlertOutboxEntity.SKIPPED, row(2).status)

        // 오류 webhook 이 없으면 전역(체결 알림) webhook 으로 보내지 않는다.
        val noWebhook = ErrorAlertOutbox(repository, notifier, props.copy(webhookUrl = ""), jacksonObjectMapper(), clock, timings)
        rows += ErrorAlertOutboxEntity(id = 3, payload = "{}", nextAttemptAt = clock.now, createdAt = clock.now)
        noWebhook.runPass()
        assertEquals(ErrorAlertOutboxEntity.SKIPPED, row(3).status)
        io.mockk.coVerify(exactly = 0) { notifier.deliverPayload(any(), any()) }
    }

    @Test
    fun `finished rows older than seven days are deleted at most once an hour`() = runBlocking {
        storingRows()
        outbox.runPass()
        clock.now = clock.now.plusSeconds(60)
        outbox.runPass()

        verify(exactly = 1) { repository.deleteFinishedBefore(Instant.parse("2026-11-05T12:00:00Z")) }
    }

    // SENT 를 적지 못해도 이 프로세스에서는 다시 보내지 않는다 — 아니면 DB 가 흔들릴 때 같은 알림이 매 패스 간다.
    @Test
    fun `a sent row whose record fails is not sent again in this process`() = runBlocking {
        storingRows()
        rows += ErrorAlertOutboxEntity(id = 1, payload = "{}", nextAttemptAt = clock.now, createdAt = clock.now)
        coEvery { notifier.deliverPayload(any(), any()) } returns TradeDelivery.Sent
        every { repository.save(match { it.status == ErrorAlertOutboxEntity.SENT }) } returns Mono.error(IllegalStateException("db down")) andThen Mono.fromCallable {
            rows.replaceAll { it.copy(status = ErrorAlertOutboxEntity.SENT) }
            rows.single()
        }

        outbox.runPass()
        outbox.runPass()

        io.mockk.coVerify(exactly = 1) { notifier.deliverPayload(any(), any()) }
        assertEquals(ErrorAlertOutboxEntity.SENT, rows.single().status)
    }

    @Test
    fun `a full queue and a failing insert both fall back to sending directly`() = runBlocking {
        val small = ErrorAlertOutbox(repository, notifier, props, jacksonObjectMapper(), clock, timings.copy(capacity = 1))
        small.markRunning()
        assertTrue(small.offer(alert("a")))
        assertFalse(small.offer(alert("b")), "가득 차면 호출자가 바로 보낸다")

        every { repository.save(any()) } returns Mono.error(IllegalStateException("db down"))
        every { repository.findDue(any(), any()) } returns Flux.empty()
        every { repository.deleteFinishedBefore(any()) } returns Mono.just(0L)
        small.runPass()
        verify(exactly = 1) { notifier.sendErrorPayload(any(), props.webhookUrl) }
    }

    // 종료 때 남은 큐는 2초 안에 DB 에 넣어 다음 기동에 보낸다. 바로 보내지 않는다 — 전역 Netty 자원이 이미 정리됐다.
    @Test
    fun `stopping stores what is left in the queue and sends nothing directly`() {
        storingRows()
        outbox.markRunning()
        outbox.offer(alert("a"))
        outbox.offer(alert("b"))

        outbox.stop()

        assertEquals(2, rows.size)
        assertTrue(rows.all { it.status == ErrorAlertOutboxEntity.PENDING })
        verify(exactly = 0) { notifier.sendErrorPayload(any(), any()) }
        assertFalse(outbox.offer(alert("c")), "멈춘 뒤에는 호출자가 바로 보낸다")
    }

    @Test
    fun `stopping gives up on a database that does not answer within the flush limit`() {
        every { repository.save(any()) } returns Mono.never()
        outbox.markRunning()
        outbox.offer(alert())

        val started = System.nanoTime()
        outbox.stop()

        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(2))
        verify(exactly = 0) { notifier.sendErrorPayload(any(), any()) }
        assertEquals(1, outbox.queued(), "넣지 못한 값은 큐에서 버리지 않는다")
    }

    @Test
    fun `a loop whose last pass is over two minutes old is not alive`() {
        outbox.markRunning()
        assertTrue(outbox.alive())
        clock.now = clock.now.plusSeconds(121)
        assertFalse(outbox.alive())
    }
}
