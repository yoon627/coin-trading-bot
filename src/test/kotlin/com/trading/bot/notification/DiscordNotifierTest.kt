package com.trading.bot.notification

import com.trading.bot.api.RequestValidators
import com.trading.bot.config.DiscordProperties
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

class DiscordNotifierTest {

    private lateinit var webClient: WebClient
    private lateinit var requestSpec: WebClient.RequestBodyUriSpec
    private lateinit var requestBodySpec: WebClient.RequestBodySpec
    private lateinit var responseSpec: WebClient.ResponseSpec
    private lateinit var notifier: DiscordNotifier

    @BeforeEach
    fun setup() {
        webClient = mockk()
        requestSpec = mockk()
        requestBodySpec = mockk()
        responseSpec = mockk()

        every { webClient.post() } returns requestSpec
        every { requestSpec.uri(any<String>()) } returns requestBodySpec
        every { requestBodySpec.bodyValue(any()) } returns requestBodySpec
        every { requestBodySpec.retrieve() } returns responseSpec
        every { responseSpec.bodyToMono(String::class.java) } returns Mono.just("ok")

        val discordProperties = DiscordProperties(webhookUrl = "https://discord.com/api/webhooks/123/abc")
        val requestValidators = RequestValidators()
        notifier = DiscordNotifier(webClient, discordProperties, requestValidators)
    }

    @Test
    fun `sendTradeEmbed sends BUY notification with correct fields`() {
        val record = TradeRecord(
            ticker = "KRW-BTC",
            side = TradeSide.BUY,
            price = 50000000.0,
            volume = 0.002,
            totalAmount = 100000.0,
            strategy = "volatility_breakout",
            userId = 1L,
        )

        notifier.sendTradeEmbed(record, krwBalance = 900000.0, username = "testuser")

        verify { webClient.post() }
        verify { requestSpec.uri("https://discord.com/api/webhooks/123/abc") }
        verify { requestBodySpec.bodyValue(match<Map<String, Any>> { payload ->
            val embeds = payload["embeds"] as? List<*>
            embeds != null && embeds.isNotEmpty()
        }) }
    }

    @Test
    fun `sendTradeEmbed sends SELL notification with PnL`() {
        val record = TradeRecord(
            ticker = "KRW-BTC",
            side = TradeSide.SELL,
            price = 52000000.0,
            volume = 0.002,
            totalAmount = 104000.0,
            pnlPercent = 4.0,
            reason = "TAKE_PROFIT",
            strategy = "rsi_bounce",
            userId = 1L,
        )

        notifier.sendTradeEmbed(record, krwBalance = 1004000.0, username = "testuser")

        verify { requestBodySpec.bodyValue(any()) }
    }

    @Test
    fun `sendTradeEmbed uses user webhook URL when provided`() {
        val record = TradeRecord(
            ticker = "KRW-BTC",
            side = TradeSide.BUY,
            price = 50000000.0,
            volume = 0.002,
            totalAmount = 100000.0,
            userId = 1L,
        )

        val customUrl = "https://discord.com/api/webhooks/456/def"
        notifier.sendTradeEmbed(record, webhookUrl = customUrl)

        verify { requestSpec.uri(customUrl) }
    }

    @Test
    fun `sendTradeEmbed skips when no webhook configured`() {
        val properties = DiscordProperties(webhookUrl = "")
        val notifierNoUrl = DiscordNotifier(webClient, properties, RequestValidators())

        val record = TradeRecord(
            ticker = "KRW-BTC",
            side = TradeSide.BUY,
            price = 50000000.0,
            volume = 0.002,
            totalAmount = 100000.0,
            userId = 1L,
        )

        notifierNoUrl.sendTradeEmbed(record)

        verify(exactly = 0) { webClient.post() }
    }

    @Test
    fun `sendTradeEmbed skips when webhook URL is invalid`() {
        val record = TradeRecord(
            ticker = "KRW-BTC",
            side = TradeSide.BUY,
            price = 50000000.0,
            volume = 0.002,
            totalAmount = 100000.0,
            userId = 1L,
        )

        notifier.sendTradeEmbed(record, webhookUrl = "http://evil.com/steal")

        verify(exactly = 0) { webClient.post() }
    }
}
