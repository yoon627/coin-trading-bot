package com.trading.bot.notification

import com.trading.bot.api.RequestValidators
import com.trading.bot.config.DiscordProperties
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.net.URI
import java.time.Duration

@Component
class DiscordNotifier internal constructor(
    private val discordWebClient: WebClient,
    private val discordProperties: DiscordProperties,
    private val requestValidators: RequestValidators,
    private val retryBackoff: Duration,
) {
    // backoff 를 Spring 주입 대상에서 뺀다 — 기본값 파라미터는 선택 의존성이라, 어딘가 Duration 빈이 생기면 조용히 주입된다.
    @Autowired
    constructor(discordWebClient: WebClient, discordProperties: DiscordProperties, requestValidators: RequestValidators) :
        this(discordWebClient, discordProperties, requestValidators, DEFAULT_RETRY_BACKOFF)

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

    /** ERROR 로그 알림용 Embed. message/stackSummary 는 호출 측에서 마스킹된 상태로 전달. */
    fun sendErrorAlert(
        loggerName: String,
        message: String,
        stackSummary: String?,
        suppressedSince: Int,
        webhookUrl: String,
    ) {
        val fields = mutableListOf(
            mapOf("name" to "Logger", "value" to loggerName.truncateForDiscord(256), "inline" to false),
            // Discord embed field value 한도는 1024자. 초과 시 400 으로 알림이 통째로 유실되므로 마진 두고 truncate.
            mapOf("name" to "Message", "value" to message.ifBlank { "(no message)" }.truncateForDiscord(1000), "inline" to false),
        )
        if (!stackSummary.isNullOrBlank()) {
            fields.add(mapOf("name" to "Stack", "value" to "```\n${stackSummary.truncateForDiscord(950)}\n```", "inline" to false))
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

    /**
     * 분당 상한에 걸려 개별로 보내지 못한 ERROR 요약. 요약 줄은 호출 측에서 마스킹된 상태로 전달.
     * 로거별로 번갈아 싣는다 — 폭주 때는 한 로거의 반복 오류(티커·메시지마다 fingerprint 가 갈린다)가 줄을 다 채워,
     * 다른 경로의 한 번만 찍히는 알림([HALT] 등)이 '외 N건' 에 묻힌다. 설명은 Discord 한도(4096자) 안에서 줄 단위로 채우고
     * 못 실은 건수는 끝 줄에 남긴다.
     */
    fun sendErrorDigest(digest: ErrorAlertRateLimiter.Digest, webhookUrl: String) {
        val lines = mutableListOf<String>()
        var used = 0
        val unlisted = LinkedHashMap<String, Int>()
        interleaveByLogger(digest.alerts).forEach { alert ->
            val repeat = if (alert.occurrences > 1) " (×${alert.occurrences})" else ""
            val line = "• ${alert.logger.substringAfterLast('.')} — ${alert.summary}$repeat"
            if (used + line.length + 1 <= DIGEST_LINES_BUDGET) {
                lines += line
                used += line.length + 1
            } else {
                unlisted.merge(alert.logger, alert.occurrences, Int::plus)
            }
        }
        digest.overflowByLogger.forEach { (logger, count) -> unlisted.merge(logger, count, Int::plus) }
        if (unlisted.isNotEmpty()) {
            val byLogger = unlisted.entries.joinToString(" · ") { "${it.key.substringAfterLast('.')} ${it.value}" }
            lines += "외 ${unlisted.values.sum()}건 — $byLogger".truncateForDiscord(DIGEST_TRAILER_MAX)
        }
        val total = digest.alerts.sumOf { it.occurrences } + digest.overflowByLogger.values.sum()
        val embed = mapOf<String, Any>(
            "title" to "📋 억제된 알림 ${total}건",
            "color" to 0xF59E0B,
            "description" to lines.joinToString("\n"),
            "footer" to mapOf("text" to "분당 알림 상한을 넘어 개별로 보내지 못한 ERROR 입니다. (×K)는 직전 알림 이후 발생 횟수, 전문·stack 은 로그 파일에 있습니다."),
        )
        sendPayload(mapOf("embeds" to listOf(embed)), webhookUrl)
    }

    private fun interleaveByLogger(alerts: List<ErrorAlertRateLimiter.HeldAlert>): List<ErrorAlertRateLimiter.HeldAlert> {
        val queues = alerts.groupByTo(LinkedHashMap()) { it.logger }.values.map { ArrayDeque(it) }
        val ordered = ArrayList<ErrorAlertRateLimiter.HeldAlert>(alerts.size)
        while (ordered.size < alerts.size) {
            queues.forEach { queue -> queue.removeFirstOrNull()?.let(ordered::add) }
        }
        return ordered
    }

    // 오류 알림은 logback appender lock 안에서 여기까지 온다 — 블로킹(재시도 대기 포함)하면 모든 로깅 스레드가 멈춘다.
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
                // 구독을 다시 하면 요청이 새로 나간다. 대기는 타이머라 이 스레드를 막지 않는다.
                .retryWhen(Retry.from { signals ->
                    signals.concatMap { signal ->
                        val delay = retryDelay(signal.failure(), signal.totalRetries()) ?: return@concatMap Mono.error(signal.failure())
                        log.debug("Discord notification retry {} in {}ms", signal.totalRetries() + 1, delay.toMillis())
                        Mono.delay(delay)
                    }
                })
                .onErrorResume { e ->
                    log.warn("Discord notification failed: {}", safeMessage(e, url))
                    Mono.empty()
                }
                .subscribe(
                    { log.debug("Discord notification sent") },
                    { e -> log.warn("Discord notification error: {}", safeMessage(e, url)) },
                )
        } catch (e: Exception) {
            log.warn("Failed to send Discord notification: {}", safeMessage(e, url))
        }
    }

    /**
     * 응답 오류(WebClientResponseException)의 메시지는 요청 URI 를 싣고, webhook URL 의 token 이 그 안에 있다.
     * 보낸 URL 에서 token 을 꺼내 직접 지운다 — 검증기는 대문자 host·포트 등도 통과시켜 URL 패턴 마스킹만으로는 놓친다.
     */
    private fun safeMessage(e: Throwable, url: String): String {
        var message = e.message.orEmpty()
        webhookToken(url)?.let { message = message.replace(it, "***") }
        return "${e.javaClass.simpleName}: ${LogMessageSanitizer.sanitize(message)}"
    }

    // 경로는 검증기가 정한 /api/webhooks/<id>/<token>[/...] 이다.
    private fun webhookToken(url: String): String? {
        val segments = runCatching { URI(url).path }.getOrNull()?.split('/')?.filter { it.isNotEmpty() } ?: return null
        val at = segments.indexOf("webhooks")
        return segments.getOrNull(at + 2)?.takeIf { at >= 0 }
    }

    /**
     * 다시 보낼 때까지 기다릴 시간. 다시 보내도 결과가 같은 실패(400 등 4xx)와 상한을 넘은 시도는 null 이다.
     * 5xx·응답 지연(타임아웃)·연결 실패는 Discord 가 이미 받았을 수 있어 같은 알림이 두 번 갈 수 있다 — 한 번만 찍히는 ERROR 를
     * 잃는 것보다 낫다.
     */
    internal fun retryDelay(e: Throwable, retriesSoFar: Long): Duration? {
        if (retriesSoFar >= MAX_RETRIES) return null
        val backoff = retryBackoff.multipliedBy(1L shl retriesSoFar.toInt())
        return when {
            e is WebClientResponseException && e.statusCode.value() == 429 -> {
                val wait = e.headers.getFirst("Retry-After")?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?.let { Duration.ofMillis((it * 1000).toLong()) }
                    ?: backoff
                wait.takeIf { it <= MAX_RETRY_AFTER }
            }
            e is WebClientResponseException -> backoff.takeIf { e.statusCode.is5xxServerError }
            e is WebClientRequestException -> backoff
            else -> null
        }
    }

    private companion object {
        // 줄 예산 + 끝 줄(못 실은 건수) + 줄바꿈이 설명 한도 4096 안에 든다.
        const val DIGEST_LINES_BUDGET = 3_500
        const val DIGEST_TRAILER_MAX = 500
        val DEFAULT_RETRY_BACKOFF: Duration = Duration.ofSeconds(1)
        const val MAX_RETRIES = 2L
        // 이보다 오래 기다리라면 포기한다 — 그동안 쌓인 알림이 같은 창에 몰려 다시 429 가 난다.
        val MAX_RETRY_AFTER: Duration = Duration.ofSeconds(30)
    }
}

/** [max] 자 안으로 자른다 — 서로게이트 쌍을 가르면 반쪽 문자가 남아 깨져 보이고, Discord 가 거부하는지는 확인하지 못했다. */
internal fun String.truncateForDiscord(max: Int): String {
    if (length <= max) return this
    var end = max - 1
    if (end > 0 && Character.isHighSurrogate(this[end - 1])) end--
    return substring(0, end) + "…"
}
