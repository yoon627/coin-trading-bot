package com.trading.research.strategy

data class IndicatorSnapshot(
    val rsi14: Double? = null,
    val ma20: Double? = null,
    val ma50: Double? = null,
    val macdSignal: Double? = null,
    val macdValue: Double? = null,
    val bollUpper: Double? = null,
    val bollLower: Double? = null,
    val atr14: Double? = null,
    val realizedVol20: Double? = null, // daily stdev of returns
) {
    companion object {
        val EMPTY = IndicatorSnapshot()
    }
}
