package com.trading.research.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import com.trading.research.domain.OrderRequest
import com.trading.research.domain.OrderSide
import com.trading.research.domain.SizingRule
import com.trading.research.domain.toNormalizedCandle

/**
 * Bridges the legacy [TradingStrategy] (shouldBuy-only) into [ResearchStrategy].
 *
 * Exit logic is intentionally not carried over — the research engine's
 * [com.trading.research.risk.RiskManager] owns exit semantics so every adapted
 * strategy is evaluated under the same stop/take-profit/time-exit rules.
 *
 * Ordering note: legacy indicators expect the **most recent candle at index 0**
 * (see [com.trading.common.strategy.Indicators.calculateTargetPrice]). The
 * research universe returns bars oldest-first, so we reverse before delegating.
 */
class LegacyStrategyAdapter(
    private val legacy: TradingStrategy,
    private val sizing: SizingRule,
    private val props: TradingProperties,
    override val warmupBars: Int = DEFAULT_WARMUP_BARS,
) : ResearchStrategy {

    override val name: String = "legacy:${legacy.name}"

    override suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest> {
        if (ctx.portfolio.hasPosition(event.asset)) return emptyList()

        val bars = ctx.universe.recentBars(event.asset, warmupBars)
        if (bars.size < warmupBars) return emptyList()

        // Legacy indicators read candles[0] as the most-recent bar; reverse to match.
        val normalized = bars.asReversed().map { it.toNormalizedCandle(event.asset) }
        val shouldBuy = legacy.shouldBuyNormalized(normalized, event.bar.close, props)
        return if (shouldBuy) {
            listOf(OrderRequest(event.asset, OrderSide.BUY, sizing, tag = "entry"))
        } else {
            emptyList()
        }
    }

    companion object {
        /** Default lookback window; enough for MACD (26+9 = 35) with headroom. */
        private const val DEFAULT_WARMUP_BARS = 50
    }
}
