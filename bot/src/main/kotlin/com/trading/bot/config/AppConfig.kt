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
    fun tickerList(): List<String> =
        tickers.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
}

/**
 * 시세 수집 WS 무수신(half-open) 자동복구 워치독 설정.
 * 매매 정확성은 TradingEngine 의 30s staleness 게이팅+REST 폴백이 이미 보호하므로 이 워치독은 피드 복구용 —
 * 오판(저유동 마켓 등) 시 재연결 폭주를 막기 위해 enabled 로 런타임 비활성(kill-switch)할 수 있게 property 화한다.
 */
@ConfigurationProperties(prefix = "marketdata.watchdog")
data class MarketDataWatchdogProperties(
    val enabled: Boolean = true,
    val staleMs: Long = 60_000,
    val intervalMs: Long = 20_000,
    val initialDelayMs: Long = 30_000,
    val restartBackoffMs: Long = 1_000,
) {
    fun isStale(now: Long, lastReceivedAt: Long): Boolean =
        lastReceivedAt > 0 && now - lastReceivedAt > staleMs
}

@ConfigurationProperties(prefix = "discord")
data class DiscordProperties(
    val webhookUrl: String = "",
)

@ConfigurationProperties(prefix = "discord.error-alert")
data class ErrorAlertProperties(
    val enabled: Boolean = false,
    val webhookUrl: String = "",
)
