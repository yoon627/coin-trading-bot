package com.trading.bot.config

import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.TradingStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class StrategyConfig {
    // 엔진과 상태 API 는 첫 bean 을 기본 전략으로 쓴다 — 전략을 더할 때는 순서와 StrategyConfigTest 의 목록을 함께 정한다.
    @Bean fun combinedStrategy(): TradingStrategy = CombinedStrategy()
}
