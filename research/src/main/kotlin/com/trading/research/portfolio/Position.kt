package com.trading.research.portfolio

import com.trading.research.domain.Asset

/**
 * Mutable open position for a single [Asset].
 *
 * [marketValue] is updated by [updateMarket] and reflects the latest mark price;
 * [peakPriceSinceEntry] tracks the highest observed price since entry for trailing-stop logic.
 */
data class Position(
    val asset: Asset,
    var quantity: Double,
    var avgEntryPrice: Double,
    var peakPriceSinceEntry: Double,
    val openedAtBarIndex: Long,
) {
    var marketValue: Double = quantity * avgEntryPrice
        private set

    fun updateMarket(lastPrice: Double) {
        marketValue = quantity * lastPrice
        if (lastPrice > peakPriceSinceEntry) peakPriceSinceEntry = lastPrice
    }

    fun unrealizedPnl(lastPrice: Double): Double = (lastPrice - avgEntryPrice) * quantity
}
