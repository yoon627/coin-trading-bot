package com.trading.research.strategy

import com.trading.research.domain.Asset
import com.trading.research.domain.Bar

data class BarEvent(
    val asset: Asset,
    val bar: Bar,
    val indicators: IndicatorSnapshot,
    val barIndex: Long,
)
