package com.trading.bot.domain

import com.fasterxml.jackson.annotation.JsonProperty

data class Order(
    val uuid: String = "",
    val side: String = "",
    @JsonProperty("ord_type")
    val ordType: String = "",
    val price: String? = null,
    val state: String = "",
    val market: String = "",
    val volume: String? = null,
    @JsonProperty("remaining_volume")
    val remainingVolume: String? = null,
    @JsonProperty("executed_volume")
    val executedVolume: String? = null,
    @JsonProperty("trades_count")
    val tradesCount: Int = 0,
)

data class OrderRequest(
    val market: String,
    val side: String,
    @JsonProperty("ord_type")
    val ordType: String,
    val volume: String? = null,
    val price: String? = null,
) {
    fun toQueryString(): String {
        return toParamMap().entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    fun toParamMap(): Map<String, String> {
        val params = mutableMapOf(
            "market" to market,
            "side" to side,
            "ord_type" to ordType,
        )
        volume?.let { params["volume"] = it }
        price?.let { params["price"] = it }
        return params
    }
}

data class Ticker(
    val market: String = "",
    @JsonProperty("trade_price")
    val tradePrice: Double = 0.0,
    @JsonProperty("high_price")
    val highPrice: Double = 0.0,
    @JsonProperty("low_price")
    val lowPrice: Double = 0.0,
    @JsonProperty("acc_trade_volume_24h")
    val accTradeVolume24h: Double = 0.0,
    @JsonProperty("signed_change_rate")
    val signedChangeRate: Double = 0.0,
    @JsonProperty("acc_trade_price_24h")
    val accTradePrice24h: Double = 0.0,
)
