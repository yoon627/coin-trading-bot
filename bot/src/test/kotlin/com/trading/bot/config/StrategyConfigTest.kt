package com.trading.bot.config

import com.trading.common.strategy.TradingStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class StrategyConfigTest {

    private fun registeredStrategyNames(): List<String> =
        AnnotationConfigApplicationContext(StrategyConfig::class.java).use { ctx ->
            ctx.getBeansOfType(TradingStrategy::class.java).values.map { it.name }
        }

    @Test
    fun `registers only the production strategy`() {
        // 엔진과 상태 API 는 첫 bean 을 기본 전략으로 쓴다 — 전략을 더하려면 이 목록과 기본 전략을 함께 정한다.
        assertEquals(listOf("combined"), registeredStrategyNames())
    }
}
