package com.trading.bot.notification

import com.trading.bot.api.RequestValidators
import com.trading.bot.config.DiscordProperties
import com.trading.bot.domain.FillOutcome
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
    private val requestValidators: RequestValidators,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이 주문의 실체결 대금이 있으면 "체결금액", 없으면 totalAmount 를 **출처 중립 라벨**로 보여준다(#146).
     * 엔진 매수의 totalAmount 는 포지션 전체 원가, 매도는 tick 평가액(과거 수동 매수 행은 요청액) — 경로마다 달라
     * 한 단어로 이름 붙이면 어느 한 경로에서는 거짓이 된다. "금액"이라 부르면 5만원 추가매수가 100만원으로 읽힌다.
     */
    private fun amountField(record: TradeRecord): Map<String, Any> {
        val (label, amount) = when (val known = record.orderAmount) {
            null -> "기록 금액(체결 미상)" to record.totalAmount
            else -> "체결금액" to known
        }
        return mapOf("name" to label, "value" to "%,.0f원".format(amount), "inline" to true)
    }

    fun sendTradeEmbed(
        record: TradeRecord,
        krwBalance: Double? = null,
        webhookUrl: String? = null,
        username: String? = null,
    ) {
        val isBuy = record.side == TradeSide.BUY
        val color = if (isBuy) 0x22C55E else if ((record.pnlPercent ?: 0.0) >= 0) 0x3B82F6 else 0xEF4444
        val title = if (isBuy) "📈 매수" else "📉 매도"
        val pnlText = record.pnlPercent?.let { "%+.2f%%".format(it) } ?: "-"

        val fields = mutableListOf(
            mapOf("name" to "티커", "value" to record.ticker, "inline" to true),
            mapOf("name" to "가격", "value" to "%,.0f원".format(record.price), "inline" to true),
            amountField(record),
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

        val embed = mutableMapOf<String, Any>(
            "title" to "$title ${record.ticker}",
            "color" to color,
            "fields" to fields,
            "timestamp" to record.createdAt.toString(),
        )

        if (username != null) {
            embed["footer"] = mapOf("text" to username)
        }

        sendPayload(mapOf("embeds" to listOf(embed)), webhookUrl)
    }

    /**
     * 수동 주문이 접수됐지만 체결을 확정하지 못해 거래 기록을 남기지 않았을 때. 사용자가 거래소에서 uuid 로
     * 대조해야 하므로 uuid·요청 수량·마지막으로 본 상태를 그대로 싣는다(#105).
     */
    fun sendOrderUnrecorded(
        outcome: FillOutcome,
        market: String,
        orderUuid: String,
        requestedVolume: String,
        lastState: String?,
        executedVolume: String?,
        webhookUrl: String? = null,
        username: String? = null,
    ) {
        // Discord embed field value 는 비면 400, 1024자 초과도 400 — 알림이 통째로 유실된다(sendErrorAlert 와 같은 가드).
        fun field(name: String, value: String, inline: Boolean) =
            mapOf("name" to name, "value" to value.ifBlank { "-" }.take(1000), "inline" to inline)
        val fields = listOf(
            field("티커", market, true),
            field("요청 수량", requestedVolume, true),
            field("마지막 상태", "${lastState ?: "조회 실패"} / 체결 ${executedVolume ?: "-"}", true),
            field("주문 uuid", orderUuid, false),
        )
        val (title, description) = when (outcome) {
            FillOutcome.NOT_FILLED ->
                "⚠️ 매도 주문이 체결 없이 종료됨 — 기록하지 않음" to "주문이 취소로 끝나 거래가 없습니다. 보유량은 그대로입니다."
            else ->
                "⚠️ 매도 주문 체결 미확인 — 기록하지 않음" to "거래소에서 주문 상태를 확인하세요. 체결분이 있다면 거래 기록에 없습니다."
        }
        val embed = mutableMapOf<String, Any>(
            "title" to title,
            "description" to description,
            "color" to 0xF59E0B,
            "fields" to fields,
        )
        if (username != null) {
            embed["footer"] = mapOf("text" to username)
        }
        sendPayload(mapOf("embeds" to listOf(embed)), webhookUrl)
    }

    /** ERROR 로그 알림용 Embed. message/stackSummary 는 호출 측에서 마스킹된 상태로 전달. */
    fun sendErrorAlert(
        loggerName: String,
        message: String,
        stackSummary: String?,
        suppressedSince: Int,
        webhookUrl: String,
    ) {
        val fields = mutableListOf(
            mapOf("name" to "Logger", "value" to loggerName.take(256), "inline" to false),
            // Discord embed field value 한도는 1024자. 초과 시 400 으로 알림이 통째로 유실되므로 마진 두고 truncate.
            mapOf("name" to "Message", "value" to message.ifBlank { "(no message)" }.take(1000), "inline" to false),
        )
        if (!stackSummary.isNullOrBlank()) {
            fields.add(mapOf("name" to "Stack", "value" to "```\n${stackSummary.take(950)}\n```", "inline" to false))
        }
        if (suppressedSince > 0) {
            fields.add(mapOf("name" to "참고", "value" to "최근 5분간 동일 에러 ${suppressedSince}회 추가 발생", "inline" to false))
        }
        val embed = mapOf<String, Any>(
            "title" to "🚨 서버 에러",
            "color" to 0xEF4444,
            "fields" to fields,
        )
        sendPayload(mapOf("embeds" to listOf(embed)), webhookUrl)
    }

    private fun sendPayload(payload: Map<String, Any>, webhookUrl: String? = null) {
        val url = try {
            requestValidators.normalizeDiscordWebhookUrl(
                webhookUrl?.takeIf { it.isNotBlank() } ?: discordProperties.webhookUrl
            )
        } catch (e: Exception) {
            log.warn("Skipping Discord notification due to invalid webhook URL")
            return
        }
        if (url.isNullOrBlank()) {
            log.debug("Discord webhook not configured, skipping notification")
            return
        }

        try {
            discordWebClient.post()
                .uri(url)
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
