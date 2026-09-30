package com.trading.bot.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.AppenderBase
import com.trading.bot.config.ErrorAlertProperties
import jakarta.annotation.PreDestroy
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * ERROR 로그를 Discord 로 전달하는 Logback appender (앱 JVM 내부, 새 컨테이너 없음).
 *
 * - opt-in: `discord.error-alert.enabled=true` + webhook-url 설정 시에만 root logger 에 attach.
 * - 무한루프 방지: [DENY_PREFIXES](notification/reactor/netty) 로거 제외 + ThreadLocal reentrancy guard +
 *   appender 안(doAppend)의 실패는 SLF4J 대신 logback `addError`(재귀 차단). 요약 스레드는 doAppend 밖이라 notification
 *   로거 WARN 을 쓴다.
 * - rate limit / 민감정보 마스킹은 ErrorAlertRateLimiter / LogMessageSanitizer 위임. 분당 상한에 걸린 알림은 버리지 않고
 *   상한에 처음 걸린 뒤 [DIGEST_DELAY_MS] 에 요약 1건으로 보낸다(#249) — 요약은 전용 스레드에서 보내 이 appender lock 밖이다.
 * - lifecycle: 이 instance 가 직접 attach 한 경우에만 context 종료 시 detach(다른 context appender 보호). 종료 때 남은
 *   보류는 보내지 않는다 — 종료 중 전송은 도달을 보장할 수 없고, 로그 파일에는 이미 있다.
 * - 한계: ApplicationReadyEvent 이후부터 캡처(기동 실패 에러는 미포함).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // restore(@Order LOWEST)보다 먼저 attach 되어 restore 중 에러가 Discord 에 도달하도록.
class DiscordErrorLogAppender internal constructor(
    private val discordNotifier: DiscordNotifier,
    private val props: ErrorAlertProperties,
    private val rateLimiter: ErrorAlertRateLimiter,
    private val digestExecutor: ScheduledExecutorService,
) : ApplicationListener<ApplicationReadyEvent> {

    // 요약 executor 를 빈으로 주입받지 않는다 — 공용 풀이 들어오면 종료 때 shutdownNow 가 남의 작업까지 멈춘다.
    @Autowired
    constructor(discordNotifier: DiscordNotifier, props: ErrorAlertProperties) :
        this(discordNotifier, props, ErrorAlertRateLimiter(), newDigestExecutor())

    private val reentry = ThreadLocal.withInitial { false }
    @Volatile private var attached = false

    private val appender = object : AppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) = handle(event)
    }

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        if (!props.enabled || props.webhookUrl.isBlank()) return
        val root = rootLogger()
        if (root.getAppender(APPENDER_NAME) != null) return // 다른 context/중복 attach 방지
        appender.context = root.loggerContext
        appender.name = APPENDER_NAME
        appender.start()
        root.addAppender(appender)
        attached = true
    }

    @PreDestroy
    fun detach() {
        try {
            if (!attached) return // 이 instance 가 attach 한 경우에만 제거(다른 context 의 appender 보호)
            rootLogger().detachAppender(appender) // name 이 아닌 실제 instance 로 detach
            appender.stop()
            attached = false
        } finally {
            // attach 여부와 무관하게 전용 스레드를 남기지 않는다. appender 를 뗀 뒤라 그 사이 보류가 거부되지 않는다.
            digestExecutor.shutdownNow()
        }
    }

    /** 요약 예약이 실행하는 곳 — 전용 스레드라 appender lock 밖이다. */
    internal fun flushDigest() {
        val digest = rateLimiter.drainHeld(System.currentTimeMillis()) ?: return
        try {
            discordNotifier.sendErrorDigest(digest, props.webhookUrl)
        } catch (e: Exception) {
            // 요약을 만들다 실패한 경우다 — 전송 실패(429·네트워크)는 비동기라 DiscordNotifier 가 따로 WARN 을 남긴다.
            // notification 로거라 이 WARN 은 알림으로 되돌아오지 않는다(DENY_PREFIXES). addError 는 운영 로그에 안 보인다.
            log.warn(
                "Discord error digest not sent — {} held alerts (+{} overflow occurrences) remain only in the log file: {}",
                digest.alerts.size, digest.overflowByLogger.values.sum(), e.message,
            )
        }
    }

    private fun scheduleDigest() {
        try {
            digestExecutor.schedule(Runnable { flushDigest() }, DIGEST_DELAY_MS, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            // 어떤 이유로 실패하든 요청을 되돌린다 — 안 그러면 요약이 영영 멈추고, 보류된 알림은 개별로도 나가지 않는다.
            rateLimiter.digestNotScheduled()
            if (e !is RejectedExecutionException) throw e
            appender.addError("Discord error digest not scheduled", e) // SLF4J 금지(재귀 방지)
        }
    }

    // 요약 줄 — 마스킹한 메시지의 첫 비지 않은 줄. 예외 클래스를 붙인다: 메시지가 같고 예외만 다른 fingerprint 가 같은 줄로 보이지 않게.
    private fun digestLine(event: ILoggingEvent): String {
        val firstLine = LogMessageSanitizer.sanitize(event.formattedMessage ?: "").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val text = firstLine.truncateForDiscord(DIGEST_SUMMARY_MAX)
        val exception = event.throwableProxy?.className?.substringAfterLast('.') ?: return text
        return "$text [$exception]"
    }

    internal fun handle(event: ILoggingEvent) {
        if (event.level != Level.ERROR) return
        if (DENY_PREFIXES.any { event.loggerName.startsWith(it) }) return // 알림/전송 경로 자기 에러 무시(루프 차단)
        if (reentry.get()) return
        reentry.set(true)
        try {
            // formattedMessage 기준 dedup: 같은 알림 텍스트만 묶고, user/ticker 가 다른 에러는 구분.
            val fingerprint = "${event.loggerName}|${event.formattedMessage}|${event.throwableProxy?.className ?: ""}"
            val decision = rateLimiter.decide(fingerprint, System.currentTimeMillis(), event.loggerName) { digestLine(event) }
            if (decision.scheduleDigest) scheduleDigest()
            if (!decision.allow) return
            val message = LogMessageSanitizer.sanitize(event.formattedMessage ?: "")
            val stack = event.throwableProxy?.let { LogMessageSanitizer.sanitize(renderStack(it)) }
            discordNotifier.sendErrorAlert(event.loggerName, message, stack, decision.suppressedSince, props.webhookUrl)
        } catch (e: Exception) {
            appender.addError("Discord error alert dispatch failed", e) // SLF4J 금지(재귀 방지)
        } finally {
            reentry.remove()
        }
    }

    private fun rootLogger() = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

    private fun renderStack(tp: IThrowableProxy): String {
        val sb = StringBuilder(tp.className)
        tp.message?.let { sb.append(": ").append(it) }
        tp.stackTraceElementProxyArray.take(MAX_STACK_FRAMES).forEach { sb.append("\n  at ").append(it.steAsString) }
        return sb.toString()
    }

    companion object {
        private const val APPENDER_NAME = "DISCORD_ERROR"
        private val DENY_PREFIXES = listOf("com.trading.bot.notification", "reactor.", "io.netty.")
        private const val MAX_STACK_FRAMES = 10
        private const val DIGEST_DELAY_MS = 60_000L
        private const val DIGEST_SUMMARY_MAX = 200
        private val log = LoggerFactory.getLogger(DiscordErrorLogAppender::class.java)

        private fun newDigestExecutor(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "discord-error-digest").apply { isDaemon = true } }
    }
}
