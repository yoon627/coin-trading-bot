package com.trading.bot.notification

import com.trading.bot.persistence.TradeNotificationOutboxRepository
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity
import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity.Companion.FAILED
import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity.Companion.PENDING
import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity.Companion.SENT
import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity.Companion.SKIPPED
import com.trading.bot.persistence.entity.UserEntity
import jakarta.annotation.PreDestroy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * outbox 의 미발송 체결 알림을 보내고 결과를 적는다. 커밋 직후 깨우기와 주기 실행을 한 루프가 받아 패스가 겹치지 않는다
 * — 겹치면 같은 행을 두 번 보낸다. 인스턴스는 하나라 행 잠금은 두지 않는다.
 * 보낸 직후 SENT 를 적기 전에 프로세스가 끝나면 다음 기동에 한 번 더 간다(알림의 주문번호로 구분한다).
 */
@Component
class TradeNotificationDispatcher internal constructor(
    private val outboxRepository: TradeNotificationOutboxRepository,
    private val userRepository: UserRepository,
    private val balances: TradeBalanceLookup,
    private val notifier: DiscordNotifier,
    private val clock: Clock,
) {
    @Autowired
    constructor(
        outboxRepository: TradeNotificationOutboxRepository,
        userRepository: UserRepository,
        balances: TradeBalanceLookup,
        notifier: DiscordNotifier,
    ) : this(outboxRepository, userRepository, balances, notifier, Clock.systemUTC())

    private val log = LoggerFactory.getLogger(javaClass)

    // 스케줄러 풀(2개)을 쓰면 긴 발송이 워치독·엔진 감시를 막는다.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("trade-notification-outbox"))
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)

    // SENT 기록이 실패해도 이 프로세스에서는 다시 보내지 않는다.
    private val sentThisProcess: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            reportBacklog()
            loop()
        }
    }

    @EventListener(TradeNotificationQueued::class)
    fun wake() {
        wakeups.trySend(Unit)
    }

    // 엔진(SmartLifecycle)이 멈춘 뒤 불린다. Discord 응답을 기다리지 않는다 — 못 보낸 행은 다음 기동에 간다.
    @PreDestroy
    fun stop() {
        scope.cancel()
    }

    // 루프가 끝나면 다음 재시작까지 아무것도 보내지 않으므로, 멈추라는 취소가 아니면 무엇이 나와도 다음 패스로 간다.
    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            try {
                runPass()
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                log.warn("Trade notification pass was cancelled from inside — retrying on the next pass: {}", e.message)
            } catch (e: Exception) {
                // DB 장애면 5초마다 나므로 ERROR(Discord)로 올리지 않는다. 그 장애는 다른 곳이 알린다.
                log.warn("Trade notification pass failed — retrying on the next pass: {}", e.message)
            } catch (e: Throwable) {
                log.error("Trade notification pass failed — retrying on the next pass", e)
            }
            withTimeoutOrNull(INTERVAL.toMillis()) { wakeups.receive() }
        }
    }

    private suspend fun reportBacklog() {
        try {
            val pending = outboxRepository.countByStatus(PENDING).awaitSingle()
            val failed = outboxRepository.countByStatus(FAILED).awaitSingle()
            log.info("Trade notification outbox: pending={}, failed={}", pending, failed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not count the trade notification outbox: {}", e.message)
        }
    }

    /**
     * 보낼 때가 된 행을 오래된 순으로 보낸다. 다시 보낼 실패(429·5xx·연결 오류)가 나면 그 패스를 멈춘다 — 장애 중에 남은 행을
     * 하나씩 기다리면 패스가 몇 분씩 걸리고, 쌓인 알림을 몰아 보내면 다시 429 다.
     */
    internal suspend fun runPass() {
        val rows = outboxRepository.findDue(clock.instant(), BATCH).collectList().awaitSingle()
        for (row in rows) {
            if (!deliver(row)) break
        }
    }

    /** false 면 이번 패스를 멈춘다. */
    private suspend fun deliver(row: TradeNotificationOutboxEntity): Boolean {
        val id = requireNotNull(row.id)
        val now = clock.instant()
        if (id in sentThisProcess) {
            save(sent(row, now))
            return true
        }
        if (Duration.between(row.createdAt, now) > RETRY_WINDOW) {
            fail(row, row.lastError ?: "not delivered within $RETRY_WINDOW")
            return true
        }
        val payload = try {
            TradeNotificationPayload.fromJson(row.payload)
        } catch (e: Exception) {
            fail(row.copy(attempts = row.attempts + 1), "unreadable payload: ${e.javaClass.simpleName}")
            return true
        }
        val user = try {
            userRepository.findById(row.userId).awaitSingleOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            retry(row, now, "user lookup failed: ${e.javaClass.simpleName}")
            return false
        }
        // 늦게 가는 알림에 지금 잔고를 실으면 체결 직후 잔고로 읽힌다.
        val balance = if (user != null && Duration.between(row.createdAt, now) <= FRESH_BALANCE) balanceOf(user, row.exchange) else null
        return when (val result = notifier.deliverTrade(payload, balance, user?.discordWebhookUrl, user?.username)) {
            TradeDelivery.Sent -> {
                sentThisProcess += id
                save(sent(row, clock.instant()))
                true
            }
            TradeDelivery.Skipped -> {
                save(row.copy(status = SKIPPED, attempts = row.attempts + 1))
                true
            }
            is TradeDelivery.Permanent -> {
                fail(row.copy(attempts = row.attempts + 1), result.error)
                true
            }
            is TradeDelivery.Retryable -> {
                retry(row, now, result.error)
                false
            }
        }
    }

    private fun sent(row: TradeNotificationOutboxEntity, at: Instant) =
        row.copy(status = SENT, attempts = row.attempts + 1, sentAt = at, lastError = null)

    private suspend fun retry(row: TradeNotificationOutboxEntity, now: Instant, error: String) {
        val attempts = row.attempts + 1
        save(row.copy(attempts = attempts, nextAttemptAt = now.plus(backoff(attempts)), lastError = error))
    }

    private suspend fun fail(row: TradeNotificationOutboxEntity, error: String) {
        if (save(row.copy(status = FAILED, lastError = error))) {
            val payload = runCatching { TradeNotificationPayload.fromJson(row.payload) }.getOrNull()
            log.error(
                "체결 알림을 보내지 못했다 — {} {} (주문 {}, outbox {}, 시도 {}회): {}",
                payload?.ticker ?: "?", payload?.side ?: "?", payload?.exchangeOrderId ?: "-", row.id, row.attempts, error,
            )
        }
    }

    private suspend fun save(row: TradeNotificationOutboxEntity): Boolean =
        try {
            outboxRepository.save(row).awaitSingle()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not record trade notification {} as {} — retrying on the next pass: {}", row.id, row.status, e.message)
            false
        }

    // 잔고는 덤이라 실패해도 알림은 간다.
    private suspend fun balanceOf(user: UserEntity, exchange: String): Double? =
        try {
            balances.krwBalance(user, exchange)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    internal companion object {
        val INTERVAL: Duration = Duration.ofSeconds(5)
        const val BATCH = 20
        // 시도 횟수가 아니라 시간으로 끊는다. 몇 시간짜리 Discord 장애에도 알림을 잃지 않는다.
        val RETRY_WINDOW: Duration = Duration.ofHours(24)
        val FRESH_BALANCE: Duration = Duration.ofMinutes(10)
        private val BACKOFF_BASE: Duration = Duration.ofSeconds(5)
        private val BACKOFF_MAX: Duration = Duration.ofMinutes(10)

        fun backoff(attempts: Int): Duration {
            val shift = (attempts - 1).coerceIn(0, 20)
            val delay = BACKOFF_BASE.multipliedBy(1L shl shift)
            return if (delay > BACKOFF_MAX) BACKOFF_MAX else delay
        }
    }
}
