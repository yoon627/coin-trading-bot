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
        // 둘째 전략 bean 은 @Primary·이름 일치로 기동 실패 없이 주입될 수 있어 목록으로 확인한다.
        assertEquals(listOf("combined"), registeredStrategyNames())
    }
}
