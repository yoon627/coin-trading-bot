package com.trading.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "trading")
data class TradingProperties(
    val tickers: String = "KRW-BTC",
    val strategy: String = "volatility_breakout",
    val investRatio: Double = 0.1,
    val maxInvestAmount: Double = 100_000.0,
    val kValue: Double = 0.5,
    val takeProfitPct: Double = 2.0,
    val maxLossPct: Double = 5.0,
    val trailingStopPct: Double = 2.0,
    val intervalSeconds: Long = 10,
    val autoStart: Boolean = false,
) {
    fun tickerList(): List<String> = tickers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
