package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `holdLimitOnlyWhenProfitable` — #128 2안("리셋 대상 한정: 수익 중인 것만 청산, 손실이면 유지").
 *
 * 판정 기준가는 상한이 걸리는 시각의 가격 = `bar.open`(라이브 KST 09:00 = Upbit 일봉 경계)이다.
 */
class BacktestConditionalHoldLimitTest {

    private val tradingProperties = TradingProperties()
    private val engine = BacktestEngine(listOf(alwaysBuy()), tradingProperties)

    /** 가격게이트를 전부 죽여 보유상한만 남긴다 — 이 테스트가 재는 건 상한 조건뿐이다. */
    private val base = BacktestConfig(
        maxHoldDays = 1, maxLossPct = 99.0, takeProfitPct = 99.0,
        trailingStopPct = 99.0, trailingArmPct = 99.0, useMarketFilter = false,
    )

    @Test
    fun `hold limit fires every bar when unconditional`() = runTest {
        val result = engine.run("always_buy", decliningAfterWarmup(120), "KRW-BTC", base)

        val timeExits = result!!.trades.filter { it.reason == "TIME_EXIT" }
        assertTrue(timeExits.isNotEmpty(), "무조건 상한이면 하락 구간에서도 TIME_EXIT 이 나야 한다")
        timeExits.forEach { assertEquals(1, it.holdDays) }
    }

    @Test
    fun `hold limit is suppressed while position is losing`() = runTest {
        val result = engine.run(
            "always_buy",
            decliningAfterWarmup(120),
            "KRW-BTC",
            base.copy(holdLimitOnlyWhenProfitable = true),
        )

        // 진입 후 시가가 매수가 아래로만 내려가므로 상한 청산이 한 번도 나면 안 된다.
        val timeExits = result!!.trades.filter { it.reason == "TIME_EXIT" }
        assertEquals(emptyList<BacktestTrade>(), timeExits, "손실 구간에서는 상한 청산이 억제돼야 한다")
        assertEquals(listOf("END"), result.trades.map { it.reason }, "포지션이 시리즈 끝까지 유지돼야 한다")
    }

    @Test
    fun `hold limit still fires while position is profitable`() = runTest {
        val result = engine.run(
            "always_buy",
            risingAfterWarmup(120),
            "KRW-BTC",
            base.copy(holdLimitOnlyWhenProfitable = true),
        )

        val timeExits = result!!.trades.filter { it.reason == "TIME_EXIT" }
        assertTrue(timeExits.isNotEmpty(), "수익 구간에서는 상한 청산이 그대로 나야 한다")
    }

    private fun alwaysBuy() = object : TradingStrategy {
        override val name = "always_buy"
        override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = true
    }

    /** 워밍업 50봉 평탄 후 매 봉 −50 씩 하락. 진입가(봉 51 시가)보다 이후 시가가 항상 낮다. */
    private fun decliningAfterWarmup(count: Int) = series(count) { c -> if (c <= 50) 10_000.0 else 10_000.0 - (c - 50) * 50.0 }

    /** 워밍업 50봉 평탄 후 매 봉 +50 씩 상승. */
    private fun risingAfterWarmup(count: Int) = series(count) { c -> if (c <= 50) 10_000.0 else 10_000.0 + (c - 50) * 50.0 }

    /** 입력은 최신순(index 0 = 최신) — [BacktestEngine.run] 이 내부에서 뒤집는다. 봉 내 변동은 0. */
    private fun series(count: Int, priceAt: (Int) -> Double): List<Candle> =
        (0 until count).map { i ->
            val price = priceAt(count - 1 - i)
            Candle(
                market = "KRW-BTC",
                tradePrice = price,
                openingPrice = price,
                highPrice = price,
                lowPrice = price,
                candleAccTradeVolume = 100.0,
            )
        }
}
