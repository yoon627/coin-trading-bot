package com.trading.bot.domain

import com.fasterxml.jackson.annotation.JsonProperty

data class Account(
    val currency: String = "",
    val balance: String = "0",
    val locked: String = "0",
    @JsonProperty("avg_buy_price")
    val avgBuyPrice: String = "0",
    @JsonProperty("avg_buy_price_modified")
    val avgBuyPriceModified: Boolean = false,
    @JsonProperty("unit_currency")
    val unitCurrency: String = "KRW",
) {
    fun balanceDouble(): Double = balance.toDoubleOrNull() ?: 0.0
    fun lockedDouble(): Double = locked.toDoubleOrNull() ?: 0.0
    fun avgBuyPriceDouble(): Double = avgBuyPrice.toDoubleOrNull() ?: 0.0
    fun totalBalance(): Double = balanceDouble() + lockedDouble()
}
