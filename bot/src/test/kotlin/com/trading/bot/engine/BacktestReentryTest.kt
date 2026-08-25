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

    private fun flatRisingCandles(
        count: Int,
        lowAt: Map<Int, Double> = emptyMap(),
        highAt: Map<Int, Double> = emptyMap(),
    ): List<Candle> =
        (0 until count).map { i ->
            val price = 10_000.0 + i
            Candle(
                market = "KRW-BTC",
                tradePrice = price,
                openingPrice = price,
                highPrice = highAt[i] ?: price,
                lowPrice = lowAt[i] ?: price,
                candleAccTradeVolume = 100.0,
            )
        }.reversed() // 엔진이 다시 reversed 하므로 최신순으로 넘긴다

    /** 특정 신호가(currentPrice)에서만 false 를 내는 전략 — 재진입 실패 경로를 만든다. */
    private class BuyExceptAt(private val skipPrice: Double) : TradingStrategy {
        override val name = "always_buy"
        override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) =
            currentPrice != skipPrice
    }

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
        assertNotNull(next, "후속 진입이 없으면 이 단언은 공허하다 — 시나리오가 깨진 것: ${result.trades.take(4)}")
        assertTrue(
            next!!.buyIndex >= sl!!.sellIndex + 2,
            "가격게이트 청산 뒤에는 기존 2봉 공백을 유지해야 한다 (sell=${sl.sellIndex}, buy=${next.buyIndex})",
        )
    }

    @Test
    fun `failed re-entry falls back to the normal entry convention`() = runTest {
        // Critical-1 회귀 — 재진입 신호가 false 일 때 그 봉의 통상 진입 기회(신호=봉 i 종가 → 체결 i+1)까지
        // 삼키면 안 된다. 삼키면 cooldown 팔이 legacy 보다 계통적으로 덜 거래해, 측정이 정책 차이가 아니라
        // 구현 아티팩트를 재게 된다. 이 구멍이 열려 있던 이유는 기존 7종이 전부 AlwaysBuy 라
        // entered=false 분기를 한 번도 타지 않았기 때문이다.
        // 첫 진입 51 → 한도 청산 52 → 봉 52 재진입 신호는 봉 51 종가(10_051)를 본다. 그것만 막는다.
        val result = engineOf(BuyExceptAt(skipPrice = 10_051.0))
            .run("always_buy", flatRisingCandles(120), "KRW-BTC", timeExitConfig(ReentryMode.LIVE_SAME_BAR))

        assertNotNull(result)
        val afterExit = result!!.trades.filter { it.buyIndex >= 52 }
        assertTrue(afterExit.isNotEmpty(), "청산 후 재진입이 아예 없다: ${result.trades.take(4)}")
        assertEquals(
            53,
            afterExit.first().buyIndex,
            "봉 52 재진입 실패 후에는 봉 52 종가 신호로 봉 53 에 체결돼야 한다(54 면 기회를 삼킨 것)",
        )
    }

    @Test
    fun `both entry paths see the same window length`() = runTest {
        // 재진입 window 는 봉 i 를 제외하되 길이는 통상 경로와 같은 50 이어야 한다.
        // 49 로 잘리면 RSI(리스트 전체로 Wilder smoothing)·MA50 이 달라져 두 경로의 신호가 갈리는데,
        // 기존 look-ahead 테스트는 "봉 i 를 보는가"만 보고 좌측 경계를 보지 않아 49 여도 통과했다.
        val strategy = AlwaysBuy()
        engineOf(strategy)
            .run("always_buy", flatRisingCandles(120), "KRW-BTC", timeExitConfig(ReentryMode.LIVE_SAME_BAR))

        assertTrue(strategy.seen.isNotEmpty(), "신호 평가가 한 번도 일어나지 않았다")
        val sizes = strategy.seen.map { it.size }.toSet()
        assertEquals(setOf(50), sizes, "두 진입 경로의 window 길이가 달라졌다: $sizes")
    }

    @Test
    fun `re-entry on the final bar stays in range`() = runTest {
        // A3d — 마지막 봉에서 TIME_EXIT + 재진입이 나면 fillIndex 가 배열 끝과 같아진다.
        // 크래시는 없지만(closeOpenPosition 은 인덱스 접근이 없다) 그 포지션이 END 로 마감되는지,
        // 인덱스가 범위 안인지를 고정해 둔다. LIVE 팔에만 END 가 1건 더 생기는 것이 A4 표의
        // 팔 간 거래수 차이로 나타나므로 근거를 남긴다.
        val count = 120
        val result = engineOf(AlwaysBuy())
            .run("always_buy", flatRisingCandles(count), "KRW-BTC", timeExitConfig(ReentryMode.LIVE_SAME_BAR))

        assertNotNull(result)
        result!!.trades.forEach {
            assertTrue(
                it.buyIndex in 0 until count && it.sellIndex in 0 until count,
                "인덱스가 범위를 벗어났다: $it",
            )
            assertTrue(it.sellIndex >= it.buyIndex, "청산이 진입보다 앞설 수 없다: $it")
        }
        val last = result.trades.last()
        assertEquals("END", last.reason, "마지막 trade 는 시리즈 말미 마크투마켓이어야 한다: $last")
        assertEquals(count - 1, last.sellIndex, "END 는 마지막 봉에서 마감돼야 한다: $last")
    }

    @Test
    fun `re-entered position can take profit in the same bar`() = runTest {
        // A1b 의 TP 짝 — 기존 시나리오는 open=high=close 라 TP 게이트가 구조적으로 발동 불가였고
        // 손절만 검증됐다. 봉 52 에 +6% 고점을 심어 익절 경로도 같은 봉에서 도는지 본다.
        val exitBar = 52
        val candles = flatRisingCandles(120, highAt = mapOf(exitBar to (10_000.0 + exitBar) * 1.06))
        val result = engineOf(AlwaysBuy())
            .run("always_buy", candles, "KRW-BTC", timeExitConfig(ReentryMode.LIVE_SAME_BAR))

        assertNotNull(result)
        assertTrue(
            result!!.trades.any { it.buyIndex == exitBar && it.sellIndex == exitBar && it.reason == "TAKE_PROFIT" },
            "봉 $exitBar 재진입 포지션이 같은 봉 익절을 받아야 한다: ${result.trades.take(4)}",
        )
    }
}
