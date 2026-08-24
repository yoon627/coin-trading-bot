package com.trading.bot.strategy

import com.trading.bot.config.StrategyConfig
import com.trading.bot.engine.BacktestEngine
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * `minCandles` 계약이 실제 동작과 맞는지 고정한다.
 *
 * 전략 목록을 **Spring 컨텍스트에서 열거**하는 이유: 기본값(21)이 있는 이상 더 많은 봉이 필요한 새 전략이
 * override 를 잊으면 같은 버그가 재발하는데, 하드코딩 목록이면 그 전략은 검사 대상에서 빠진다.
 */
class StrategyMinCandlesTest {

    private val config = TradingProperties()

    private fun strategies(): List<TradingStrategy> =
        AnnotationConfigApplicationContext(StrategyConfig::class.java).use { ctx ->
            ctx.getBeansOfType(TradingStrategy::class.java).values.toList()
        }

    /** 어떤 지표에도 걸리지 않는 평탄한 캔들 — 봉 수만으로 판정을 가르기 위한 것. */
    private fun flat(count: Int) = List(count) {
        Candle(
            market = "KRW-BTC",
            openingPrice = 10_000.0,
            highPrice = 10_100.0,
            lowPrice = 9_900.0,
            tradePrice = 10_000.0,
            candleAccTradeVolume = 100.0,
        )
    }

    @Test
    fun `every strategy refuses to signal below its declared minimum`() = runTest {
        for (strategy in strategies()) {
            val short = flat(strategy.minCandles - 1)
            assertFalse(
                strategy.shouldBuy(short, 10_000.0, config),
                "${strategy.name}: minCandles=${strategy.minCandles} 인데 ${short.size}봉에서 매수 신호가 났다",
            )
            assertFalse(
                strategy.shouldSell(short, 10_000.0, config),
                "${strategy.name}: minCandles=${strategy.minCandles} 인데 ${short.size}봉에서 청산 신호가 났다",
            )
        }
    }

    @Test
    fun `every strategy evaluates without throwing at its declared minimum`() = runTest {
        // 선언값에서 예외가 나면 엔진이 그 값을 믿고 호출했을 때 매매 루프가 터진다.
        for (strategy in strategies()) {
            val exact = flat(strategy.minCandles)
            strategy.shouldBuy(exact, 10_000.0, config)
            strategy.shouldSell(exact, 10_000.0, config)
        }
    }

    @Test
    fun `no strategy demands more candles than the backtest window provides`() {
        // 백테는 전략에 항상 정확히 MIN_CANDLES 봉을 넘긴다. 그보다 많이 요구하는 전략은 백테에서
        // 영영 신호를 못 내 비교 대상에서 조용히 빠진다.
        for (strategy in strategies()) {
            assertTrue(
                strategy.minCandles <= BacktestEngine.MIN_CANDLES,
                "${strategy.name}: minCandles=${strategy.minCandles} > 백테 window ${BacktestEngine.MIN_CANDLES}",
            )
        }
    }

    /**
     * 선언값 자체를 고정한다.
     *
     * "minCandles-1 에서 false" 만으로는 **선언이 실제보다 작은 경우를 못 잡는다** — 41봉을 요구하는
     * 전략은 20봉에서도 false 라 21로 잘못 선언해도 통과한다(실측 확인). 기대값을 명시해야 override
     * 누락이 드러나고, 새 전략은 이 표에 없어서 실패한다 — 계약을 정하라는 신호다.
     */
    @Test
    fun `declared minimums match the agreed contract`() {
        val expected = mapOf(
            "volatility_breakout" to 21,
            "golden_cross" to 21,
            "bollinger_bounce" to 21,
            "mean_reversion" to 21,
            "rsi_bounce" to 21,
            "macd_cross" to 36,
            "combined" to 21,
            "knee_reversal" to 41,
            "knee_pullback" to 41,
        )

        val actual = strategies().associate { it.name to it.minCandles }

        assertEquals(expected, actual, "전략의 minCandles 선언이 합의된 계약과 다르다")
    }

}
