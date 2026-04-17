package com.trading.research.execution

import com.trading.research.domain.Asset
import com.trading.research.domain.OrderRequest
import com.trading.research.domain.OrderSide
import com.trading.research.portfolio.Fill
import com.trading.research.portfolio.Portfolio
import com.trading.research.sizing.SizingCalculator

/**
 * Converts pending [OrderRequest]s into [Fill] records at the next bar's open price,
 * applying the configured [CostModel] (slippage + fee) and [SizingCalculator] rules.
 *
 * v1 semantics:
 *  - Entry fills ([fill]) only consume BUY orders; size target comes from [SizingRule] applied to equity.
 *  - Exit fills ([fillExit]) only consume SELL orders; quantity equals the existing position.
 *  - Assets missing an open price or lacking a position are silently skipped (graceful degradation).
 */
class FillSimulator(
    private val costModel: CostModel,
    private val sizer: SizingCalculator = SizingCalculator,
) {
    /** Entry fills: sizes via SizingRule against equity. Skips assets without open price. */
    fun fill(
        orders: List<OrderRequest>,
        openPrices: Map<Asset, Double>,
        equityBefore: Double,
        assetDailyVol: Map<Asset, Double>,
        barIndex: Long,
    ): List<Fill> = orders.mapNotNull { order ->
        if (order.side == OrderSide.SELL) return@mapNotNull null
        val open = openPrices[order.asset] ?: return@mapNotNull null
        val notional = sizer.notional(order.sizing, equityBefore, assetDailyVol[order.asset] ?: 0.0)
        if (notional <= 0.0) return@mapNotNull null
        val fillPrice = costModel.applySlippage(open, order.side)
        val quantity = notional / fillPrice
        val fee = costModel.applyFee(quantity * fillPrice, order.side)
        Fill(order.asset, order.side, quantity, fillPrice, fee, order.tag, barIndex)
    }

    /** Exit fills: uses existing position quantity. Skips assets without open price or position. */
    fun fillExit(
        orders: List<OrderRequest>,
        openPrices: Map<Asset, Double>,
        portfolio: Portfolio,
        barIndex: Long,
    ): List<Fill> = orders.mapNotNull { order ->
        if (order.side != OrderSide.SELL) return@mapNotNull null
        val open = openPrices[order.asset] ?: return@mapNotNull null
        val position = portfolio.positions[order.asset] ?: return@mapNotNull null
        val fillPrice = costModel.applySlippage(open, OrderSide.SELL)
        val quantity = position.quantity
        val fee = costModel.applyFee(quantity * fillPrice, OrderSide.SELL)
        Fill(order.asset, OrderSide.SELL, quantity, fillPrice, fee, order.tag, barIndex)
    }
}
