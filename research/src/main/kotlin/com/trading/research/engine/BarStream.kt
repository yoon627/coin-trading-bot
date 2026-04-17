package com.trading.research.engine

import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import com.trading.research.strategy.BarEvent
import com.trading.research.strategy.IndicatorSnapshot

/**
 * Deterministic chronological merge of per-asset bar histories.
 *
 * Emits [BarEvent]s ordered by [Bar.closeTime] ascending. Ties on close time
 * are broken by [Asset.toString] (alphabetical) so runs with the same input
 * always produce the same event sequence — critical for backtest reproducibility.
 */
class BarStream(private val history: Map<Asset, List<Bar>>) : Iterable<BarEvent> {

    override fun iterator(): Iterator<BarEvent> = sequence {
        val ordered = history.flatMap { (asset, bars) ->
            bars.mapIndexed { index, bar -> Triple(asset, index, bar) }
        }.sortedWith(compareBy({ it.third.closeTime }, { it.first.toString() }))

        for ((asset, index, bar) in ordered) {
            yield(
                BarEvent(
                    asset = asset,
                    bar = bar,
                    indicators = IndicatorSnapshot.EMPTY,
                    barIndex = index.toLong(),
                ),
            )
        }
    }.iterator()
}
