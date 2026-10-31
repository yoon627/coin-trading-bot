package com.trading.bot.config

import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val jwtSecret: String = "",
    val jwtExpirationMs: Long = 86_400_000,
    val encryptionSecret: String = "",
)

@ConfigurationProperties(prefix = "upbit")
data class UpbitProperties(
    val baseUrl: String = "https://api.upbit.com",
)

@ConfigurationProperties(prefix = "watchlist")
data class WatchlistProperties(
    val tickers: String = "KRW-BTC,KRW-ETH,KRW-XRP,KRW-SOL,KRW-DOGE,KRW-ADA,KRW-AVAX,KRW-LINK,KRW-DOT,KRW-SHIB,KRW-NEAR,KRW-SUI,KRW-HBAR",
) {
    fun tickerList(): List<String> =
        tickers.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()

    // KRW_BTC 같은 항목이 섞이면 구독 메시지를 만들다 예외가 나 모든 마켓의 시세가 끊기므로 형식이 틀린 항목은 구독하지 않는다.
    fun validTickers(): List<String> = tickerList().filter { MarketPair.isValid(Exchange.UPBIT, it) }

    fun invalidTickers(): List<String> = tickerList() - validTickers().toSet()
}

/**
 * 시세 WS 무수신 워치독 설정. 매매는 엔진의 staleness 검사가 따로 막으므로 이건 피드 복구용이고,
 * 저유동 마켓에서 오판해 재연결이 반복되면 enabled 로 끌 수 있다.
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
