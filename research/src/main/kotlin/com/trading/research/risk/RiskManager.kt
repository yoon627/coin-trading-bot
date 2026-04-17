package com.trading.research.risk

import com.trading.research.domain.OrderRequest
import com.trading.research.domain.OrderSide
import com.trading.research.domain.SizingRule
import com.trading.research.portfolio.Position

class RiskManager(private val policy: RiskPolicy) {
    fun evaluate(position: Position, lastPrice: Double, currentBarIndex: Long): List<OrderRequest> {
        val entry = position.avgEntryPrice
        val peak = position.peakPriceSinceEntry
        val pnlFrac = (lastPrice - entry) / entry

        val reason: String? = when {
            policy.stopLossPct != null && pnlFrac <= -policy.stopLossPct -> "STOP_LOSS"
            policy.trailingStopPct != null && pnlFrac > 0.0 && ((peak - lastPrice) / peak) >= policy.trailingStopPct -> "TRAILING_STOP"
            policy.takeProfitPct != null && pnlFrac >= policy.takeProfitPct -> "TAKE_PROFIT"
            policy.timeExitBars != null && (currentBarIndex - position.openedAtBarIndex) >= policy.timeExitBars -> "TIME_EXIT"
            else -> null
        }

        return if (reason == null) emptyList()
        else listOf(OrderRequest(position.asset, OrderSide.SELL, SizingRule.CloseAll, tag = reason))
    }
}
