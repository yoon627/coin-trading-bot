package com.trading.research.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import com.trading.common.strategy.TradingStrategy
import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import com.trading.research.domain.OrderSide
import com.trading.research.domain.SizingRule
import com.trading.research.engine.ResearchClock
import com.trading.research.portfolio.Fill
import com.trading.research.portfolio.Portfolio
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class LegacyStrategyAdapterTest {

    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    private class AlwaysBuy : TradingStrategy {
        override val name = "AlwaysBuy"
        override suspend fun shouldBuy(
            candles: List<com.trading.common.domain.Candle>,
            currentPrice: Double,
            config: TradingProperties,
        ): Boolean = true
    }

    private fun makeContext(portfolio: Portfolio, bar: Bar, history: Int = 5): ResearchContext =
        ResearchContextImpl(
            clock = ResearchClock(),
            portfolio = portfolio.view(),
            universe = RollingUniverseView(
                history = mapOf(asset to List(history) { bar }),
                currentBarIndex = mapOf(asset to (history - 1L)),
            ),
            params = emptyMap(),
        )

    private fun sampleBar(close: Double = 100.0): Bar = Bar(
        Instant.parse("2024-01-01T00:00:00Z"),
        Instant.parse("2024-01-02T00:00:00Z"),
        close, close, close, close, 1.0, close,
    )

    @Test
    fun `emits single BUY when no position and strategy says yes`() = runTest {
        val adapter = LegacyStrategyAdapter(
            legacy = AlwaysBuy(),
            sizing = SizingRule.FixedFraction(0.1),
            props = TradingProperties(),
            warmupBars = 5,
        )
        val bar = sampleBar()
        val event = BarEvent(asset, bar, IndicatorSnapshot.EMPTY, barIndex = 4L)

        val orders = adapter.onBar(makeContext(Portfolio(10_000.0), bar), event)

        assertEquals(1, orders.size)
        assertEquals(OrderSide.BUY, orders[0].side)
    }

    @Test
    fun `emits nothing when already has position`() = runTest {
        val adapter = LegacyStrategyAdapter(
            legacy = AlwaysBuy(),
            sizing = SizingRule.FixedFraction(0.1),
            props = TradingProperties(),
            warmupBars = 5,
        )
        val portfolio = Portfolio(10_000.0).apply {
            applyFill(Fill(asset, OrderSide.BUY, 1.0, 100.0, 0.0, "t"))
        }
        val bar = sampleBar()
        val event = BarEvent(asset, bar, IndicatorSnapshot.EMPTY, barIndex = 4L)

        val orders = adapter.onBar(makeContext(portfolio, bar), event)
        assertEquals(0, orders.size)
    }
}
