package com.trading.bot.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.persistence.TradeNotificationOutboxRepository
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity
import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity.Companion.FAILED
import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity.Companion.PENDING
import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity.Companion.SENT
import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity.Companion.SKIPPED
import com.trading.bot.persistence.entity.UserEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** outbox 의 미발송 체결 알림을 보내고 결과를 적는 규칙. 저장소는 메모리 위의 가짜다. */
class TradeNotificationDispatcherTest {

    private val start = Instant.parse("2026-11-10T00:00:00Z")
    private var now = start
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private val rows = linkedMapOf<Long, TradeNotificationOutboxEntity>()
    private val saveFailures = AtomicInteger(0)
    private val outbox = mockk<TradeNotificationOutboxRepository> {
        every { findDue(any(), any()) } answers {
            val at = firstArg<Instant>()
            Flux.fromIterable(
                rows.values.filter { it.status == PENDING && !it.nextAttemptAt.isAfter(at) }
                    .sortedWith(compareBy({ it.nextAttemptAt }, { it.id })).take(secondArg()),
            )
        }
        every { save(any()) } answers {
            if (saveFailures.getAndUpdate { maxOf(0, it - 1) } > 0) return@answers Mono.error(IllegalStateException("db blip"))
            val row = firstArg<TradeNotificationOutboxEntity>()
            rows[row.id!!] = row
            Mono.just(row)
        }
        every { countByStatus(any()) } answers { Mono.just(rows.values.count { it.status == firstArg<String>() }.toLong()) }
    }

    private val user = UserEntity(id = 1L, username = "owner", password = "p", discordWebhookUrl = "https://discord.com/api/webhooks/1/user-token")
    private val users = mockk<UserRepository> { every { findById(1L) } returns Mono.just(user) }
    private var balanceLookups = 0
    private var balance: () -> Double? = { 900000.0 }
    private val balances = TradeBalanceLookup { _, _ -> balanceLookups++; balance() }
    private val notifier = mockk<DiscordNotifier>()
    private val dispatcher = TradeNotificationDispatcher(outbox, users, balances, notifier, clock)

    private val logger = LoggerFactory.getLogger(TradeNotificationDispatcher::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setup() {
        logger.addAppender(logs)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(logs)
        dispatcher.stop()
    }

    private fun payload(orderId: String) = TradeNotificationPayload(
        ticker = "KRW-BTC", side = "BUY", price = 50_000_000.0, totalAmount = 100_000.0, exchangeOrderId = orderId, createdAt = "2026-11-10T09:00",
    )

    private fun queue(id: Long, createdAt: Instant = start) {
        rows[id] = TradeNotificationOutboxEntity(
            id = id, tradeExecutionId = id, userId = 1L, exchange = "UPBIT",
            payload = jacksonObjectMapper().writeValueAsString(payload("o-$id")), nextAttemptAt = createdAt, createdAt = createdAt,
        )
    }

    private fun delivers(vararg results: TradeDelivery) {
        coEvery { notifier.deliverTrade(any(), any(), any(), any()) } returnsMany results.toList()
    }

    private fun pass() = runBlocking { dispatcher.runPass() }

    private fun errors() = logs.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }

    @Test
    fun `unsent rows go out oldest first with the user's webhook and are marked sent`() {
        queue(2)
        queue(1)
        delivers(TradeDelivery.Sent, TradeDelivery.Sent)

        pass()

        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            notifier.deliverTrade(match { it.exchangeOrderId == "o-1" }, 900000.0, "https://discord.com/api/webhooks/1/user-token", "owner")
            notifier.deliverTrade(match { it.exchangeOrderId == "o-2" }, any(), any(), any())
        }
        assertTrue(rows.values.all { it.status == SENT && it.sentAt == now })
    }

    // 깨우기와 주기 실행이 겹치면 같은 행을 두 번 보낸다.
    @Test
    fun `a wake during a pass does not send the same row twice`() = runBlocking {
        queue(1)
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { notifier.deliverTrade(any(), any(), any(), any()) } coAnswers {
            inFlight.complete(Unit)
            release.await()
            TradeDelivery.Sent
        }

        dispatcher.start()
        withTimeout(5_000) { inFlight.await() }
        repeat(3) { dispatcher.wake() }
        release.complete(Unit)
        withTimeout(5_000) { while (rows.getValue(1).status != SENT) kotlinx.coroutines.delay(10) }
        dispatcher.wake()
        kotlinx.coroutines.delay(200)

        coVerify(exactly = 1) { notifier.deliverTrade(any(), any(), any(), any()) }
    }

    @Test
    fun `a row whose sent mark failed is not sent again by this process`() {
        queue(1)
        delivers(TradeDelivery.Sent)
        saveFailures.set(1)

        pass()
        pass()

        coVerify(exactly = 1) { notifier.deliverTrade(any(), any(), any(), any()) }
        assertEquals(SENT, rows.getValue(1).status)
    }

    @Test
    fun `a retryable failure backs off and keeps trying until a day has passed`() {
        queue(1)
        delivers(TradeDelivery.Retryable("503"), TradeDelivery.Retryable("503"))

        pass()
        assertEquals(PENDING, rows.getValue(1).status)
        assertEquals(start.plusSeconds(5), rows.getValue(1).nextAttemptAt)
        pass() // 아직 때가 아니다
        coVerify(exactly = 1) { notifier.deliverTrade(any(), any(), any(), any()) }

        now = start.plusSeconds(5)
        pass()
        assertEquals(2, rows.getValue(1).attempts)
        assertEquals(now.plusSeconds(10), rows.getValue(1).nextAttemptAt)
        assertTrue(errors().isEmpty())

        now = start.plus(Duration.ofHours(25))
        rows[1] = rows.getValue(1).copy(nextAttemptAt = now)
        pass()
        assertEquals(FAILED, rows.getValue(1).status)
        assertEquals(1, errors().size, errors().toString())
        assertTrue(errors().single().contains("o-1"), errors().single())
    }

    // 없어진 webhook(404)은 하루 동안 다시 보내도 소용없다.
    @Test
    fun `a permanent failure fails at once and reports once`() {
        queue(1)
        delivers(TradeDelivery.Permanent("404 Not Found from POST https://discord.com/api/webhooks/1/***"))

        pass()
        pass()

        assertEquals(FAILED, rows.getValue(1).status)
        assertEquals(1, errors().size)
        coVerify(exactly = 1) { notifier.deliverTrade(any(), any(), any(), any()) }
    }

    @Test
    fun `a retryable failure stops the pass so an outage does not stall every row`() {
        queue(1)
        queue(2)
        delivers(TradeDelivery.Retryable("429"))

        pass()

        coVerify(exactly = 1) { notifier.deliverTrade(any(), any(), any(), any()) }
        assertEquals(PENDING, rows.getValue(2).status)
        assertEquals(0, rows.getValue(2).attempts)
    }

    // 사용자 행이 없어도 전역 webhook 으로 보낼 수 있어야 한다(webhook 이 없으면 notifier 가 전역으로 보낸다).
    @Test
    fun `a missing user still gets the notification through the global webhook`() {
        queue(1)
        every { users.findById(1L) } returns Mono.empty()
        delivers(TradeDelivery.Sent)

        pass()

        coVerify { notifier.deliverTrade(any(), null, null, null) }
        assertEquals(SENT, rows.getValue(1).status)
    }

    @Test
    fun `a row already sent by this process is recorded like a normal send`() {
        queue(1)
        rows[1] = rows.getValue(1).copy(lastError = "503")
        delivers(TradeDelivery.Sent)
        saveFailures.set(1)

        pass()
        pass()

        assertEquals(SENT, rows.getValue(1).status)
        assertEquals(1, rows.getValue(1).attempts)
        assertNull(rows.getValue(1).lastError)
    }

    @Test
    fun `nothing configured anywhere is recorded as skipped`() {
        queue(1)
        delivers(TradeDelivery.Skipped)

        pass()

        assertEquals(SKIPPED, rows.getValue(1).status)
        assertTrue(errors().isEmpty())
    }

    // 늦게 가는 알림에 지금 잔고를 실으면 체결 직후 잔고로 읽힌다.
    @Test
    fun `a late notification goes out without a balance, and a failed balance lookup does not hold it back`() {
        queue(1, createdAt = start.minus(Duration.ofMinutes(11)))
        queue(2)
        balance = { throw IllegalStateException("upbit down") }
        delivers(TradeDelivery.Sent, TradeDelivery.Sent)

        pass()

        coVerify { notifier.deliverTrade(match { it.exchangeOrderId == "o-1" }, null, any(), any()) }
        coVerify { notifier.deliverTrade(match { it.exchangeOrderId == "o-2" }, null, any(), any()) }
        assertEquals(1, balanceLookups)
        assertTrue(rows.values.all { it.status == SENT })
    }

    @Test
    fun `an unreadable row fails without blocking the next one`() {
        queue(1)
        rows[1] = rows.getValue(1).copy(payload = "{not json")
        queue(2)
        delivers(TradeDelivery.Sent)

        pass()

        assertEquals(FAILED, rows.getValue(1).status)
        assertEquals(SENT, rows.getValue(2).status)
    }

    // 재시작 뒤 남은 미발송을 보낸다(기동하면 먼저 한 패스).
    @Test
    fun `rows left from before a restart are sent when the dispatcher starts`() = runBlocking {
        queue(1)
        delivers(TradeDelivery.Sent)

        dispatcher.start()
        withTimeout(5_000) { while (rows.getValue(1).status != SENT) kotlinx.coroutines.delay(10) }

        assertTrue(logs.list.any { it.level == Level.INFO && it.formattedMessage.contains("pending=1") })
        assertNull(rows.getValue(1).lastError)
        assertFalse(rows.getValue(1).payload.contains("webhook"))
    }
}
