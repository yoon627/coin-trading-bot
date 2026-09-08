package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [LiveSemanticsArm.run] 의 `keepWinnersUntilDays` — "손실만 09:00 에 정리, 트레일링 손절선이 진입가 위로 잠긴 이익은 유지" 정책.
 * 9시 정책 판정(plan `2026-09-09-minute-ladder`)의 셀을 만드는 노브라 합성 봉으로 의미를 고정한다.
 */
class LiveSemanticsArmKeepWinnersTest {

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

    /** 워밍업 49일 + 전날(레인지 10 → 돌파선 105) + 진입일 + 3일. 진입일 index = 50. 이후 진입 없음(`boughtToday` 는 날마다 풀리지만 always 전략이 다시 사도 결과는 첫 거래로 판정). */
    private fun daily(): List<Candle> {
        val out = ArrayList<Candle>()
        for (back in warmup downTo 2) out += flat(entry.minusDays(back.toLong()).toString(), 100.0)
        out += Candle(candleDateTimeKst = "${entry.minusDays(1)}T09:00:00", openingPrice = 100.0, highPrice = 110.0, lowPrice = 100.0, tradePrice = 100.0)
        out += flat(day(0), 100.0)
        for (d in 1..3) out += flat(day(d), 109.5)
        return out
    }

    /**
     * 진입 봉(시가 100, 고가 [entryHigh], 저가 104, 종가 [entryClose]) 뒤 모든 봉은 [later] 평탄(peak 은 max(entryHigh, later)).
     * [laterLow] 는 (day offset → 그날 6봉의 low) 로 되돌림을 만든다.
     */
    private fun intraday(entryHigh: Double, entryClose: Double = 108.0, later: Double = 109.5, laterLow: Map<Int, Double> = emptyMap()): List<Candle> = buildList {
        add(bar(day(0), 0, 100.0, entryHigh, 104.0, entryClose))
        for (h in listOf(4, 8, 12, 16, 20)) add(bar(day(0), h, later, later, later, later))
        for (d in 1..3) {
            val low = laterLow[d] ?: later
            for (h in listOf(0, 4, 8, 12, 16, 20)) add(bar(day(d), h, later, later, low, later))
        }
    }

    private fun first(intraday: List<Candle>, keepDays: Int) = runBlocking {
        LiveSemanticsArm.run("KRW-TEST", always, daily(), intraday, config, props, keepWinnersUntilDays = keepDays).first()
    }

    @Test
    fun `locked winner survives the 09-00 boundary and exits later on its trailing stop`() {
        // 진입 105, peak 110 → 손절선 108.35 > 105 (잠김). 다음날 저가 108 이 손절선 아래 → 다음날 트레일링.
        val bars = intraday(entryHigh = 110.0, laterLow = mapOf(1 to 108.0))
        val current = first(bars, keepDays = 0)
        assertEquals("TIME_EXIT", current.reason)
        assertEquals(day(1), current.exitDate)
        assertEquals(109.5, current.exitPrice, 1e-9, "현행은 다음날 09:00 시가 청산")

        val kept = first(bars, keepDays = 5)
        assertEquals("TRAILING_STOP", kept.reason)
        assertEquals(day(1), kept.exitDate)
        assertEquals(110.0 * 0.985, kept.exitPrice, 1e-9, "유지된 포지션은 트레일링 손절선에 청산")
    }

    @Test
    fun `an unlocked position is still closed at the 09-00 boundary`() {
        // peak 106(이후 봉 104 평탄이라 안 오른다) → 손절선 104.41 < 105 (미잠김) → 정책이 켜져 있어도 현행대로 다음날 시가 청산.
        val bars = intraday(entryHigh = 106.0, entryClose = 104.0, later = 104.0)
        val kept = first(bars, keepDays = 5)
        assertEquals("TIME_EXIT", kept.reason)
        assertEquals(day(1), kept.exitDate)
        assertEquals(104.0, kept.exitPrice, 1e-9)
    }

    @Test
    fun `a kept winner is force-closed when the day cap is reached`() {
        // 잠겼고 되돌림도 없다 → keepWinnersUntilDays=2 면 진입 +2일 첫 봉 시가에 강제 청산.
        val bars = intraday(entryHigh = 110.0)
        val kept = first(bars, keepDays = 2)
        assertEquals("TIME_EXIT", kept.reason)
        assertEquals(day(2), kept.exitDate)
        assertEquals(109.5, kept.exitPrice, 1e-9)
    }
}
