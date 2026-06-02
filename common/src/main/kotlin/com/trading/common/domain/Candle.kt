package com.trading.common.domain

import com.fasterxml.jackson.annotation.JsonProperty

data class Candle(
    val market: String = "",
    @JsonProperty("candle_date_time_utc")
    val candleDateTimeUtc: String = "",
    @JsonProperty("candle_date_time_kst")
    val candleDateTimeKst: String = "",
    @JsonProperty("opening_price")
    val openingPrice: Double = 0.0,
    @JsonProperty("high_price")
    val highPrice: Double = 0.0,
    @JsonProperty("low_price")
    val lowPrice: Double = 0.0,
    @JsonProperty("trade_price")
    val tradePrice: Double = 0.0,
    @JsonProperty("candle_acc_trade_price")
    val candleAccTradePrice: Double = 0.0,
    @JsonProperty("candle_acc_trade_volume")
    val candleAccTradeVolume: Double = 0.0,
) : Ohlc {
    override val open get() = openingPrice
    override val high get() = highPrice
    override val low get() = lowPrice
    override val close get() = tradePrice
}
