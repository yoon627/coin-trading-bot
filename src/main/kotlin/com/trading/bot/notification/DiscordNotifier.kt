package com.trading.bot.notification

import com.trading.bot.config.DiscordProperties
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

@Component
class DiscordNotifier(
    private val discordWebClient: WebClient,
    private val discordProperties: DiscordProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun sendMessage(message: String) {
        sendPayload(mapOf("content" to message))
    }

    fun sendTradeEmbed(record: TradeRecord, krwBalance: Double? = null) {
        val isBuy = record.side == TradeSide.BUY
        val color = if (isBuy) 0x22C55E else if ((record.pnlPercent ?: 0.0) >= 0) 0x3B82F6 else 0xEF4444
        val title = if (isBuy) "📈 매수" else "📉 매도"
        val pnlText = record.pnlPercent?.let { "%+.2f%%".format(it) } ?: "-"

        val fields = mutableListOf(
            mapOf("name" to "티커", "value" to record.ticker, "inline" to true),
            mapOf("name" to "가격", "value" to "%,.0f원".format(record.price), "inline" to true),
            mapOf("name" to "금액", "value" to "%,.0f원".format(record.totalAmount), "inline" to true),
        )

        if (!isBuy) {
            fields.add(mapOf("name" to "수익률", "value" to "**$pnlText**", "inline" to true))
            fields.add(mapOf("name" to "사유", "value" to (record.reason ?: "-"), "inline" to true))
        }

        if (record.strategy != null) {
            fields.add(mapOf("name" to "전략", "value" to record.strategy, "inline" to true))
        }

        if (krwBalance != null) {
            fields.add(mapOf("name" to "잔고", "value" to "%,.0f원".format(krwBalance), "inline" to true))
        }

        val embed = mapOf(
            "title" to "$title ${record.ticker}",
            "color" to color,
            "fields" to fields,
            "timestamp" to record.createdAt.toString(),
        )

        sendPayload(mapOf("embeds" to listOf(embed)))
    }

    private fun sendPayload(payload: Map<String, Any>) {
        if (discordProperties.webhookUrl.isBlank()) {
            log.debug("Discord webhook not configured, skipping notification")
            return
        }

        try {
            discordWebClient.post()
                .uri(discordProperties.webhookUrl)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono<String>()
                .onErrorResume { e ->
                    log.warn("Discord notification failed: {}", e.message)
                    Mono.empty()
                }
                .subscribe(
                    { log.debug("Discord notification sent") },
                    { e -> log.warn("Discord notification error: {}", e.message) },
                )
        } catch (e: Exception) {
            log.warn("Failed to send Discord notification: {}", e.message)
        }
    }
}
