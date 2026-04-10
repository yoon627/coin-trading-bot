package com.trading.bot.engine

import com.trading.bot.config.ClaudeProperties
import com.trading.bot.config.DiscordProperties
import com.trading.bot.persistence.PriceSnapshotRepository
import com.trading.bot.persistence.UserRepository
import com.trading.bot.security.UserSecretsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class CoinAnalysisScheduler(
    private val claudeProperties: ClaudeProperties,
    private val discordProperties: DiscordProperties,
    private val discordWebClient: WebClient,
    private val userRepository: UserRepository,
    private val userTradingManager: UserTradingManager,
    private val tradeExecutionService: TradeExecutionService,
    private val userSecretsService: UserSecretsService,
    private val priceSnapshotRepository: PriceSnapshotRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val kst = ZoneId.of("Asia/Seoul")
    private val scope = CoroutineScope(Dispatchers.IO)

    private val claudeWebClient = WebClient.builder()
        .baseUrl("https://api.anthropic.com")
        .defaultHeader("anthropic-version", "2023-06-01")
        .codecs { it.defaultCodecs().maxInMemorySize(512 * 1024) }
        .build()

    @Scheduled(cron = "0 0 * * * *")
    fun hourlyAnalysis() {
        if (!claudeProperties.analysisEnabled || claudeProperties.apiKey.isBlank()) {
            return
        }

        val now = LocalDateTime.now(kst)
        if (!isInSleepHours(now.hour)) {
            log.debug("Not in sleep hours ({}), skipping analysis", now.hour)
            return
        }

        log.info("Starting Claude auto-trading analysis at {}", now)

        scope.launch {
            try {
                val admins = userRepository.findByAdminTrue().collectList().awaitSingle()
                if (admins.isEmpty()) {
                    log.warn("No admin users found, skipping")
                    return@launch
                }

                for (admin in admins) {
                    try {
                        if (admin.upbitAccessKey.isNullOrBlank()) {
                            log.warn("Admin {} has no Upbit keys, skipping", admin.username)
                            continue
                        }
                        processAdminTrading(admin.id!!, admin.username, admin.discordWebhookUrl)
                    } catch (e: Exception) {
                        log.error("Failed Claude auto-trading for admin {}: {}", admin.username, e.message, e)
                    }
                }
            } catch (e: Exception) {
                log.error("Failed to run hourly analysis: {}", e.message, e)
            }
        }
    }

    private suspend fun processAdminTrading(adminId: Long, username: String, webhookUrl: String?) {
        val marketData = fetchMarketDataFromDb()
        if (marketData.isBlank()) {
            log.warn("No market data available for analysis")
            return
        }

        val admin = userRepository.findById(adminId).awaitSingleOrNull() ?: return
        val decryptedAdmin = userSecretsService.decryptUserSecrets(admin)
        val client = userTradingManager.createUpbitClient(decryptedAdmin)

        val accounts = client.getAccounts()
        val krwBalance = accounts.find { it.currency == "KRW" }?.balanceDouble() ?: 0.0
        val holdings = accounts
            .filter { it.currency != "KRW" && it.balanceDouble() > 0 }
            .joinToString("\n") { "  ${it.currency}: ${it.balance}개 (평단 ${it.avgBuyPrice}원)" }

        val holdingsInfo = if (holdings.isNotBlank()) {
            "현재 보유:\n$holdings\nKRW 잔고: %,.0f원".format(krwBalance)
        } else {
            "현재 보유 코인 없음\nKRW 잔고: %,.0f원".format(krwBalance)
        }

        val decision = callClaudeForTrading(marketData, holdingsInfo)
        if (decision.isBlank()) return

        val actions = parseActions(decision)
        val executedActions = mutableListOf<String>()

        for (action in actions) {
            try {
                when (action.type) {
                    ActionType.BUY -> {
                        if (action.amount < 5000) continue
                        val result = tradeExecutionService.executeBuy(
                            client = client,
                            market = action.ticker,
                            amount = action.amount,
                            strategy = "claude_auto",
                            userId = adminId,
                            username = username,
                            discordWebhookUrl = webhookUrl,
                        )
                        executedActions.add("BUY ${action.ticker} ${"%,.0f".format(action.amount)}원 - ${if (result.success) "success" else result.error}")
                        log.info("Claude auto-buy: {} {}원 for admin {}", action.ticker, action.amount, username)
                    }
                    ActionType.SELL -> {
                        val result = tradeExecutionService.executeSellAll(
                            client = client,
                            market = action.ticker,
                            strategy = "claude_auto",
                            userId = adminId,
                            username = username,
                            discordWebhookUrl = webhookUrl,
                        )
                        executedActions.add("SELL ${action.ticker} 전량 - ${if (result.success) "success" else result.error}")
                        log.info("Claude auto-sell: {} for admin {}", action.ticker, username)
                    }
                    ActionType.HOLD -> {
                        executedActions.add("HOLD ${action.ticker}")
                    }
                }
            } catch (e: Exception) {
                executedActions.add("FAILED ${action.type} ${action.ticker}: ${e.message}")
                log.error("Failed to execute {}: {}", action, e.message)
            }
        }

        sendTradeReport(decision, executedActions, webhookUrl ?: discordProperties.webhookUrl)
    }

    private suspend fun fetchMarketDataFromDb(): String {
        val tickers = claudeProperties.tickerList()
        val now = LocalDateTime.now(kst)
        val sixHoursAgo = now.minusHours(6)
        val sb = StringBuilder()

        for (ticker in tickers) {
            try {
                val snapshots = priceSnapshotRepository
                    .findByTickerAndCapturedAtBetweenOrderByCapturedAtDesc(ticker, sixHoursAgo, now)
                    .collectList()
                    .awaitSingle()

                if (snapshots.isEmpty()) continue

                val latest = snapshots.first()
                val oldest = snapshots.last()
                val highest = snapshots.maxOf { it.price }
                val lowest = snapshots.minOf { it.price }
                val changeFromStart = if (oldest.price > 0) ((latest.price - oldest.price) / oldest.price) * 100 else 0.0

                sb.appendLine("=== $ticker ===")
                sb.appendLine("현재: %,.0f | 6h변동: %+.2f%% | 24h변동: %+.2f%%".format(
                    latest.price, changeFromStart, latest.signedChangeRate * 100
                ))
                sb.appendLine("6h 범위: %,.0f ~ %,.0f | 거래대금: %,.0f".format(lowest, highest, latest.accTradePrice24h))

                val sampled = snapshots.filterIndexed { i, _ -> i % 6 == 0 }.take(12).reversed()
                if (sampled.size > 1) {
                    sb.append("추이: ")
                    sb.appendLine(sampled.joinToString(" -> ") { "%,.0f".format(it.price) })
                }
                sb.appendLine()
            } catch (e: Exception) {
                log.warn("Failed to fetch DB data for {}: {}", ticker, e.message)
            }
        }

        return sb.toString()
    }

    private suspend fun callClaudeForTrading(marketData: String, holdingsInfo: String): String {
        val prompt = """당신은 암호화폐 단기 트레이딩 전문가입니다.
아래 시장 데이터와 현재 포트폴리오를 분석하고 매매 판단을 내려주세요.

## 규칙
- 보수적 접근: 확실한 기회만 진입, 불확실하면 HOLD
- 매수 시 총 KRW 잔고의 최대 20%만 사용
- 손실 중인 코인은 -3% 이하면 손절 검토
- 수익 중인 코인은 상승 모멘텀 소진 시 익절 검토
- 최대 동시 보유 3종목

## 포트폴리오
$holdingsInfo

## 시장 데이터 (최근 6시간)
$marketData

## 응답 형식 (반드시 이 형식으로)
각 판단을 한 줄씩:
ACTION:BUY:KRW-XXX:금액(원)
ACTION:SELL:KRW-XXX:0
ACTION:HOLD:KRW-XXX:0

마지막에 한 줄 근거 요약."""

        val body = mapOf(
            "model" to "claude-haiku-4-5-20251001",
            "max_tokens" to 800,
            "messages" to listOf(
                mapOf("role" to "user", "content" to prompt)
            )
        )

        val response = claudeWebClient.post()
            .uri("/v1/messages")
            .header("x-api-key", claudeProperties.apiKey)
            .header("Content-Type", "application/json")
            .bodyValue(body)
            .retrieve()
            .bodyToMono<Map<*, *>>()
            .awaitSingleOrNull() ?: return ""

        val content = response["content"] as? List<*> ?: return ""
        return content.filterIsInstance<Map<*, *>>()
            .filter { it["type"] == "text" }
            .joinToString("") { it["text"]?.toString() ?: "" }
    }

    private fun parseActions(response: String): List<TradeAction> {
        return response.lines()
            .filter { it.startsWith("ACTION:") }
            .mapNotNull { line ->
                val parts = line.split(":")
                if (parts.size < 4) return@mapNotNull null
                val type = try { ActionType.valueOf(parts[1]) } catch (_: Exception) { return@mapNotNull null }
                val ticker = parts[2]
                val amount = parts[3].replace(",", "").toDoubleOrNull() ?: 0.0
                TradeAction(type, ticker, amount)
            }
    }

    private fun sendTradeReport(analysis: String, actions: List<String>, webhookUrl: String) {
        if (webhookUrl.isBlank()) return

        val now = LocalDateTime.now(kst)
        val actionsText = if (actions.isNotEmpty()) actions.joinToString("\n") else "No actions taken"

        val embed = mapOf(
            "title" to "Claude Auto-Trading Report",
            "color" to 0x8B5CF6,
            "fields" to listOf(
                mapOf("name" to "Analysis", "value" to analysis.take(1024), "inline" to false),
                mapOf("name" to "Executed Actions", "value" to actionsText.take(1024), "inline" to false),
            ),
            "footer" to mapOf("text" to "Claude Haiku | ${now.toString().take(16)}"),
        )

        discordWebClient.post()
            .uri(webhookUrl)
            .bodyValue(mapOf("embeds" to listOf(embed)))
            .retrieve()
            .bodyToMono<String>()
            .onErrorResume { e ->
                log.warn("Failed to send trade report to Discord: {}", e.message)
                Mono.empty()
            }
            .subscribe()
    }

    private fun isInSleepHours(hour: Int): Boolean {
        val start = claudeProperties.sleepStartHour
        val end = claudeProperties.sleepEndHour
        return if (start > end) hour >= start || hour < end else hour in start until end
    }
}

enum class ActionType { BUY, SELL, HOLD }

data class TradeAction(val type: ActionType, val ticker: String, val amount: Double)
