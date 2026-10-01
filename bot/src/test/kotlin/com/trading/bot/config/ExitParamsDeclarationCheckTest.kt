package com.trading.bot.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.notification.DiscordErrorLogAppender
import com.trading.common.config.TradingProperties
import io.mockk.every
import io.mockk.mockk
import jakarta.annotation.PostConstruct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.annotation.Order
import org.springframework.core.annotation.OrderUtils
import org.springframework.core.env.Environment
import org.springframework.mock.env.MockEnvironment

/**
 * 청산 파라미터의 미선언·구간 밖 값을 **드러내되 막지는 않는다**는 계약을 고정한다 (#179, #230).
 *
 * 막지 않는 이유는 [ExitParamsDeclarationCheck] KDoc 참조 — 차단은 배포 preflight 가 맡는다.
 */
class ExitParamsDeclarationCheckTest {

    private fun envWith(keys: Collection<String>) = MockEnvironment().apply {
        keys.forEach { setProperty(it, "1.0") }
    }

    private fun check(declared: Collection<String>) =
        ExitParamsDeclarationCheck(TradingProperties(), envWith(declared))

    private fun errorsWhileReporting(check: ExitParamsDeclarationCheck): List<String> {
        val logger = LoggerFactory.getLogger(ExitParamsDeclarationCheck::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            check.report("테스트")
            appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `모두 선언되면 미선언 키가 없다`() {
        assertThat(check(ExitParamsDeclarationCheck.REQUIRED_KEYS).undeclaredKeys).isEmpty()
    }

    @Test
    fun `아무것도 선언되지 않으면 전부 미선언으로 잡는다`() {
        assertThat(check(emptyList()).undeclaredKeys)
            .containsExactlyElementsOf(ExitParamsDeclarationCheck.REQUIRED_KEYS)
    }

    @ParameterizedTest(name = "{0} 하나만 빠져도 잡는다")
    @MethodSource("requiredKeys")
    fun `키 하나가 빠지면 그 키를 지목한다`(missing: String) {
        val declared = ExitParamsDeclarationCheck.REQUIRED_KEYS - missing
        assertThat(check(declared).undeclaredKeys).containsExactly(missing)
    }

    @Test
    fun `선언 검사는 값을 보지 않는다`() {
        // 선언 검사가 운영값을 강제하면 운영이 값을 바꿀 때마다 코드를 고쳐야 한다.
        // 값은 의미상 구간(ExitParamRanges)만 따로 본다.
        val env = MockEnvironment().apply {
            ExitParamsDeclarationCheck.REQUIRED_KEYS.forEach { setProperty(it, "-999") }
        }
        assertThat(ExitParamsDeclarationCheck(TradingProperties(), env).undeclaredKeys).isEmpty()
    }

    @Test
    fun `요구 키는 TradingProperties 프로퍼티에서 파생돼 실제 바인딩 키와 일치한다`() {
        // 문자열로 적으면 rename 시 옛 키가 계속 선언돼 있어 검사가 조용히 무력해진다.
        assertThat(ExitParamsDeclarationCheck.REQUIRED_KEYS).containsExactly(
            "trading.take-profit-pct",
            "trading.max-loss-pct",
            "trading.trailing-stop-pct",
            "trading.trailing-arm-pct",
            "trading.max-hold-days",
        )
    }

    @Test
    fun `구간 밖 전역값은 키·값·구간을 ERROR 한 줄로 알린다`() {
        val check = ExitParamsDeclarationCheck(
            TradingProperties(maxLossPct = -5.0),
            envWith(ExitParamsDeclarationCheck.REQUIRED_KEYS),
        )
        val errors = errorsWhileReporting(check)
        assertThat(errors).hasSize(1)
        // 이 값으로 이미 연 포지션은 진입 때 찍은 스냅샷으로 청산된다 — 설정만 고쳐서는 되돌아오지 않는다는 것까지 알린다.
        assertThat(errors.single()).contains("trading.max-loss-pct=-5.0", "(0, 100)", "진입 시점")
    }

    @Test
    fun `운영값이면 ERROR 가 없다`() {
        val check = ExitParamsDeclarationCheck(ExitParamRangesTest.PRODUCTION, envWith(ExitParamsDeclarationCheck.REQUIRED_KEYS))
        assertThat(errorsWhileReporting(check)).isEmpty()
    }

    @Test
    fun `보고는 예외를 던지지 않는다 — 거래를 막지 않는 것이 계약이다`() {
        check(emptyList()).report("테스트") // 미선언이 전부여도 throw 없음
        check(ExitParamsDeclarationCheck.REQUIRED_KEYS).report("테스트")
        ExitParamsDeclarationCheck(TradingProperties(maxLossPct = Double.NaN), envWith(emptyList())).report("테스트")
    }

    @Test
    fun `보고 중 예외가 나도 던지지 않고 ERROR 로 남긴다`() {
        // 기동 보고는 복원 앞의 ready 리스너이고 수동 시작에서도 불린다 — 던지면 기동·시작이 실패한다.
        val broken = mockk<Environment> { every { containsProperty(any()) } throws IllegalStateException("source down") }
        val errors = errorsWhileReporting(ExitParamsDeclarationCheck(TradingProperties(), broken))
        assertThat(errors).hasSize(1)
        assertThat(errors.single()).contains("테스트")
    }

    @Test
    fun `기동 보고는 Discord appender 뒤·복원 앞에 도는 ApplicationReadyEvent 리스너다`() {
        val report = ExitParamsDeclarationCheck::class.java.getMethod("reportOnStartup")
        val listener = AnnotatedElementUtils.findMergedAnnotation(report, EventListener::class.java)
        assertThat(listener?.classes?.map { it.java }).containsExactly(ApplicationReadyEvent::class.java)

        // appender attach 전에 보고하면 ERROR 가 Discord 에 가지 않고, 복원 뒤에 보고하면 거래 재개 뒤에 알린다.
        val appender = OrderUtils.getOrder(DiscordErrorLogAppender::class.java)
        val reportOrder = AnnotatedElementUtils.findMergedAnnotation(report, Order::class.java)?.value
        val restore = UserTradingManager::class.java.getMethod("restoreOnStartup")
        val restoreOrder = AnnotatedElementUtils.findMergedAnnotation(restore, Order::class.java)?.value
        assertThat(appender).isNotNull()
        assertThat(reportOrder).isNotNull()
        assertThat(restoreOrder).isNotNull()
        assertThat(reportOrder!!).isGreaterThan(appender!!).isLessThan(restoreOrder!!)

        assertThat(ExitParamsDeclarationCheck::class.java.methods.filter { it.isAnnotationPresent(PostConstruct::class.java) })
            .describedAs("@PostConstruct 는 appender attach 전이라 기동 ERROR 가 Discord 에 가지 않는다")
            .isEmpty()
    }

    companion object {
        @JvmStatic
        fun requiredKeys(): List<String> = ExitParamsDeclarationCheck.REQUIRED_KEYS
    }
}
