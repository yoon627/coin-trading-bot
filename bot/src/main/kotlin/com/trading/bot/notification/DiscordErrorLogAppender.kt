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
 * ERROR 로그를 Discord 로 보내는 Logback appender. ApplicationReadyEvent 이후에 붙으므로 기동 실패 에러는 보내지 않는다.
 * appender 안에서 SLF4J 로 로깅하면 자기 자신으로 되돌아오므로 실패는 logback addError 로 남긴다.
 */
@Component
// 청산 파라미터 기동 보고와 포지션 복원보다 먼저 붙어야 그 에러가 Discord 로 나간다.
@Order(Ordered.HIGHEST_PRECEDENCE)
class DiscordErrorLogAppender internal constructor(
    private val discordNotifier: DiscordNotifier,
    private val props: ErrorAlertProperties,
    private val rateLimiter: ErrorAlertRateLimiter,
    private val digestExecutor: ScheduledExecutorService,
) : ApplicationListener<ApplicationReadyEvent> {

    // 공용 풀이 주입되면 종료 때 shutdownNow 가 다른 작업까지 멈추므로 executor 는 직접 만든다.
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
        if (root.getAppender(APPENDER_NAME) != null) return
        appender.context = root.loggerContext
        appender.name = APPENDER_NAME
        appender.start()
        root.addAppender(appender)
        attached = true
    }

    @PreDestroy
    fun detach() {
        try {
            // 같은 이름의 다른 context appender 를 떼지 않도록 직접 붙인 instance 만 뗀다.
            if (!attached) return
            rootLogger().detachAppender(appender)
            appender.stop()
            attached = false
        } finally {
            // 종료 중 전송은 도달을 보장할 수 없고 로그 파일에 이미 있으므로 남은 보류는 버린다.
            // appender 를 먼저 뗐으므로 그 사이 새 보류가 거부되지 않는다.
            digestExecutor.shutdownNow()
        }
    }

    // 전용 스레드에서 돌아 appender lock 밖이므로 SLF4J 를 써도 된다.
    internal fun flushDigest() {
        val digest = rateLimiter.drainHeld(System.currentTimeMillis()) ?: return
        try {
            discordNotifier.sendErrorDigest(digest, props.webhookUrl)
        } catch (e: Exception) {
            // notification 로거라 이 WARN 은 알림으로 되돌아오지 않는다.
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
            // 요청을 되돌리지 않으면 요약이 다시 예약되지 않아 보류된 알림이 영영 나가지 않는다.
            rateLimiter.digestNotScheduled()
            if (e !is RejectedExecutionException) throw e
            appender.addError("Discord error digest not scheduled", e)
        }
    }

    // 메시지가 같고 예외만 다른 알림이 같은 줄로 보이지 않게 예외 클래스를 붙인다.
    private fun digestLine(event: ILoggingEvent): String {
        val firstLine = LogMessageSanitizer.sanitize(event.formattedMessage ?: "").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val text = firstLine.truncateForDiscord(DIGEST_SUMMARY_MAX)
        val exception = event.throwableProxy?.className?.substringAfterLast('.') ?: return text
        return "$text [$exception]"
    }

    internal fun handle(event: ILoggingEvent) {
        if (event.level != Level.ERROR) return
        // 알림 전송 경로의 에러가 다시 알림이 되는 루프를 막는다.
        if (DENY_PREFIXES.any { event.loggerName.startsWith(it) }) return
        if (reentry.get()) return
        reentry.set(true)
        try {
            val fingerprint = "${event.loggerName}|${event.formattedMessage}|${event.throwableProxy?.className ?: ""}"
            val decision = rateLimiter.decide(fingerprint, System.currentTimeMillis(), event.loggerName) { digestLine(event) }
            if (decision.scheduleDigest) scheduleDigest()
            if (!decision.allow) return
            val message = LogMessageSanitizer.sanitize(event.formattedMessage ?: "")
            val stack = event.throwableProxy?.let { LogMessageSanitizer.sanitize(renderStack(it)) }
            discordNotifier.sendErrorAlert(event.loggerName, message, stack, decision.suppressedSince, props.webhookUrl)
        } catch (e: Exception) {
            appender.addError("Discord error alert dispatch failed", e)
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
