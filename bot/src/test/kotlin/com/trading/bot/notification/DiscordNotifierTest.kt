package com.trading.bot.notification

import com.trading.bot.api.RequestValidators
import com.trading.bot.config.DiscordProperties
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
            pnlPercent = null,
            pnlAmount = null,
            strategy = "volatility_breakout",
            userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = null,
        )

        notifier.sendTradeEmbed(record, krwBalance = 900000.0, username = "testuser")

        verify { webClient.post() }
        verify { requestSpec.uri("https://discord.com/api/webhooks/123/abc") }
        verify { requestBodySpec.bodyValue(match<Map<String, Any>> { payload ->
            val embeds = payload["embeds"] as? List<*>
            embeds != null && embeds.isNotEmpty()
        }) }
    }

    // --- 금액 필드의 의미 (#146) ---
    // 엔진 매수의 totalAmount 는 포지션 전체 원가라 "금액"으로 보여주면 추가매수가 총보유 원가로 읽힌다.

    private fun amountField(record: TradeRecord): Pair<String, String> {
        val payload = slot<Map<String, Any>>()
        every { requestBodySpec.bodyValue(capture(payload)) } returns requestBodySpec
        notifier.sendTradeEmbed(record, krwBalance = null, username = "u")
        val embed = (payload.captured["embeds"] as List<*>).single() as Map<*, *>
        val fields = embed["fields"] as List<*>
        val field = fields.map { it as Map<*, *> }.single { it["name"] in setOf("체결금액", "기록 금액(체결 미상)", "금액") }
        return field["name"] as String to field["value"] as String
    }

    private fun record(side: TradeSide, orderAmount: Double?) = TradeRecord(
        ticker = "KRW-BTC", side = side, price = 50000000.0, volume = 0.02, totalAmount = 1_000_000.0,
        pnlPercent = if (side == TradeSide.SELL) 1.0 else null, pnlAmount = null, strategy = "combined",
        userId = 1L, fee = FeeBasis.Estimate, orderAmount = orderAmount,
    )

    @Test
    fun `amount field shows this order's funds when known and a source-neutral label otherwise`() {
        assertEquals("체결금액" to "50,000원", amountField(record(TradeSide.BUY, orderAmount = 50_000.0)))
        assertEquals("기록 금액(체결 미상)" to "1,000,000원", amountField(record(TradeSide.BUY, orderAmount = null)))
        assertEquals("기록 금액(체결 미상)" to "1,000,000원", amountField(record(TradeSide.SELL, orderAmount = null)))
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
            pnlAmount = 4000.0,
            reason = "TAKE_PROFIT",
            strategy = "rsi_bounce",
            userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = null,
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
            pnlPercent = null,
            pnlAmount = null,
            strategy = null, // 전략 미상 분기(수동 매수 포지션)도 embed 를 만들 수 있어야 한다
            userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = null,
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
            pnlPercent = null,
            pnlAmount = null,
            strategy = "combined",
            userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = null,
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
            pnlPercent = null,
            pnlAmount = null,
            strategy = "combined",
            userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = null,
        )

        notifier.sendTradeEmbed(record, webhookUrl = "http://evil.com/steal")

        verify(exactly = 0) { webClient.post() }
    }

    @Test
    fun `sendErrorAlert sends red error embed to given webhook`() {
        notifier.sendErrorAlert(
            loggerName = "com.trading.bot.engine.TradingEngine",
            message = "boom",
            stackSummary = "java.lang.RuntimeException: boom\n  at Foo.bar",
            suppressedSince = 3,
            webhookUrl = "https://discord.com/api/webhooks/789/errwebhook",
        )

        verify { requestSpec.uri("https://discord.com/api/webhooks/789/errwebhook") }

        val payloadSlot = slot<Map<String, Any>>()
        verify { requestBodySpec.bodyValue(capture(payloadSlot)) }
        val embed = (payloadSlot.captured["embeds"] as List<*>).first() as Map<*, *>
        val firstField = (embed["fields"] as List<*>).first() as Map<*, *>
        assertEquals("Logger", firstField["name"]) // error embed 식별 (거래 embed 엔 없는 필드)
    }

    @Test
    fun `sendErrorAlert 는 긴 메시지를 Discord field 1024 한도 이내로 자른다`() {
        notifier.sendErrorAlert(
            loggerName = "logger",
            message = "x".repeat(5000),
            stackSummary = "y".repeat(5000),
            suppressedSince = 0,
            webhookUrl = "https://discord.com/api/webhooks/789/errwebhook",
        )

        val payloadSlot = slot<Map<String, Any>>()
        verify { requestBodySpec.bodyValue(capture(payloadSlot)) }
        val embed = (payloadSlot.captured["embeds"] as List<*>).first() as Map<*, *>
        (embed["fields"] as List<*>).map { it as Map<*, *> }.forEach {
            assertTrue((it["value"] as String).length <= 1024, "field ${it["name"]} exceeds 1024")
        }
    }
}
