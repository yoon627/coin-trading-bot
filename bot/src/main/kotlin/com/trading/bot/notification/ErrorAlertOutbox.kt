package com.trading.bot.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.trading.bot.config.ErrorAlertProperties
import com.trading.bot.persistence.ErrorAlertOutboxRepository
import com.trading.bot.persistence.entity.ErrorAlertOutboxEntity
import com.trading.bot.persistence.entity.ErrorAlertOutboxEntity.Companion.FAILED
import com.trading.bot.persistence.entity.ErrorAlertOutboxEntity.Companion.PENDING
import com.trading.bot.persistence.entity.ErrorAlertOutboxEntity.Companion.SENT
import com.trading.bot.persistence.entity.ErrorAlertOutboxEntity.Companion.SKIPPED
import jakarta.annotation.PreDestroy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** appender 가 outbox 에 맡기는 ERROR 알림. [at] 은 ERROR 가 난 시각(요약은 보류를 비운 시각)이다. */
sealed interface ErrorAlert {
    val at: Instant

    data class Single(val logger: String, val message: String, val stack: String?, val suppressedSince: Int, override val at: Instant) : ErrorAlert

    data class Digest(val digest: ErrorAlertRateLimiter.Digest, override val at: Instant) : ErrorAlert
}

/**
 * Discord ERROR 알림을 DB outbox 에 남기고 보낸다. appender 는 logback lock 안이라 메모리 큐에 넣기만 하고, 한 루프가 큐를 DB 에
 * 넣고(넣지 못하거나 시간을 넘기면 바로 보낸다) 보낼 때가 된 행을 보내 결과를 적는다. 발송 규칙은 [TradeNotificationDispatcher] 와 같다.
 * 루프가 살아 있지 않으면 [offer] 가 false 라 appender 가 바로 보낸다 — 멈춘 루프가 ERROR 알림 경로를 조용히 끊지 않게 한다.
 * 이 클래스의 로그는 notification 로거라 알림으로 되돌아오지 않는다. 그래도 ERROR 로는 남기지 않는다.
 */
@Component
class ErrorAlertOutbox internal constructor(
    private val repository: ErrorAlertOutboxRepository,
    private val notifier: DiscordNotifier,
    private val props: ErrorAlertProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val timings: Timings,
) {
    internal data class Timings(
        val insert: Duration = Duration.ofSeconds(5),
        val stopWait: Duration = Duration.ofSeconds(1),
        val flush: Duration = Duration.ofSeconds(1),
        // 한 발송은 429 Retry-After(최대 30초)를 두 번 기다려 75초까지 걸린다. 그보다 짧으면 backoff 를 적기 전에 끊긴다.
        val pass: Duration = Duration.ofSeconds(90),
        val liveness: Duration = Duration.ofSeconds(120),
        val capacity: Int = 1_000,
    )

    @Autowired
    constructor(repository: ErrorAlertOutboxRepository, notifier: DiscordNotifier, props: ErrorAlertProperties, objectMapper: ObjectMapper) :
        this(repository, notifier, props, objectMapper, Clock.systemUTC(), Timings())

    private val log = LoggerFactory.getLogger(javaClass)
    private val queue = ArrayBlockingQueue<ErrorAlert>(timings.capacity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("error-alert-outbox"))
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)
    @Volatile private var running = false
    @Volatile private var lastPassAt: Instant = Instant.EPOCH
    private var loopJob: Job? = null
    private var lastCleanupAt: Instant = Instant.EPOCH

    // SENT 기록이 실패해도 이 프로세스에서는 다시 보내지 않는다.
    private val sentThisProcess: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** logback lock 안에서 불린다 — 기다리지 않는다. false 면 호출자가 바로 보낸다. */
    fun offer(alert: ErrorAlert): Boolean {
        if (!alive() || !queue.offer(alert)) return false
        wakeups.trySend(Unit)
        return true
    }

    internal fun alive(): Boolean = running && Duration.between(lastPassAt, clock.instant()) <= timings.liveness

    internal fun queued(): Int = queue.size

    internal fun markRunning() {
        lastPassAt = clock.instant()
        running = true
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        markRunning()
        loopJob = scope.launch {
            try {
                reportBacklog()
                loop()
            } finally {
                running = false
            }
        }
    }

    /**
     * appender 가 먼저 떨어진 뒤(그 빈이 이 빈에 의존한다) 불린다. 루프를 멈추고 기다린 다음 남은 큐를 시간 안에 DB 에 넣는다 —
     * 다음 기동에 간다. 넣지 못한 것은 바로 보내지 않는다(전역 Netty 자원이 이미 정리돼 닿지 않는다).
     */
    @PreDestroy
    fun stop() {
        running = false
        runBlocking {
            withTimeoutOrNull(timings.stopWait.toMillis()) { loopJob?.cancelAndJoin() }
            // 루프가 아직 돌면 같은 큐를 둘이 비워 한 알림을 두 번 넣고 다른 알림을 버린다.
            if (loopJob?.isCompleted == false) return@runBlocking
            withTimeoutOrNull(timings.flush.toMillis()) {
                while (true) {
                    val alert = queue.peek() ?: break
                    if (!store(alert)) break
                    queue.poll()
                }
            }
        }
        scope.cancel()
        if (queue.isNotEmpty()) log.warn("{} ERROR alerts were not stored at shutdown — they remain only in the log file", queue.size)
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            lastPassAt = clock.instant()
            try {
                withTimeout(timings.pass.toMillis()) { runPass() }
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                log.warn("Error alert pass did not finish — retrying on the next pass: {}", e.message)
            } catch (e: Throwable) {
                // 루프가 끝나면 큐에 든 알림이 종료까지 묶인다. ERROR 로 남기지 않는다(알림이 되돌아온다).
                log.warn("Error alert pass failed — retrying on the next pass: {}", e.toString())
            }
            withTimeoutOrNull(INTERVAL.toMillis()) { wakeups.receive() }
        }
    }

    // 멈춘 조회가 첫 패스를 막지 않게 한다.
    private suspend fun reportBacklog() {
        try {
            withTimeoutOrNull(timings.insert.toMillis()) {
                log.info("Error alert outbox: pending={}, failed={}", repository.countByStatus(PENDING).awaitSingle(), repository.countByStatus(FAILED).awaitSingle())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not count the error alert outbox: {}", e.message)
        }
    }

    internal suspend fun runPass() {
        storeQueued()
        deliverDue()
        cleanUp()
    }

    // 꺼낸 값은 저장했거나 바로 보낸 뒤에만 큐에서 지운다.
    private suspend fun storeQueued() {
        while (true) {
            val alert = queue.peek() ?: return
            if (!store(alert)) notifier.sendErrorPayload(payloadOf(alert), props.webhookUrl)
            queue.poll()
        }
    }

    /** 커밋됐는데 응답만 늦었으면 false 라 바로 보낸 알림과 함께 두 번 갈 수 있다. */
    private suspend fun store(alert: ErrorAlert): Boolean =
        try {
            val now = clock.instant()
            val row = ErrorAlertOutboxEntity(payload = objectMapper.writeValueAsString(payloadOf(alert)), nextAttemptAt = now, createdAt = now)
            withTimeoutOrNull(timings.insert.toMillis()) { repository.save(row).awaitSingle() } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not store an error alert — sending it directly: {}", e.message)
            false
        }

    private fun payloadOf(alert: ErrorAlert): Map<String, Any> = when (alert) {
        is ErrorAlert.Single -> notifier.errorAlertPayload(alert.logger, alert.message, alert.stack, alert.suppressedSince, alert.at)
        is ErrorAlert.Digest -> notifier.errorDigestPayload(alert.digest, alert.at)
    }

    /** 다시 보낼 실패(429·5xx·연결 오류)가 나면 그 패스를 멈춘다 — 장애 중에 남은 행을 하나씩 기다리지 않는다. */
    private suspend fun deliverDue() {
        for (row in db { repository.findDue(clock.instant(), BATCH).collectList().awaitSingle() }) {
            if (!deliver(row)) return
        }
    }

    private suspend fun deliver(row: ErrorAlertOutboxEntity): Boolean {
        val id = requireNotNull(row.id)
        val now = clock.instant()
        if (id in sentThisProcess) return true.also { save(sent(row, now)) }
        if (Duration.between(row.createdAt, now) > RETRY_WINDOW) return true.also { fail(row, row.lastError ?: "not delivered within $RETRY_WINDOW") }
        // 오류 webhook 이 없으면 보내지 않는다 — 전역 webhook 은 체결 알림 채널이다.
        if (!props.enabled || props.webhookUrl.isBlank()) return true.also { save(row.copy(status = SKIPPED)) }
        val payload = try {
            objectMapper.readValue<Map<String, Any>>(row.payload)
        } catch (e: Exception) {
            return true.also { fail(row.copy(attempts = row.attempts + 1), "unreadable payload: ${e.javaClass.simpleName}") }
        }
        return when (val result = notifier.deliverPayload(payload, props.webhookUrl)) {
            TradeDelivery.Sent -> true.also {
                sentThisProcess += id
                save(sent(row, clock.instant()))
            }
            TradeDelivery.Skipped -> true.also { save(row.copy(status = SKIPPED, attempts = row.attempts + 1)) }
            is TradeDelivery.Permanent -> true.also { fail(row.copy(attempts = row.attempts + 1), result.error) }
            is TradeDelivery.Retryable -> false.also {
                val attempts = row.attempts + 1
                save(row.copy(attempts = attempts, nextAttemptAt = now.plus(TradeNotificationDispatcher.backoff(attempts)), lastError = result.error))
            }
        }
    }

    private fun sent(row: ErrorAlertOutboxEntity, at: Instant) = row.copy(status = SENT, attempts = row.attempts + 1, sentAt = at, lastError = null)

    private suspend fun fail(row: ErrorAlertOutboxEntity, error: String) {
        if (save(row.copy(status = FAILED, lastError = error))) {
            log.warn("Gave up on error alert {} after {} attempts: {}", row.id, row.attempts, error)
        }
    }

    private suspend fun save(row: ErrorAlertOutboxEntity): Boolean =
        try {
            db { repository.save(row).awaitSingle() }
            true
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            log.warn("Recording error alert {} as {} timed out — retrying on the next pass", row.id, row.status)
            false
        } catch (e: Exception) {
            log.warn("Could not record error alert {} as {} — retrying on the next pass: {}", row.id, row.status, e.message)
            false
        }

    // 폭주 때 하루 수천 행이 쌓일 수 있어 끝난 행을 지운다.
    private suspend fun cleanUp() {
        val now = clock.instant()
        if (Duration.between(lastCleanupAt, now) < CLEANUP_EVERY) return
        lastCleanupAt = now
        db { repository.deleteFinishedBefore(now.minus(KEEP_FINISHED)).awaitSingle() }
    }

    // DB 가 멈추면 패스가 90초 상한까지 묶이고, 그동안 생존 표시는 살아 있어 새 알림이 큐에서 기다린다.
    private suspend fun <T> db(block: suspend () -> T): T = withTimeout(timings.insert.toMillis()) { block() }

    private companion object {
        const val BATCH = 20
        val INTERVAL: Duration = Duration.ofSeconds(5)
        val RETRY_WINDOW: Duration = TradeNotificationDispatcher.RETRY_WINDOW
        val CLEANUP_EVERY: Duration = Duration.ofHours(1)
        val KEEP_FINISHED: Duration = Duration.ofDays(7)
    }
}
