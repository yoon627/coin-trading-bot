package com.trading.research.strategy

import com.trading.research.domain.OrderRequest

interface ResearchStrategy {
    val name: String
    val warmupBars: Int

    suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest>
}
