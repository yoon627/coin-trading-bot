package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [LiveSemanticsArm.run] 의 `entryBarStopOnClose` — 진입 봉의 **손절 게이트만** 봉 저가 대신 종가로 판정한다.
 * 이 옵션은 판정 게이트(plan `2026-09-09-tp-sl-grid` Acceptance 8c)를 만드는 감도 팔이라 합성 봉으로 의미를 고정한다.
 */
class LiveSemanticsArmEntryBarTest {

    private val always = object : TradingStrategy {
        override val name = "always"
        override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = true
    }
    private val props = TradingProperties()
    private val config = StrategySearchGrid.currentLivePoint().toConfig() // TP 5 / SL 5 / 트레일 1.5 / arm 0 / k 0.5 / h 1

    private val warmup = BacktestEngine.MIN_CANDLES
    private val entryDay = "2024-02-20"
    private val nextDay = "2024-02-21"

    /** 시간순 일봉: 워밍업 49일(무변동 100) + 진입일 전날(레인지 10 → 돌파선 = 당일시가 + 5) + 진입일 + 다음날 = 52봉, 진입일 index = 워밍업 50. */
    private fun daily(): List<Candle> {
        val entry = LocalDate.parse(entryDay)
        val out = ArrayList<Candle>()
        for (back in warmup downTo 2) out += flat(entry.minusDays(back.toLong()).toString(), 100.0)
        out += Candle(candleDateTimeKst = "${entry.minusDays(1)}T09:00:00", openingPrice = 100.0, highPrice = 110.0, lowPrice = 100.0, tradePrice = 100.0)
        out += flat(entryDay, 100.0)
        out += flat(nextDay, 109.5)
        check(out.size == warmup + 2)
        return out
    }

    private fun flat(date: String, p: Double) = Candle(candleDateTimeKst = "${date}T09:00:00", openingPrice = p, highPrice = p, lowPrice = p, tradePrice = p)

    private fun bar(date: String, hour: Int, open: Double, high: Double, low: Double, close: Double) = Candle(
        candleDateTimeUtc = "${date}T%02d:00:00".format(hour), openingPrice = open, highPrice = high, lowPrice = low, tradePrice = close,
    )

    /**
     * 진입 봉: 시가 100 → 돌파선 105 에서 체결. 나머지 봉은 109.5 로 평탄(진입 봉 고가 110 이 peak 이라 되돌림 0.45% < 트레일 1.5),
     * 다음날 첫 봉 시가 109.5 → 한도봉 TIME_EXIT.
     */
    private fun intraday(entryHigh: Double, entryLow: Double, entryClose: Double): List<Candle> = buildList {
        add(bar(entryDay, 0, 100.0, entryHigh, entryLow, entryClose))
        for (h in listOf(4, 8, 12, 16, 20)) add(bar(entryDay, h, 109.5, 109.5, 109.5, 109.5))
        add(bar(nextDay, 0, 109.5, 109.5, 109.5, 109.5))
    }

    private fun run(intraday: List<Candle>, onClose: Boolean) = runBlocking {
        LiveSemanticsArm.run("KRW-TEST", always, daily(), intraday, config, props, entryBarStopOnClose = onClose)
    }

    @Test
    fun `entry-bar low below the stop fires STOP_LOSS at the stop price by default but not when judged on close`() {
        // 체결 105, 손절선 99.75. 저가 95 는 손절선 아래, 종가 108 은 위, 고가 110 은 익절선(110.25) 아래.
        val bars = intraday(entryHigh = 110.0, entryLow = 95.0, entryClose = 108.0)

        val byLow = run(bars, onClose = false).single()
        assertEquals("STOP_LOSS", byLow.reason)
        assertTrue(byLow.exitOnEntryBar)
        assertEquals(105.0 * 0.95, byLow.exitPrice, 1e-9, "체결가는 손절선")

        val byClose = run(bars, onClose = true).single()
        assertEquals("TIME_EXIT", byClose.reason, "종가 판정이면 진입 봉에서 손절이 나지 않고 다음날 09:00 청산")
        assertEquals(nextDay, byClose.exitDate)
        assertEquals(109.5, byClose.exitPrice, 1e-9)
    }

    @Test
    fun `entry-bar take-profit and diagnostic fields are identical under both treatments when the low is above the stop`() {
        // 고가 111 ≥ 익절선 110.25, 저가 104 > 손절선 99.75 — 손절 판정 기준을 바꿔도 결과가 같아야 한다.
        val bars = intraday(entryHigh = 111.0, entryLow = 104.0, entryClose = 108.0)
        val a = run(bars, onClose = false).single()
        val b = run(bars, onClose = true).single()
        assertEquals(a, b)
        assertEquals("TAKE_PROFIT", a.reason)
        assertEquals(105.0 * 1.05, a.exitPrice, 1e-9)
        assertEquals(100.0, a.exitBarOpen, 1e-9)
        assertEquals(104.0, a.exitBarLow, 1e-9, "진단 필드는 원본 봉의 저가")
    }

    @Test
    fun `when both stop and take-profit are touched on the entry bar the stop wins by low but take-profit wins by close`() {
        // 저가 95(손절선 아래)·고가 111(익절선 위)·종가 108. 봉 안 순서 불명 → 저가 판정은 손절 우선, 종가 판정은 손절 미발동 → 익절.
        val bars = intraday(entryHigh = 111.0, entryLow = 95.0, entryClose = 108.0)
        assertEquals("STOP_LOSS", run(bars, onClose = false).single().reason)
        val byClose = run(bars, onClose = true).single()
        assertEquals("TAKE_PROFIT", byClose.reason)
        assertEquals(95.0, byClose.exitBarLow, 1e-9, "종가로 판정해도 진단 필드는 원본 저가를 기록한다")
    }
}
