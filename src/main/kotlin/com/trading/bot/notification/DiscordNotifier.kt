package com.trading.bot.notification

import com.trading.bot.config.DiscordProperties
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
        if (discordProperties.webhookUrl.isBlank()) {
            log.debug("Discord webhook not configured, skipping: {}", message)
            return
        }

        try {
            discordWebClient.post()
                .uri(discordProperties.webhookUrl)
                .bodyValue(mapOf("content" to message))
                .retrieve()
                .bodyToMono<String>()
                .onErrorResume { e ->
                    log.warn("Discord notification failed: {}", e.message)
                    Mono.empty()
                }
                .subscribe()
        } catch (e: Exception) {
            log.warn("Failed to send Discord notification: {}", e.message)
        }
    }
}
