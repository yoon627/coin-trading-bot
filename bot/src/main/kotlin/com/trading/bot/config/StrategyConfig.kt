package com.trading.bot.config

import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.TradingStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class StrategyConfig {
    // 전략 bean 은 하나여야 한다 — UserTradingManager 가 TradingStrategy 하나를 주입받는다. 둘이면 대개 기동이 실패하지만
    // @Primary·파라미터명과 같은 bean 이름이 있으면 그 bean 이 조용히 주입되므로, 목록은 StrategyConfigTest 가 고정한다.
    @Bean fun combinedStrategy(): TradingStrategy = CombinedStrategy()
}
