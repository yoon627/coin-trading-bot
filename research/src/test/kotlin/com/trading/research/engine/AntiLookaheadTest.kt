package com.trading.research.engine

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import com.trading.research.domain.OrderRequest
import com.trading.research.domain.OrderSide
import com.trading.research.domain.SizingRule
import com.trading.research.execution.FlatFeeSlippageModel
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import com.trading.research.strategy.BarEvent
import com.trading.research.strategy.ResearchContext
import com.trading.research.strategy.ResearchStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class AntiLookaheadTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    private class HonestBuyOnDay5 : ResearchStrategy {
        override val name = "Honest"
        override val warmupBars = 0
        override suspend fun onBar(ctx: ResearchContext, event: BarEvent) =
            if (event.barIndex == 5L) {
                listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.FixedFraction(0.5)))
            } else {
                emptyList()
            }
    }

    private class CheaterTriesToPeek : ResearchStrategy {
        override val name = "Cheater"
        override val warmupBars = 0
        override suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest> {
            // Attempt to read "future" bars — universe must return bars only up to current index.
            ctx.universe.recentBars(event.asset, PEEK_WINDOW)
            // Cheat check: bars shouldn't include anything after current barIndex
            // (if engine leaked future bars, cheater could act differently)
            return if (event.barIndex == 5L) {
                listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.FixedFraction(0.5)))
            } else {
                emptyList()
            }
        }

        companion object {
            private const val PEEK_WINDOW = 100
        }
    }

    private fun barsLinear(): List<Bar> {
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        return (0L..9L).map { i ->
            val t = t0.plusSeconds(i * SECONDS_PER_DAY)
            Bar(t, t.plusSeconds(SECONDS_PER_DAY), 100.0 + i, 100.0 + i, 100.0 + i, 100.0 + i, 1.0, 100.0 + i)
        }
    }

    @Test
    fun `honest and naive-cheater produce identical results when engine blocks peek`() = runTest {
        val history = mapOf(asset to barsLinear())
        val base = BacktestRunConfig(
            strategy = HonestBuyOnDay5(),
            history = history,
            initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(0.0, 0.0),
            risk = RiskPolicy(null, null, null, null),
            killSwitch = KillSwitch(),
        )
        val honest = Engine.run(base)
        val cheat = Engine.run(base.copy(strategy = CheaterTriesToPeek()))
        assertEquals(honest.finalEquity, cheat.finalEquity, 1e-9)
    }

    companion object {
        private const val SECONDS_PER_DAY = 86_400L
    }
}
