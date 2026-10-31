package com.trading.bot.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.notification.DiscordErrorLogAppender
import com.trading.common.config.TradingProperties
import jakarta.annotation.PostConstruct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.annotation.Order
import org.springframework.core.annotation.OrderUtils

/** TRADING_TICKERS 형식은 기동 뒤 알리기만 한다. 엔진은 값을 그대로 쓰고, 차단은 배포 preflight 가 맡는다. */
class TradingTickersCheckTest {

    private fun errorsWhileReporting(tickers: String): List<String> {
        val logger = LoggerFactory.getLogger(TradingTickersCheck::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            TradingTickersCheck(TradingProperties(tickers = tickers)).reportOnStartup()
            appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `형식이 틀리거나 빈 TRADING_TICKERS 는 기동 뒤 ERROR 한 줄로 알리고 맞으면 알리지 않는다`() {
        // 엔진은 원래 값을 그대로 주문에 쓰므로 watchlist 와 달리 소문자도 틀린 항목이다.
        val invalid = errorsWhileReporting("KRW-BTC,KRW_BTC,krw-eth")
        assertThat(invalid).hasSize(1)
        assertThat(invalid.single()).contains("KRW_BTC", "krw-eth").doesNotContain("KRW-BTC,")
        assertThat(errorsWhileReporting("KRW-BTC,KRW-T")).isEmpty()
        for (empty in listOf("", " , ")) {
            assertThat(errorsWhileReporting(empty)).describedAs("'$empty'").singleElement().asString().contains("비어 있습니다")
        }
        // ERROR 는 Discord 로 나가므로 값을 통째로 붙여 넣은 항목은 잘라서 싣는다.
        val pasted = errorsWhileReporting("KRW-BTC," + "A".repeat(500)).single()
        assertThat(pasted).contains("A".repeat(40)).doesNotContain("A".repeat(41))
    }

    // appender attach 전에 보고하면 ERROR 가 Discord 에 가지 않고, 복원 뒤에 보고하면 거래 재개 뒤에 알린다.
    @Test
    fun `기동 보고는 Discord appender 뒤·복원 앞에 도는 ApplicationReadyEvent 리스너다`() {
        val report = TradingTickersCheck::class.java.getMethod("reportOnStartup")
        val listener = AnnotatedElementUtils.findMergedAnnotation(report, EventListener::class.java)
        assertThat(listener?.classes?.map { it.java }).containsExactly(ApplicationReadyEvent::class.java)

        val appender = OrderUtils.getOrder(DiscordErrorLogAppender::class.java)
        val reportOrder = AnnotatedElementUtils.findMergedAnnotation(report, Order::class.java)?.value
        val restore = UserTradingManager::class.java.getMethod("restoreOnStartup")
        val restoreOrder = AnnotatedElementUtils.findMergedAnnotation(restore, Order::class.java)?.value
        assertThat(appender).isNotNull()
        assertThat(reportOrder).isNotNull()
        assertThat(restoreOrder).isNotNull()
        assertThat(reportOrder!!).isGreaterThan(appender!!).isLessThan(restoreOrder!!)
        assertThat(TradingTickersCheck::class.java.methods.filter { it.isAnnotationPresent(PostConstruct::class.java) }).isEmpty()
    }
}
