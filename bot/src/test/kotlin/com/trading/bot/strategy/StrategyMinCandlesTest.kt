package com.trading.bot.strategy

import com.trading.bot.config.StrategyConfig
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/** `minCandles` 선언이 실제 동작과 맞는지 본다. 새 전략이 빠지지 않게 전략 목록은 Spring 컨텍스트에서 가져온다. */
class StrategyMinCandlesTest {

    private val config = TradingProperties()

    private fun strategies(): List<TradingStrategy> =
        AnnotationConfigApplicationContext(StrategyConfig::class.java).use { ctx ->
            ctx.getBeansOfType(TradingStrategy::class.java).values.toList()
        }

    /** 어떤 지표에도 걸리지 않는 평탄한 캔들. 봉 수만으로 판정을 가른다. */
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
        }
    }

    @Test
    fun `every strategy evaluates without throwing at its declared minimum`() = runTest {
        // 선언값에서 예외가 나면 엔진이 그 값을 믿고 호출했을 때 매매 루프가 터진다.
        for (strategy in strategies()) {
            val exact = flat(strategy.minCandles)
            strategy.shouldBuy(exact, 10_000.0, config)
        }
    }

    /**
     * "minCandles-1 에서 false" 만으로는 선언이 실제보다 작은 경우를 못 잡아 기대값을 표로 둔다.
     * 새 전략은 이 표에 없어 실패한다.
     */
    @Test
    fun `declared minimums match the agreed contract`() {
        val expected = mapOf("combined" to 21)

        val actual = strategies().associate { it.name to it.minCandles }

        assertEquals(expected, actual, "전략의 minCandles 선언이 합의된 계약과 다르다")
    }

}
