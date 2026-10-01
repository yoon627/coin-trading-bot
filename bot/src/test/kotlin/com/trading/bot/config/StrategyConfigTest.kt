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
        // 매니저가 TradingStrategy 하나를 주입받는다 — 둘째 bean 은 @Primary·이름 일치로 조용히 주입될 수 있어 기동 실패에 기대지 않고 목록을 고정한다.
        assertEquals(listOf("combined"), registeredStrategyNames())
    }
}
