package com.trading.bot.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.AppenderBase
import com.trading.bot.config.ErrorAlertProperties
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component

/**
 * ERROR 로그를 Discord 로 전달하는 Logback appender (앱 JVM 내부, 새 컨테이너 없음).
 *
 * - opt-in: `discord.error-alert.enabled=true` + webhook-url 설정 시에만 root logger 에 attach.
 * - 무한루프 방지: [DENY_PREFIXES](notification/reactor/netty) 로거 제외 + ThreadLocal reentrancy guard +
 *   내부 실패는 SLF4J 대신 logback `addError`(재귀 차단).
 * - rate limit / 민감정보 마스킹은 ErrorAlertRateLimiter / LogMessageSanitizer 위임.
 * - lifecycle: 이 instance 가 직접 attach 한 경우에만 context 종료 시 detach(다른 context appender 보호).
 * - 한계: ApplicationReadyEvent 이후부터 캡처(기동 실패 에러는 미포함).
 */
@Component
class DiscordErrorLogAppender(
    private val discordNotifier: DiscordNotifier,
    private val props: ErrorAlertProperties,
) : ApplicationListener<ApplicationReadyEvent> {

    private val rateLimiter = ErrorAlertRateLimiter()
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
        if (!attached) return // 이 instance 가 attach 한 경우에만 제거(다른 context 의 appender 보호)
        rootLogger().detachAppender(appender) // name 이 아닌 실제 instance 로 detach
        appender.stop()
        attached = false
    }

    private fun handle(event: ILoggingEvent) {
        if (event.level != Level.ERROR) return
        if (DENY_PREFIXES.any { event.loggerName.startsWith(it) }) return // 알림/전송 경로 자기 에러 무시(루프 차단)
        if (reentry.get()) return
        reentry.set(true)
        try {
            // formattedMessage 기준 dedup: 같은 알림 텍스트만 묶고, user/ticker 가 다른 에러는 구분.
            val fingerprint = "${event.loggerName}|${event.formattedMessage}|${event.throwableProxy?.className ?: ""}"
            val decision = rateLimiter.decide(fingerprint, System.currentTimeMillis())
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
    }
}
