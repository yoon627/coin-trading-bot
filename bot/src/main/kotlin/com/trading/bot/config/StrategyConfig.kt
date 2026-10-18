package com.trading.bot.config

import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.TradingStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class StrategyConfig {
    // TradingStrategy 빈은 하나여야 한다. 둘이 되면 @Primary 나 이름이 맞는 쪽이 조용히 주입될 수 있다.
    @Bean fun combinedStrategy(): TradingStrategy = CombinedStrategy()
}
