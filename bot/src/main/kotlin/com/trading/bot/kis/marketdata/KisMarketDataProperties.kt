package com.trading.bot.kis.marketdata

import org.springframework.boot.context.properties.ConfigurationProperties

/** 주식 시세수집 watchlist/주기. 비우면 수집 안 함(엔진은 per-user REST 폴백). */
@ConfigurationProperties(prefix = "kis.marketdata")
data class KisMarketDataProperties(
    val symbols: String = "",
    val pricePollSeconds: Long = 3,
    val candleRefreshSeconds: Long = 300,
) {
    fun symbolList(): List<String> = symbols.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
