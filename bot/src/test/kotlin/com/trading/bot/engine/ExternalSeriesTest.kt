package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalSeriesTest {

    private val fixture = ExternalSeries.load("fng")

    @Test
    fun `gate value for a trading day is the previous data day`() {
        val day = LocalDate.parse("2021-05-20")
        assertEquals(fixture.dates.let { d -> d.first { it == day.minusDays(1) } }, day.minusDays(1))
        assertEquals(fixture.lagged(day), fixture.laggedBy(day, 0))
        assertEquals(fixture.lagged(day.minusDays(5)), fixture.laggedBy(day, 5))
    }

    @Test
    fun `rolling statistics exclude the gate day itself`() {
        val day = LocalDate.parse("2020-06-01")
        val sma = fixture.sma(day, 20)!!
        val manual = (2..21).map { fixture.lagged(day.minusDays(it.toLong() - 1))!! }.average()
        assertEquals(manual, sma, 1e-9)
        assertNull(fixture.sma(fixture.dates.first().plusDays(5), 20), "워밍업 전에는 통계가 없어야 한다")
    }

    @Test
    fun `phase shift keeps the date set and the value multiset`() {
        val shifted = fixture.shifted(17)
        assertEquals(fixture.dates, shifted.dates)
        val original = fixture.dates.map { fixture.lagged(it.plusDays(1))!! }.sorted()
        val moved = shifted.dates.map { shifted.lagged(it.plusDays(1))!! }.sorted()
        assertEquals(original, moved)
        assertFalse(fixture.dates.all { fixture.lagged(it.plusDays(1)) == shifted.lagged(it.plusDays(1)) }, "이동했는데 값이 그대로다")
    }

    @Test
    fun `regime cells are complete across the fixture windows and complementary pairs partition the days`() {
        val regime = ExternalRegime.load()
        val days = (0 until 400).map { LocalDate.parse("2020-01-23").plusDays(it.toLong()) }
        days.forEach { assertTrue(regime.complete(it), "$it 결측") }
        for (d in days) {
            assertTrue(regime.allows(ExternalRegime.Cell.KIMP_LOW, d) != regime.allows(ExternalRegime.Cell.KIMP_HIGH, d))
            assertTrue(regime.allows(ExternalRegime.Cell.ETHBTC_UP, d) != regime.allows(ExternalRegime.Cell.ETHBTC_DOWN, d))
            assertEquals(regime.allows(ExternalRegime.Cell.KIMP_LOW, d) && regime.allows(ExternalRegime.Cell.FUND_LOW, d), regime.allows(ExternalRegime.Cell.COMBO, d))
        }
    }

    @Test
    fun `gated strategy blocks only when the inner strategy would buy and counts each day once`() = runBlocking {
        val always = object : TradingStrategy { override val name = "combined"; override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = true }
        val never = object : TradingStrategy { override val name = "combined"; override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = false }
        val window = listOf(Candle(market = "KRW-BTC", candleDateTimeKst = "2021-05-20T09:00:00"))
        val blockedOnOdd = DateGatedStrategy(always) { it.dayOfMonth % 2 == 0 }
        assertTrue(blockedOnOdd.shouldBuy(window, 1.0, TradingProperties()))
        assertFalse(blockedOnOdd.shouldBuy(listOf(window[0].copy(candleDateTimeKst = "2021-05-21T09:00:00")), 1.0, TradingProperties()))
        assertFalse(blockedOnOdd.shouldBuy(listOf(window[0].copy(candleDateTimeKst = "2021-05-21T09:00:00")), 1.0, TradingProperties()))
        assertEquals(1, blockedOnOdd.blockedDays.size); assertEquals(1, blockedOnOdd.allowedDays.size)
        val innerRefuses = DateGatedStrategy(never) { false }
        assertFalse(innerRefuses.shouldBuy(window, 1.0, TradingProperties()))
        assertEquals(0, innerRefuses.blockedDays.size, "안의 전략이 안 사면 게이트가 막은 것이 아니다")
        assertEquals("combined", blockedOnOdd.name)
    }
}
