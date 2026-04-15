package com.trading.collector.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "collector")
data class CollectorProperties(
    val upbit: ExchangeConfig = ExchangeConfig(),
    val binance: ExchangeConfig = ExchangeConfig(),
    val kis: KisExchangeConfig = KisExchangeConfig(),
)

data class ExchangeConfig(
    val enabled: Boolean = false,
    val markets: String = "",
) {
    fun marketList(): List<String> = markets.split(",").map { it.trim() }.filter { it.isNotBlank() }
}

data class KisExchangeConfig(
    val enabled: Boolean = false,
    val markets: String = "",
    val appKey: String = "",
    val appSecret: String = "",
) {
    fun marketList(): List<String> = markets.split(",").map { it.trim() }.filter { it.isNotBlank() }
}
