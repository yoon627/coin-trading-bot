package com.trading.bot.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.api.RequestValidators
import com.trading.bot.config.DiscordProperties
import com.trading.bot.domain.TradeSide
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpRequest
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration

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
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<String>>()) } returns Mono.just("ok")

        val discordProperties = DiscordProperties(webhookUrl = "https://discord.com/api/webhooks/123/abc")
        val requestValidators = RequestValidators()
        notifier = DiscordNotifier(webClient, discordProperties, requestValidators)
    }

    private fun trade(
        side: TradeSide = TradeSide.BUY,
        orderAmount: Double? = null,
        strategy: String? = "combined",
        exchangeOrderId: String? = "order-1",
    ) = TradeNotificationPayload(
        ticker = "KRW-BTC", side = side.name, price = 50000000.0, totalAmount = 1_000_000.0, orderAmount = orderAmount,
        pnlPercent = if (side == TradeSide.SELL) 1.0 else null, reason = if (side == TradeSide.SELL) "TAKE_PROFIT" else null,
        strategy = strategy, exchangeOrderId = exchangeOrderId, createdAt = "2026-11-10T09:00",
    )

    private fun sentEmbed(block: suspend () -> TradeDelivery): Pair<TradeDelivery, Map<*, *>> {
        val payload = slot<Map<String, Any>>()
        every { requestBodySpec.bodyValue(capture(payload)) } returns requestBodySpec
        val result = runBlocking { block() }
        return result to ((payload.captured["embeds"] as List<*>).single() as Map<*, *>)
    }

    private fun fields(embed: Map<*, *>) = (embed["fields"] as List<*>).map { it as Map<*, *> }

    @Test
    fun `a trade notification goes to the global webhook when the user has none`() {
        val (result, embed) = sentEmbed { notifier.deliverTrade(trade(), krwBalance = 900000.0, webhookUrl = null, username = "testuser") }

        assertEquals(TradeDelivery.Sent, result)
        verify { requestSpec.uri("https://discord.com/api/webhooks/123/abc") }
        assertEquals("900,000원", fields(embed).single { it["name"] == "잔고" }["value"])
        assertEquals(mapOf("text" to "testuser"), embed["footer"])
    }

    // 드물게 같은 체결 알림이 두 번 갈 때 주문번호로 같은 건인지 알아본다.
    @Test
    fun `a trade notification carries the order number`() {
        val (_, embed) = sentEmbed { notifier.deliverTrade(trade(exchangeOrderId = "uuid-42"), null, null, null) }

        assertEquals("uuid-42", fields(embed).single { it["name"] == "주문" }["value"])
    }

    // 엔진 매수의 totalAmount 는 포지션 전체 원가라 "금액"으로 보여주면 추가매수가 총보유 원가로 읽힌다.
    private fun amountField(record: TradeNotificationPayload): Pair<String, String> {
        val (_, embed) = sentEmbed { notifier.deliverTrade(record, krwBalance = null, webhookUrl = null, username = "u") }
        val field = fields(embed).single { it["name"] in setOf("체결금액", "기록 금액(체결 미상)", "금액") }
        return field["name"] as String to field["value"] as String
    }

    @Test
    fun `amount field shows this order's funds when known and a source-neutral label otherwise`() {
        assertEquals("체결금액" to "50,000원", amountField(trade(TradeSide.BUY, orderAmount = 50_000.0)))
        assertEquals("기록 금액(체결 미상)" to "1,000,000원", amountField(trade(TradeSide.BUY, orderAmount = null)))
        assertEquals("기록 금액(체결 미상)" to "1,000,000원", amountField(trade(TradeSide.SELL, orderAmount = null)))
    }

    @Test
    fun `a sell notification shows the return and reason, and an unknown strategy still builds`() {
        val (_, embed) = sentEmbed { notifier.deliverTrade(trade(TradeSide.SELL, strategy = null), null, null, null) }

        assertEquals("**+1.00%**", fields(embed).single { it["name"] == "수익률" }["value"])
        assertEquals("TAKE_PROFIT", fields(embed).single { it["name"] == "사유" }["value"])
        assertTrue(fields(embed).none { it["name"] == "전략" })
    }

    @Test
    fun `a trade notification uses the user's webhook when set`() {
        val customUrl = "https://discord.com/api/webhooks/456/def"

        sentEmbed { notifier.deliverTrade(trade(), null, customUrl, null) }

        verify { requestSpec.uri(customUrl) }
    }

    @Test
    fun `a trade notification is skipped only when no webhook is configured anywhere`() = runTest {
        val notifierNoUrl = DiscordNotifier(webClient, DiscordProperties(webhookUrl = ""), RequestValidators())

        assertEquals(TradeDelivery.Skipped, notifierNoUrl.deliverTrade(trade(), null, null, null))
        verify(exactly = 0) { webClient.post() }
    }

    @Test
    fun `an invalid stored webhook fails permanently without a request`() = runTest {
        assertTrue(notifier.deliverTrade(trade(), null, "http://evil.com/steal", null) is TradeDelivery.Permanent)
        verify(exactly = 0) { webClient.post() }
    }

    private fun rejected(status: Int, url: String): WebClientResponseException {
        val request = mockk<HttpRequest>()
        every { request.method } returns HttpMethod.POST
        every { request.uri } returns URI(url)
        every { request.headers } returns HttpHeaders()
        every { request.attributes } returns mutableMapOf()
        return WebClientResponseException.create(status, "status $status", HttpHeaders(), ByteArray(0), null, request)
    }

    // 다시 보낼지는 발송기가 이 분류로 정한다. 없어진 webhook(404)을 24시간 다시 보내면 안 되고, 429·5xx 는 다시 보내야 한다.
    @Test
    fun `a failed trade notification says whether retrying can help, without the webhook token`() = runTest {
        val url = "https://discord.com/api/webhooks/123/secret-token"
        val quick = DiscordNotifier(webClient, DiscordProperties(webhookUrl = ""), RequestValidators(), Duration.ZERO)
        fun failWith(status: Int) {
            every { responseSpec.bodyToMono(any<ParameterizedTypeReference<String>>()) } returns Mono.error(rejected(status, url))
        }

        failWith(404)
        val gone = quick.deliverTrade(trade(), null, url, null)
        failWith(429)
        val limited = quick.deliverTrade(trade(), null, url, null)
        failWith(503)
        val down = quick.deliverTrade(trade(), null, url, null)

        assertTrue(gone is TradeDelivery.Permanent, "$gone")
        assertTrue(limited is TradeDelivery.Retryable, "$limited")
        assertTrue(down is TradeDelivery.Retryable, "$down")
        listOf(gone, limited, down).forEach { assertFalse(it.toString().contains("secret-token"), it.toString()) }
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
        assertEquals("Logger", firstField["name"]) // 거래 embed 에는 없는 필드
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

    @Test
    fun `sendErrorAlert 는 자르는 자리의 서로게이트 쌍을 가르지 않는다`() {
        notifier.sendErrorAlert(
            loggerName = "l".repeat(255) + "😀",
            message = "x".repeat(999) + "😀",
            stackSummary = "y".repeat(949) + "😀",
            suppressedSince = 0,
            webhookUrl = "https://discord.com/api/webhooks/789/errwebhook",
        )

        val payloadSlot = slot<Map<String, Any>>()
        verify { requestBodySpec.bodyValue(capture(payloadSlot)) }
        val embed = (payloadSlot.captured["embeds"] as List<*>).first() as Map<*, *>
        (embed["fields"] as List<*>).map { it as Map<*, *> }.forEach {
            val value = it["value"] as String
            val lone = value.indices.any { i ->
                Character.isHighSurrogate(value[i]) && (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1]))
            }
            assertFalse(lone, "field ${it["name"]} ends with half a surrogate pair")
        }
    }

    @Test
    fun `전송 실패 로그에 webhook token 을 남기지 않는다`() {
        // 두 번째는 검증기는 통과하지만 URL 패턴 마스킹은 놓치는 모양(대문자 host·포트)이다.
        listOf(
            "https://discord.com/api/webhooks/123/secret-token",
            "https://Discord.com:443/api/webhooks/123/secret-token",
        ).forEach { url ->
            val logs = failureLogs(url)
            assertTrue(logs.any { it.level == Level.WARN && it.formattedMessage.startsWith("Discord notification failed") }, "$logs")
            logs.forEach { assertFalse(it.formattedMessage.contains("secret-token"), it.formattedMessage) }
        }
    }

    // WebClientResponseException 메시지는 "<status> <reason> from POST <URI>" 형식이고 URI 끝이 webhook token 이다.
    private fun failureLogs(url: String): List<ILoggingEvent> {
        val request = mockk<HttpRequest>()
        every { request.method } returns HttpMethod.POST
        every { request.uri } returns URI(url)
        every { request.headers } returns HttpHeaders()
        every { request.attributes } returns mutableMapOf()
        val rejected = WebClientResponseException.create(400, "Bad Request", HttpHeaders(), ByteArray(0), null, request)
        // Kotlin 의 bodyToMono<String>() 은 ParameterizedTypeReference 오버로드를 부른다.
        every { responseSpec.bodyToMono(any<ParameterizedTypeReference<String>>()) } returns Mono.error(rejected)
        val logger = LoggerFactory.getLogger(DiscordNotifier::class.java) as Logger
        val captured = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(captured)
        try {
            notifier.sendErrorAlert("logger", "boom", null, 0, url)
        } finally {
            logger.detachAppender(captured)
        }
        return captured.list
    }

    private fun digestEmbed(digest: ErrorAlertRateLimiter.Digest): Map<*, *> {
        notifier.sendErrorDigest(digest, "https://discord.com/api/webhooks/789/errwebhook")
        verify { requestSpec.uri("https://discord.com/api/webhooks/789/errwebhook") }
        val payloadSlot = slot<Map<String, Any>>()
        verify { requestBodySpec.bodyValue(capture(payloadSlot)) }
        return (payloadSlot.captured["embeds"] as List<*>).single() as Map<*, *>
    }

    @Test
    fun `sendErrorDigest 는 로거별로 번갈아 한 줄씩 싣고 반복 횟수와 못 실은 건수를 붙인다`() {
        val embed = digestEmbed(
            ErrorAlertRateLimiter.Digest(
                alerts = listOf(
                    ErrorAlertRateLimiter.HeldAlert("com.trading.bot.engine.TradingEngine", "Error processing KRW-A", 1),
                    ErrorAlertRateLimiter.HeldAlert("com.trading.bot.engine.TradingEngine", "Error processing KRW-B", 3),
                    ErrorAlertRateLimiter.HeldAlert("com.trading.bot.engine.PositionManager", "[HALT] KRW-XRP", 1),
                ),
                overflowByLogger = mapOf("com.trading.bot.client.UpbitClientImpl" to 4),
            ),
        )

        assertEquals("📋 억제된 알림 9건", embed["title"])
        assertEquals(
            listOf(
                "• TradingEngine — Error processing KRW-A",
                "• PositionManager — [HALT] KRW-XRP",
                "• TradingEngine — Error processing KRW-B (×3)",
                "외 4건 — UpbitClientImpl 4",
            ),
            (embed["description"] as String).lines(),
        )
    }

    @Test
    fun `sendErrorDigest 는 폭주에서도 설명을 한도 안에 두고 다른 로거의 줄과 못 실은 건수를 남긴다`() {
        val flood = (1..60).map { ErrorAlertRateLimiter.HeldAlert("com.trading.bot.engine.TradingEngine", "x".repeat(200) + it, 1) }
        val halt = ErrorAlertRateLimiter.HeldAlert("com.trading.bot.engine.PositionManager", "[HALT] KRW-XRP", 1)
        val overflow = (1..100).associate { "com.trading.bot.engine.Logger$it" to 1 } // 끝 줄이 500자를 넘게

        val description = digestEmbed(ErrorAlertRateLimiter.Digest(flood + halt, overflow))["description"] as String

        assertTrue(description.length <= 4096, "description ${description.length} exceeds 4096")
        assertTrue(description.contains("• PositionManager — [HALT] KRW-XRP"))
        val trailer = description.lines().last()
        assertTrue(trailer.startsWith("외 ") && trailer.length <= 500, trailer)
    }

    @Test
    fun `truncateForDiscord 는 한도 안에서 자르고 서로게이트 쌍을 가르지 않는다`() {
        val text = "a".repeat(198) + "😀" + "tail" // 😀 는 서로게이트 쌍(index 198·199)
        assertEquals("a".repeat(198) + "…", text.truncateForDiscord(200))
        assertEquals("short", "short".truncateForDiscord(200))
    }
}
