package com.trading.research.risk

data class RiskPolicy(
    val stopLossPct: Double? = 0.03,
    val trailingStopPct: Double? = 0.02,
    val takeProfitPct: Double? = 0.05,
    val timeExitBars: Int? = 7,
)
