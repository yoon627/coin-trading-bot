package com.trading.bot.config

import com.trading.common.strategy.TradingStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class StrategyConfigTest {

    private fun registeredStrategyNames(): List<String> =
        AnnotationConfigApplicationContext(StrategyConfig::class.java).use { ctx ->
            ctx.getBeansOfType(TradingStrategy::class.java).values.map { it.name }
        }

    @Test
    fun `registers every strategy exactly once`() {
        val names = registeredStrategyNames()

        assertEquals(names.distinct(), names, "전략 이름이 중복 등록됐다: $names")
        assertTrue(
            names.containsAll(listOf("knee_reversal", "knee_pullback")),
            "무릎 전략이 등록되지 않았다: $names",
        )
    }

    @Test
    fun `keeps volatility_breakout as the first strategy`() {
        // KisStockTradingEngine.kt:57 과 TradingEngine.kt:66 이 strategies.firstOrNull() 을
        // 기본·폴백 전략으로 쓴다. 새 전략을 목록 앞에 끼워 넣으면 KIS 국내주식 봇의 기본 전략이
        // 조용히 바뀌므로, 첫 자리를 고정한다.
        assertEquals("volatility_breakout", registeredStrategyNames().first())
    }
}
