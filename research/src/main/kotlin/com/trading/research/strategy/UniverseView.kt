package com.trading.research.strategy

import com.trading.research.domain.Asset
import com.trading.research.domain.Bar

interface UniverseView {
    val assets: List<Asset>
    fun recentBars(asset: Asset, count: Int): List<Bar>
}

/** Simple implementation backed by full history + current index per asset. */
class RollingUniverseView(
    private val history: Map<Asset, List<Bar>>,
    private var currentBarIndex: Map<Asset, Long>,
) : UniverseView {
    override val assets: List<Asset> = history.keys.toList()

    override fun recentBars(asset: Asset, count: Int): List<Bar> {
        val all = history[asset] ?: return emptyList()
        val idx = (currentBarIndex[asset] ?: 0L).toInt()
        val to = (idx + 1).coerceAtMost(all.size)
        val from = (to - count).coerceAtLeast(0)
        return all.subList(from, to)
    }

    fun advance(asset: Asset, newIndex: Long) {
        currentBarIndex = currentBarIndex.toMutableMap().also { it[asset] = newIndex }
    }
}
