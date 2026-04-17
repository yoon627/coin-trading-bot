package com.trading.research.strategy

import com.trading.research.engine.ResearchClock
import com.trading.research.portfolio.PortfolioView

interface ResearchContext {
    val clock: ResearchClock
    val portfolio: PortfolioView
    val universe: UniverseView
    val params: Map<String, Any>
}

data class ResearchContextImpl(
    override val clock: ResearchClock,
    override val portfolio: PortfolioView,
    override val universe: UniverseView,
    override val params: Map<String, Any>,
) : ResearchContext
