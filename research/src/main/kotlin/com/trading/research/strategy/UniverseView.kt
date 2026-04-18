package com.trading.research.strategy

import com.trading.research.domain.Asset
import com.trading.research.domain.Bar

interface UniverseView {
    val assets: List<Asset>
    fun recentBars(asset: Asset, count: Int): List<Bar>
}

/**
 * Simple implementation backed by full history + current index per asset.
 *
 * An asset absent from [currentBarIndex] has not yet received a [com.trading.research.strategy.BarEvent],
 * meaning the simulation clock has not reached its first bar. [recentBars] returns an empty list
 * in that case to prevent cross-asset strategies from peeking at bars whose close-time is still in
 * the future relative to the current clock.
 */
class RollingUniverseView(
    private val history: Map<Asset, List<Bar>>,
    private var currentBarIndex: Map<Asset, Long>,
) : UniverseView {
    override val assets: List<Asset> = history.keys.toList()

    override fun recentBars(asset: Asset, count: Int): List<Bar> {
        val all = history[asset] ?: return emptyList()
        val idx = currentBarIndex[asset]?.toInt() ?: return emptyList()
        val to = (idx + 1).coerceAtMost(all.size)
        val from = (to - count).coerceAtLeast(0)
        return all.subList(from, to)
    }

    fun advance(asset: Asset, newIndex: Long) {
        currentBarIndex = currentBarIndex.toMutableMap().also { it[asset] = newIndex }
    }
}
