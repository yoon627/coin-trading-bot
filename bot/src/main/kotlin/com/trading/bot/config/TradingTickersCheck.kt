package com.trading.bot.config

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** TRADING_TICKERS 형식을 기동 뒤 알린다. 거래 목록은 바꾸지 않는다 — 차단은 배포 preflight 가 맡는다. */
@Component
class TradingTickersCheck(private val tradingProperties: TradingProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    // @PostConstruct 는 Discord appender 가 붙기 전이라 그 ERROR 는 Discord 에 가지 않는다. 복원보다 먼저 알린다.
    // ready 리스너의 예외는 기동을 실패시키므로 삼키고 남긴다.
    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    fun reportOnStartup() {
        try {
            // 엔진은 원래 값을 그대로 주문에 쓰므로 대문자로 바꾸지 않고 본다.
            val tickers = tradingProperties.tickerList()
            val invalid = tickers.filterNot { MarketPair.isValid(Exchange.UPBIT, it) }
            if (tickers.isNotEmpty() && invalid.isEmpty()) return
            val problem = if (tickers.isEmpty()) "가 비어 있습니다" else "의 형식이 틀린 항목 ${reportableEntries(invalid)}"
            log.error(
                "TRADING_TICKERS{} — 티커를 고르지 않고 시작하는 봇(웹 화면 기본 목록 포함)이 이 값을 그대로 씁니다. 복원되는 봇은 저장된 목록을 씁니다. 배포 환경(.env·VULTR_DEPLOY_ENV 시크릿)을 고치세요",
                problem,
            )
        } catch (e: Exception) {
            log.error("TRADING_TICKERS 점검에 실패했습니다 — 거래는 막지 않습니다", e)
        }
    }
}
