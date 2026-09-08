package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [LiveSemanticsArm.run] 의 `pessimisticTrailing`(트레일링 고점에 이 봉 고가까지 포함 — 봉 안 고점이 저점보다 먼저라는 가정)과
 * `Trade.keptPastLimit` 플래그. 둘 다 사다리 판정(plan `2026-09-09-minute-ladder`)의 브래킷·집계를 만드는 노브라 합성 봉으로 고정한다.
 */
class LiveSemanticsArmPessimisticTrailingTest {

    private val always = object : TradingStrategy {
        override val name = "always"
        override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = true
    }
    private val props = TradingProperties()
    private val config = StrategySearchGrid.currentLivePoint().toConfig() // TP 5 / SL 5 / 트레일 1.5 / arm 0 / h 1

    private val warmup = BacktestEngine.MIN_CANDLES
    private val entry = LocalDate.parse("2024-02-20")
    private fun day(offset: Int) = entry.plusDays(offset.toLong()).toString()

    private fun flat(date: String, p: Double) = Candle(candleDateTimeKst = "${date}T09:00:00", openingPrice = p, highPrice = p, lowPrice = p, tradePrice = p)
    private fun bar(date: String, hour: Int, open: Double, high: Double, low: Double, close: Double) = Candle(
        candleDateTimeUtc = "${date}T%02d:00:00".format(hour), openingPrice = open, highPrice = high, lowPrice = low, tradePrice = close,
    )

    private fun daily(): List<Candle> {
        val out = ArrayList<Candle>()
        for (back in warmup downTo 2) out += flat(entry.minusDays(back.toLong()).toString(), 100.0)
        out += Candle(candleDateTimeKst = "${entry.minusDays(1)}T09:00:00", openingPrice = 100.0, highPrice = 110.0, lowPrice = 100.0, tradePrice = 100.0)
        out += flat(day(0), 100.0)
        for (d in 1..2) out += flat(day(d), 108.0)
        return out
    }

    private fun run(intraday: List<Candle>, pessimistic: Boolean, keepDays: Int = 0, cfg: BacktestConfig = config) = runBlocking {
        LiveSemanticsArm.run("KRW-TEST", always, daily(), intraday, cfg, props, keepWinnersUntilDays = keepDays, pessimisticTrailing = pessimistic).first()
    }

    @Test
    fun `a bar that makes a new high and pulls back inside itself trails only under the pessimistic bracket`() {
        // 익절 off 로 두어 트레일링만 본다. 진입 봉(고가 106, 종가 106) 뒤 두 번째 봉: 고가 112 → 저가 110 → 종가 111.
        // 직전 고점 106 기준으론 손절선 104.41 < 진입가라 트레일링 불가, 이 봉 고가 112 기준으론 110 ≤ 112×0.985 = 110.32 라 걸린다.
        val tpOff = StrategySearchGrid.currentLivePoint().copy(takeProfitPct = StrategySearchGrid.TAKE_PROFIT_OFF).toConfig()
        val bars = listOf(
            bar(day(0), 0, 100.0, 106.0, 100.0, 106.0),
            bar(day(0), 4, 106.0, 112.0, 110.0, 111.0),
        ) + listOf(8, 12, 16, 20).map { bar(day(0), it, 111.0, 111.0, 111.0, 111.0) } + listOf(bar(day(1), 0, 111.0, 111.0, 111.0, 111.0))

        val optimistic = run(bars, pessimistic = false, cfg = tpOff)
        assertEquals("TIME_EXIT", optimistic.reason, "낙관(직전 봉 고점)은 이 봉 안 신고점을 못 보므로 다음날 09:00 청산")
        assertEquals(111.0, optimistic.exitPrice, 1e-9)

        val pessimistic = run(bars, pessimistic = true, cfg = tpOff)
        assertEquals("TRAILING_STOP", pessimistic.reason)
        assertEquals(day(0), pessimistic.exitDate)
        assertEquals(112.0 * 0.985, pessimistic.exitPrice, 1e-9, "비관은 이 봉 고가 112 를 고점으로 봐 110.32 에 청산")
    }

    @Test
    fun `the pessimistic bracket does not touch the entry bar`() {
        // 진입 봉 고가 112·저가 104·종가 108: 비관을 진입 봉에 적용하면 112×0.985=110.32 에 유령 트레일링이 났을 것이다. 적용하지 않으므로 두 처리가 같다.
        val bars = listOf(bar(day(0), 0, 100.0, 112.0, 104.0, 108.0)) +
            listOf(4, 8, 12, 16, 20).map { bar(day(0), it, 108.0, 108.0, 108.0, 108.0) } + listOf(bar(day(1), 0, 108.0, 108.0, 108.0, 108.0))
        val a = run(bars, pessimistic = false)
        val b = run(bars, pessimistic = true)
        assertEquals(a, b)
        assertEquals("TAKE_PROFIT", a.reason, "진입 봉 고가 112 ≥ 익절선 110.25")
    }

    @Test
    fun `keptPastLimit marks only positions that survived the hold-limit bar`() {
        // 진입 봉 고가 110 → peak 110, 손절선 108.35 > 105 (잠김). keep=5 면 09:00 을 넘겨 살아남고, 다음날 저가 108 에서 트레일링.
        val bars = listOf(bar(day(0), 0, 100.0, 110.0, 104.0, 109.5)) +
            listOf(4, 8, 12, 16, 20).map { bar(day(0), it, 109.5, 109.5, 109.5, 109.5) } +
            listOf(0, 4, 8, 12, 16, 20).map { bar(day(1), it, 109.5, 109.5, 108.0, 109.5) } +
            listOf(bar(day(2), 0, 109.5, 109.5, 109.5, 109.5))
        val current = run(bars, pessimistic = false, keepDays = 0)
        assertFalse(current.keptPastLimit)
        assertEquals("TIME_EXIT", current.reason)

        val kept = run(bars, pessimistic = false, keepDays = 5)
        assertTrue(kept.keptPastLimit)
        assertEquals("TRAILING_STOP", kept.reason)
        assertEquals(day(1), kept.exitDate)
    }
}
