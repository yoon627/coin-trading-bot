package com.trading.research.portfolio

import com.trading.research.domain.Asset
import com.trading.research.domain.OrderSide

/**
 * A simulated order fill posted against the [Portfolio].
 */
data class Fill(
    val asset: Asset,
    val side: OrderSide,
    val quantity: Double,
    val fillPrice: Double,
    val fee: Double,
    val tag: String,
    val barIndex: Long = 0L,
)

/**
 * In-memory portfolio accounting for the backtest engine.
 *
 * Responsibilities:
 *  - Track cash balance and open [Position]s keyed by [Asset].
 *  - Apply [Fill]s, updating weighted-average entry price on buys and realizing P&L on sells.
 *  - Mark positions to market and recompute [totalEquity].
 */
class Portfolio(initialCash: Double) {
    init {
        require(initialCash >= 0.0) { "initialCash must be non-negative, got $initialCash" }
    }

    var cash: Double = initialCash
        private set

    val positions: MutableMap<Asset, Position> = mutableMapOf()

    var totalEquity: Double = initialCash
        private set

    val realizedPnlByAsset: MutableMap<Asset, Double> = mutableMapOf()

    fun hasPosition(asset: Asset): Boolean = positions[asset]?.let { it.quantity != 0.0 } ?: false

    fun applyFill(fill: Fill) {
        when (fill.side) {
            OrderSide.BUY -> applyBuy(fill)
            OrderSide.SELL -> applySell(fill)
        }
    }

    private fun applyBuy(fill: Fill) {
        val notional = fill.quantity * fill.fillPrice
        cash -= (notional + fill.fee)
        val existing = positions[fill.asset]
        if (existing == null) {
            positions[fill.asset] = Position(
                asset = fill.asset,
                quantity = fill.quantity,
                avgEntryPrice = fill.fillPrice,
                peakPriceSinceEntry = fill.fillPrice,
                openedAtBarIndex = fill.barIndex,
            )
            return
        }
        val totalQty = existing.quantity + fill.quantity
        val newAvg = (existing.avgEntryPrice * existing.quantity + fill.fillPrice * fill.quantity) / totalQty
        existing.quantity = totalQty
        existing.avgEntryPrice = newAvg
    }

    private fun applySell(fill: Fill) {
        val notional = fill.quantity * fill.fillPrice
        cash += (notional - fill.fee)
        val existing = positions[fill.asset]
            ?: error("sell without open position on ${fill.asset}")
        val realized = (fill.fillPrice - existing.avgEntryPrice) * fill.quantity - fill.fee
        realizedPnlByAsset.merge(fill.asset, realized) { previous, delta -> previous + delta }
        existing.quantity -= fill.quantity
        if (existing.quantity <= CLOSED_POSITION_EPS) positions.remove(fill.asset)
    }

    fun markToMarket(lastPrices: Map<Asset, Double>) {
        var marketValueSum = 0.0
        for ((asset, position) in positions) {
            lastPrices[asset]?.let { position.updateMarket(it) }
            marketValueSum += position.marketValue
        }
        totalEquity = cash + marketValueSum
    }

    fun view(): PortfolioView = PortfolioViewImpl(this)

    companion object {
        // Floating-point epsilon below which a residual quantity is treated as "fully closed".
        private const val CLOSED_POSITION_EPS: Double = 1e-12
    }
}
