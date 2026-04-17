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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class EngineSmokeTest {

    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    /** Strategy that buys on bar 5 and never exits voluntarily. */
    private class BuyOnceStrategy : ResearchStrategy {
        override val name = "BuyOnce"
        override val warmupBars = 0
        override suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest> {
            return if (event.barIndex == 5L && !ctx.portfolio.hasPosition(event.asset))
                listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.FixedFraction(0.5)))
            else emptyList()
        }
    }

    @Test
    fun `engine runs 10 bars and produces result with one trade`() = runTest {
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        val bars = (0L..9L).map { i ->
            val t = t0.plusSeconds(i * 86400)
            Bar(t, t.plusSeconds(86400), 100.0 + i, 100.0 + i, 100.0 + i, 100.0 + i, 1.0, 100.0 + i)
        }

        val config = BacktestRunConfig(
            strategy = BuyOnceStrategy(),
            history = mapOf(asset to bars),
            initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(feeRate = 0.0, slippageBps = 0.0),
            risk = RiskPolicy(stopLossPct = null, trailingStopPct = null, takeProfitPct = null, timeExitBars = null),
            killSwitch = KillSwitch(),
        )

        val result = Engine.run(config)

        assertEquals(1, result.fills.size) // only the entry filled (no exit rule, simulation end closes)
        assertTrue(result.equityCurve.size >= 10)
    }
}
