package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TIME_EXIT 직후 재진입 모델(#128). 라이브는 09:00 리셋 매도 후 같은 경계에서 `boughtToday` 가 풀려
 * 곧바로 재매수가 가능한데, 기존 백테는 청산 봉에서 진입 평가를 안 해 2봉 공백이 강제된다.
 *
 * 캔들은 봉마다 가격이 1씩 오르고 O=H=L=C 라 가격 게이트가 절대 발동하지 않는다 —
 * TIME_EXIT 만 남겨 재진입 타이밍을 격리한다. `openingPrice - 10_000` 이 곧 chronological 인덱스라
 * 체결가만 보고 어느 봉인지 되짚을 수 있다.
 */
class BacktestReentryTest {

    private val tradingProperties = TradingProperties()

    /** 신호를 항상 true 로 고정해 재진입 타이밍만 남긴다. */
    private class AlwaysBuy(val seen: MutableList<List<Candle>> = mutableListOf()) : TradingStrategy {
        override val name = "always_buy"
        override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties): Boolean {
            seen.add(candles)
            return true
        }
    }

    private fun engineOf(strategy: TradingStrategy) = BacktestEngine(listOf(strategy), tradingProperties)

    private fun flatRisingCandles(count: Int, lowAt: Map<Int, Double> = emptyMap()): List<Candle> =
        (0 until count).map { i ->
            val price = 10_000.0 + i
            Candle(
                market = "KRW-BTC",
                tradePrice = price,
                openingPrice = price,
                highPrice = price,
                lowPrice = lowAt[i] ?: price,
                candleAccTradeVolume = 100.0,
            )
        }.reversed() // 엔진이 다시 reversed 하므로 최신순으로 넘긴다

    private fun timeExitConfig(mode: ReentryMode, cooldown: Int = 0) = BacktestConfig(
        maxHoldDays = 1,
        reentryMode = mode,
        reentryCooldownBars = cooldown,
    )

    @Test
    fun `legacy mode keeps the two-bar gap after a time exit`() = runTest {
        // 기존 동작 고정 — 이 테스트는 구현 후에도 green 이어야 한다(계약 보존, Decision 6).
        val result = engineOf(AlwaysBuy())
            .run("always_buy", flatRisingCandles(120), "KRW-BTC", timeExitConfig(ReentryMode.LEGACY_NEXT_BAR))

        assertNotNull(result)
        val trades = result!!.trades.filter { it.reason == "TIME_EXIT" }
        assertTrue(trades.size >= 2, "expected repeated TIME_EXIT cycles, got ${result.trades.size} trades")
        assertEquals(trades[0].sellIndex + 2, trades[1].buyIndex, "legacy 는 청산 봉 다음다음 봉에 진입한다")
    }

    @Test
    fun `live same-bar mode re-enters on the exit bar at the exit price`() = runTest {
        // A1 — 재진입 trade 의 buyIndex 가 직전 trade 의 sellIndex 와 같고, 체결가도 그 청산가(bar.open)와 같다.
        val result = engineOf(AlwaysBuy())
            .run("always_buy", flatRisingCandles(120), "KRW-BTC", timeExitConfig(ReentryMode.LIVE_SAME_BAR))

        assertNotNull(result)
        val trades = result!!.trades.filter { it.reason == "TIME_EXIT" }
        assertTrue(trades.size >= 2, "expected repeated TIME_EXIT cycles, got ${result.trades.size} trades")
        assertEquals(trades[0].sellIndex, trades[1].buyIndex, "same-bar 재진입이어야 한다")
        assertEquals(trades[0].sellPrice, trades[1].buyPrice, 1e-9, "재진입가 = 청산가(bar.open)")
    }

    @Test
    fun `re-entry signal must not see the exit bar`() = runTest {
        // R1 — 봉 D 시가에 체결하므로 신호 window 는 D-1 종가까지여야 한다. 기존 `window` 를 그대로
        // 재사용하면 봉 D 를 포함해 look-ahead 가 된다(BacktestEngine 의 window 는 subList(..., i+1)).
        //
        // 판정을 결정적으로 만든다 — 봉 D 를 본 window 에는 신호를 내지 않는 전략을 쓴다.
        // 올바른 구현이면 재진입 신호는 D-1 까지만 보므로 봉 D 진입이 생기고, look-ahead 구현이면 사라진다.
        val exitBar = 52
        val forbidden = 10_000.0 + exitBar
        val strategy = object : TradingStrategy {
            override val name = "always_buy"
            override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) =
                candles.none { it.openingPrice >= forbidden }
        }

        val result = engineOf(strategy)
            .run("always_buy", flatRisingCandles(120), "KRW-BTC", timeExitConfig(ReentryMode.LIVE_SAME_BAR))

        assertNotNull(result)
        assertTrue(
            result!!.trades.any { it.buyIndex == exitBar },
            "봉 $exitBar 재진입이 없다 — 신호 window 가 봉 $exitBar 를 본 것(look-ahead): ${result.trades.take(4)}",
        )
    }

    @Test
    fun `re-entered position is exposed to the same bar's intrabar gates`() = runTest {
        // A1b — 재진입 포지션은 holdDays=0 이라 봉 D 의 실제 low 를 받는다. 이걸 건너뛰면
        // churn 포지션만 손절 보호가 사라져 편향된다.
        // 첫 진입은 51(=chronological), 한도 도달 청산은 52 → 52 에 재진입 → 52 의 low 로 손절.
        val exitBar = 52
        val candles = flatRisingCandles(120, lowAt = mapOf(exitBar to (10_000.0 + exitBar) * 0.90))
        val result = engineOf(AlwaysBuy())
            .run("always_buy", candles, "KRW-BTC", timeExitConfig(ReentryMode.LIVE_SAME_BAR))

        assertNotNull(result)
        val onExitBar = result!!.trades.filter { it.buyIndex == exitBar }
        assertTrue(
            onExitBar.any { it.reason == "STOP_LOSS" && it.sellIndex == exitBar },
            "봉 $exitBar 에 재진입한 포지션이 같은 봉 손절을 받아야 한다: ${result.trades.take(4)}",
        )
    }

    @Test
    fun `at most one re-entry per bar`() = runTest {
        // A1c — 봉 D 에서 재진입 후 같은 봉에 다시 청산돼도 세 번째 진입은 없다(라이브 boughtToday 등가).
        val exitBar = 52
        val candles = flatRisingCandles(120, lowAt = mapOf(exitBar to (10_000.0 + exitBar) * 0.90))
        val result = engineOf(AlwaysBuy())
            .run("always_buy", candles, "KRW-BTC", timeExitConfig(ReentryMode.LIVE_SAME_BAR))

        assertNotNull(result)
        assertEquals(
            1,
            result!!.trades.count { it.buyIndex == exitBar },
            "봉 $exitBar 진입은 1회여야 한다: ${result.trades.take(4)}",
        )
    }

    @Test
    fun `cooldown blocks re-entry for exactly N bars`() = runTest {
        // A2 — 초과·미달 모두 실패해야 한다.
        for (n in 1..3) {
            val result = engineOf(AlwaysBuy())
                .run("always_buy", flatRisingCandles(120), "KRW-BTC", timeExitConfig(ReentryMode.LIVE_SAME_BAR, cooldown = n))

            assertNotNull(result, "cooldown=$n")
            val trades = result!!.trades.filter { it.reason == "TIME_EXIT" }
            assertTrue(trades.size >= 2, "cooldown=$n: expected repeated cycles, got ${result.trades.size}")
            assertEquals(trades[0].sellIndex + n, trades[1].buyIndex, "cooldown=$n 은 정확히 $n 봉 막아야 한다")
        }
    }

    @Test
    fun `price gate exits keep the legacy gap even in live same-bar mode`() = runTest {
        // A2b — SL/TP/트레일링 청산가는 실제 체결가가 아니라 게이트 임계가이고 청산 시각을 D1 에서 모른다.
        // 같은 봉 재진입은 봉의 high/low 를 본 뒤 사는 셈이라 look-ahead → 기존 규약 유지.
        val slBar = 52
        val candles = flatRisingCandles(120, lowAt = mapOf(slBar to (10_000.0 + slBar) * 0.90))
        // maxHoldDays 를 크게 잡아 TIME_EXIT 을 배제하고 손절만 남긴다.
        val config = BacktestConfig(maxHoldDays = 999, reentryMode = ReentryMode.LIVE_SAME_BAR)
        val result = engineOf(AlwaysBuy()).run("always_buy", candles, "KRW-BTC", config)

        assertNotNull(result)
        val sl = result!!.trades.firstOrNull { it.reason == "STOP_LOSS" }
        assertNotNull(sl, "손절 trade 가 있어야 한다: ${result.trades.take(4)}")
        val next = result.trades.firstOrNull { it.buyIndex > sl!!.sellIndex }
        if (next != null) {
            assertTrue(
                next.buyIndex >= sl!!.sellIndex + 2,
                "가격게이트 청산 뒤에는 기존 2봉 공백을 유지해야 한다 (sell=${sl.sellIndex}, buy=${next.buyIndex})",
            )
        }
    }
}
