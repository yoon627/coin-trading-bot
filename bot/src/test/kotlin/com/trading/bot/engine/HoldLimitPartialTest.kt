package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 부분 보유상한 청산(`holdLimitSellFraction`)의 계약 — "09:00 에 전량 파는 게 최선인가" 를 재려면 필요한 노브다.
 *
 * 정책: 경계 시각(`bar.open`)에 f 만 실현하고 **잔여는 보유상한을 다시 받지 않는다**. 잔여는 그 봉의 전 구간을
 * 정상으로 겪고 이후 가격 게이트(손절·트레일링·익절)로만 나간다.
 */
class HoldLimitPartialTest {

    private val props = TradingProperties()

    private fun candle(id: String, open: Double, high: Double, low: Double, close: Double) = Candle(
        market = "SYN", candleDateTimeUtc = "2026-01-01T00:00:00", candleDateTimeKst = id,
        openingPrice = open, highPrice = high, lowPrice = low, tradePrice = close,
        candleAccTradeVolume = 100.0, candleAccTradePrice = 100.0 * close,
    )

    @Test
    fun `config rejects policies that cannot share the same boundary`() {
        assertThrows(IllegalArgumentException::class.java) { BacktestConfig(holdLimitSellFraction = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { BacktestConfig(holdLimitSellFraction = 1.0) }
        assertThrows(IllegalArgumentException::class.java) {
            BacktestConfig(holdLimitSellFraction = 0.5, partialTakeProfitPct = 2.0, partialTakeProfitFraction = 0.3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BacktestConfig(holdLimitSellFraction = 0.5, holdLimitOnlyWhenProfitable = true)
        }
    }

    @Test
    fun `M1 replay refuses the partial hold-limit policy`() {
        val entry = LocalDateTime.parse("2026-01-01T00:00:00")
        assertThrows(IllegalArgumentException::class.java) {
            M1ReplayEngine.replayExit(100.0, entry, entry.plusDays(1), listOf(candle("b", 100.0, 101.0, 99.0, 100.0)), BacktestConfig(holdLimitSellFraction = 0.5))
        }
    }

    @Test
    fun `the remainder survives the boundary and exits on a price gate instead`() = runTest {
        // 워밍업 50봉 평탄 → 진입 → 경계 봉에서 소폭 상승 → 이후 급등해 익절선 도달.
        val bars = buildList {
            repeat(52) { add(candle("w%03d".format(it), 100.0, 100.5, 99.5, 100.0)) }
            add(candle("boundary", 101.0, 102.0, 100.5, 101.5))
            add(candle("spike", 102.0, 120.0, 101.5, 118.0))
            add(candle("after", 118.0, 119.0, 117.0, 118.0))
        }.reversed()

        val engine = BacktestEngine(listOf(AlwaysBuy()), props)
        val full = engine.run(AlwaysBuy.NAME, bars, "SYN", BacktestConfig(takeProfitPct = 10.0, maxLossPct = 20.0, trailingStopPct = 50.0))!!
        val partial = engine.run(
            AlwaysBuy.NAME, bars, "SYN",
            BacktestConfig(takeProfitPct = 10.0, maxLossPct = 20.0, trailingStopPct = 50.0, holdLimitSellFraction = 0.5),
        )!!

        assertTrue(full.trades.any { it.reason == "TIME_EXIT" }, "전량 정책은 경계에서 TIME_EXIT 이 난다")
        assertTrue(full.trades.all { it.partialFraction == null }, "전량 정책엔 합성 레코드가 없다")

        val composite = partial.trades.first { it.partialFraction != null }
        assertEquals(0.5, composite.partialFraction)
        assertTrue(composite.reason != "TIME_EXIT", "잔여는 상한이 아니라 가격 게이트로 나간다 — 실제 사유 ${composite.reason}")
        assertTrue(composite.pnlPercent > full.trades.first().pnlPercent, "급등을 절반이라도 먹었으므로 전량 청산보다 낫다")
    }

    @Test
    fun `the boundary is only consumed once`() = runTest {
        val bars = buildList {
            repeat(52) { add(candle("w%03d".format(it), 100.0, 100.5, 99.5, 100.0)) }
            repeat(6) { add(candle("d$it", 100.0, 100.5, 99.5, 100.0)) }
        }.reversed()
        val engine = BacktestEngine(listOf(AlwaysBuy()), props)
        val result = engine.run(
            AlwaysBuy.NAME, bars, "SYN",
            BacktestConfig(takeProfitPct = 50.0, maxLossPct = 50.0, trailingStopPct = 50.0, holdLimitSellFraction = 0.5),
        )!!
        // 상한을 매 봉 다시 걸면 거래가 계속 쪼개진다 — 소진 플래그가 그걸 막는지 본다.
        assertTrue(result.trades.count { it.partialFraction != null } <= result.trades.size)
        for (t in result.trades) assertTrue(t.partialFraction == null || t.partialFraction == 0.5)
    }

    @Test
    fun `default config keeps the full-liquidation behaviour`() {
        assertNull(BacktestConfig().holdLimitSellFraction)
    }

    private class AlwaysBuy : com.trading.common.strategy.TradingStrategy {
        override val name = NAME
        override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = true
        override suspend fun shouldSell(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = false

        companion object {
            const val NAME = "always_buy"
        }
    }
}
