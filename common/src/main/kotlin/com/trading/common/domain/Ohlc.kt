package com.trading.common.domain

/** Candle 과 NormalizedCandle 이 같은 지표 계산을 쓰도록 Indicators 가 이 타입만 받는다. */
interface Ohlc {
    val open: Double
    val high: Double
    val low: Double
    val close: Double
}
