package com.trading.research.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import com.trading.common.strategy.BollingerBounce
import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.GoldenCross
import com.trading.common.strategy.MacdCross
import com.trading.common.strategy.MeanReversion
import com.trading.common.strategy.RsiBounce
import com.trading.common.strategy.TradingStrategy
import com.trading.common.strategy.VolatilityBreakout
import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import com.trading.research.domain.SizingRule
import com.trading.research.execution.FlatFeeSlippageModel
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import com.trading.research.strategy.LegacyStrategyAdapter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

class LegacyStrategyAcceptanceTest {

    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    /** Synthesize 300 days of noisy trend for smoke-quality acceptance. */
    private fun syntheticBars(): List<Bar> {
        val rng = Random(42)
        val bars = mutableListOf<Bar>()
        var p = 100.0
        val t0 = Instant.parse("2023-01-01T00:00:00Z")
        for (i in 0 until 300) {
            val t = t0.plusSeconds(i * 86400L)
            val delta = rng.nextDouble(-3.0, 3.5)
            val open = p
            val close = (p + delta).coerceAtLeast(1.0)
            val high = maxOf(open, close) + rng.nextDouble(0.0, 2.0)
            val low = minOf(open, close) - rng.nextDouble(0.0, 2.0)
            bars.add(Bar(t, t.plusSeconds(86400), open, high, low, close, 1.0, close))
            p = close
        }
        return bars
    }

    @Test
    fun `each of the 7 legacy strategies runs end to end without exception`() = runTest {
        val bars = syntheticBars()
        val history = mapOf(asset to bars)
        val props = TradingProperties()

        val legacies: List<TradingStrategy> = listOf(
            RsiBounce(), GoldenCross(), MacdCross(), BollingerBounce(),
            MeanReversion(), VolatilityBreakout(), CombinedStrategy(),
        )

        for (legacy in legacies) {
            val adapter = LegacyStrategyAdapter(legacy, SizingRule.FixedFraction(0.1), props)
            val cfg = BacktestRunConfig(
                strategy = adapter,
                history = history,
                initialCash = 10_000.0,
                costModel = FlatFeeSlippageModel(0.0005, 5.0),
                risk = RiskPolicy(),
                killSwitch = KillSwitch(),
            )
            val r = Engine.run(cfg)
            assertNotNull(r)
            assertTrue(r.equityCurve.isNotEmpty(), "equity curve empty for ${legacy.name}")
        }
    }
}
