package com.trading.research.portfolio

import com.trading.research.domain.Asset

/**
 * Read-only projection of a [Portfolio] handed to strategies/risk managers that must not mutate state.
 */
interface PortfolioView {
    val cash: Double
    val totalEquity: Double
    fun hasPosition(asset: Asset): Boolean
    fun getPosition(asset: Asset): Position?
}

internal class PortfolioViewImpl(private val portfolio: Portfolio) : PortfolioView {
    override val cash: Double get() = portfolio.cash
    override val totalEquity: Double get() = portfolio.totalEquity
    override fun hasPosition(asset: Asset): Boolean = portfolio.hasPosition(asset)
    override fun getPosition(asset: Asset): Position? = portfolio.positions[asset]
}
