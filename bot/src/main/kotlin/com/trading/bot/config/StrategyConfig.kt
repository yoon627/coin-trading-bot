package com.trading.bot.config

import com.trading.common.strategy.BollingerBounce
import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.GoldenCross
import com.trading.common.strategy.KneePullback
import com.trading.common.strategy.KneeReversal
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

    // 신규 전략은 목록 끝에 등록한다 — KisStockTradingEngine 과 TradingEngine 이 strategies.firstOrNull()
    // 을 기본·폴백 전략으로 쓰기 때문에, 앞에 끼워 넣으면 기본 전략이 조용히 바뀐다(StrategyConfigTest 가 가드).
    @Bean fun kneeReversalStrategy(): TradingStrategy = KneeReversal()
    @Bean fun kneePullbackStrategy(): TradingStrategy = KneePullback()
}
