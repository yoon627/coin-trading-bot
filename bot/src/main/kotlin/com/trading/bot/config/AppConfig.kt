package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val jwtSecret: String = "",
    val jwtExpirationMs: Long = 86_400_000,
    val encryptionSecret: String = "",
)

@ConfigurationProperties(prefix = "upbit")
data class UpbitProperties(
    val accessKey: String = "",
    val secretKey: String = "",
    val baseUrl: String = "https://api.upbit.com",
)

@ConfigurationProperties(prefix = "watchlist")
data class WatchlistProperties(
    val tickers: String = "KRW-BTC,KRW-ETH,KRW-XRP,KRW-SOL",
) {
    fun tickerList(): List<String> = tickers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

@ConfigurationProperties(prefix = "discord")
data class DiscordProperties(
    val webhookUrl: String = "",
)
