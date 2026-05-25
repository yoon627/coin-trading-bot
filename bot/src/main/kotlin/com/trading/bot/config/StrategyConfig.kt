package com.trading.bot.config

import com.trading.common.strategy.BollingerBounce
import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.GoldenCross
import com.trading.common.strategy.MacdCross
import com.trading.common.strategy.MeanReversion
import com.trading.common.strategy.RsiBounce
import com.trading.common.strategy.TradingStrategy
import com.trading.common.strategy.VolatilityBreakout
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class StrategyConfig {
    @Bean fun volatilityBreakoutStrategy(): TradingStrategy = VolatilityBreakout()
    @Bean fun goldenCrossStrategy(): TradingStrategy = GoldenCross()
    @Bean fun bollingerBounceStrategy(): TradingStrategy = BollingerBounce()
    @Bean fun meanReversionStrategy(): TradingStrategy = MeanReversion()
    @Bean fun rsiBounceStrategy(): TradingStrategy = RsiBounce()
    @Bean fun macdCrossStrategy(): TradingStrategy = MacdCross()
    @Bean fun combinedStrategy(): TradingStrategy = CombinedStrategy()
}
