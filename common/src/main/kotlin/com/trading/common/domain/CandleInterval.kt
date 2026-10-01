package com.trading.common.domain

enum class CandleInterval(val minutes: Int, val label: String) {
    M1(1, "1m"),
    M5(5, "5m"),
    M15(15, "15m"),
    H1(60, "1h"),
    H4(240, "4h"),
    D1(1440, "1d"),
    W1(10080, "1w"),
    MO1(43200, "1M"),
}
