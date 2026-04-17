package com.trading.research.walkforward

/**
 * Cartesian enumeration of parameter axes for grid-search optimization.
 *
 * Emits one [Map] per combination via a lazy [Sequence] so the walk-forward runner
 * can evaluate grids with thousands of points without materializing them upfront.
 * Key ordering in emitted maps follows the input map's iteration order, which gives
 * deterministic combination tuples for reproducible optimization runs.
 */
class ParameterGrid(private val axes: Map<String, List<Any>>) {

    fun combinations(): Sequence<Map<String, Any>> = sequence {
        if (axes.isEmpty()) {
            yield(emptyMap())
            return@sequence
        }
        val keys = axes.keys.toList()
        val valueLists = keys.map { axes.getValue(it) }
        yieldAll(buildCombinations(keys, valueLists, 0, emptyMap()))
    }

    private fun buildCombinations(
        keys: List<String>,
        valueLists: List<List<Any>>,
        idx: Int,
        acc: Map<String, Any>,
    ): Sequence<Map<String, Any>> = sequence {
        if (idx == keys.size) {
            yield(acc)
            return@sequence
        }
        for (value in valueLists[idx]) {
            yieldAll(buildCombinations(keys, valueLists, idx + 1, acc + (keys[idx] to value)))
        }
    }
}
