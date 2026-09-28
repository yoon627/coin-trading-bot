package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.engine.reloadFailureMessage
import com.trading.bot.engine.RuntimeReloadFailedException
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.persistence.UserRepository
import com.trading.bot.security.UserSecretsService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api")
class TradingController(
    private val userTradingManager: UserTradingManager,
    private val userRepository: UserRepository,
    private val requestValidators: RequestValidators,
    private val userSecretsService: UserSecretsService,
) {

    @PostMapping("/bot/start")
    suspend fun startBot(@RequestBody(required = false) req: StartBotRequest?): Map<String, Any> {
        val userId = currentUserId()
        val tickers = req?.tickers?.let(requestValidators::normalizeMarkets)
        val strategy = req?.strategy?.let(requestValidators::normalizeStrategy)
        val result = userTradingManager.startBot(userId, tickers, strategy)
        // UserTradingManager returns {"error": "..."} for precondition failures
        // (no API keys, user missing, running with another ticker list). Surface those as proper 4xx so clients
        // can branch on status instead of having to inspect the body.
        result["error"]?.let { msg ->
            val status = when {
                result["code"] == UserTradingManager.CONFLICT_CODE -> HttpStatus.CONFLICT
                (msg as? String)?.contains("not found", ignoreCase = true) == true -> HttpStatus.NOT_FOUND
                else -> HttpStatus.BAD_REQUEST
            }
            throw ResponseStatusException(status, msg.toString())
        }
        return result
    }

    @PostMapping("/bot/stop")
    suspend fun stopBot(): Map<String, Any> {
        return userTradingManager.stopBot(currentUserId())
    }

    @GetMapping("/bot/status")
    suspend fun getStatus(): Map<String, Any> {
        return userTradingManager.getStatus(currentUserId())
    }

    @PostMapping("/bot/strategy")
    suspend fun changeStrategy(@RequestBody request: StrategyRequest): Map<String, Any> {
        val strategy = requestValidators.normalizeStrategy(request.strategy)
        val success = userTradingManager.setStrategy(currentUserId(), strategy)
        if (!success) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown strategy: $strategy")
        }
        return mapOf("status" to "changed", "strategy" to strategy)
    }

    @PostMapping("/bot/halt/clear")
    suspend fun clearHalt(@RequestBody request: ClearHaltRequest): Map<String, Any> {
        // currentUserId() 로 자기 봇만 대상 — 타인 봇 halt 해제 불가.
        val ticker = requestValidators.normalizeMarkets(listOf(request.ticker)).firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ticker: ${request.ticker}")
        return userTradingManager.clearHalt(currentUserId(), ticker)
    }

    @GetMapping("/account")
    suspend fun getAccount(): Any {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.upbitAccessKey.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Upbit API keys not configured")
        }
        val client = userTradingManager.createUpbitClient(userSecretsService.decryptUserSecrets(user))
        return client.getAccounts()
    }

    @PostMapping("/user/keys")
    suspend fun setUpbitKeys(@RequestBody req: UpbitKeysRequest): Map<String, String> {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found")
        val accessKey = requestValidators.normalizeApiKey(req.accessKey, "accessKey")
        val secretKey = requestValidators.normalizeApiKey(req.secretKey, "secretKey")
        val (encryptedAccessKey, encryptedSecretKey) = userSecretsService.encryptUpbitKeys(accessKey, secretKey)
        userRepository.save(
            user.copy(upbitAccessKey = encryptedAccessKey, upbitSecretKey = encryptedSecretKey)
        ).awaitSingle()
        try {
            userTradingManager.reloadUserRuntime(userId)
        } catch (e: RuntimeReloadFailedException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reloadFailureMessage(e), e)
        }
        return mapOf("status" to "saved")
    }

    @PostMapping("/user/settings")
    suspend fun updateSettings(@RequestBody req: UserSettingsRequest): Map<String, Any> {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found")
        // 필드가 없으면 바꿀 것이 없다 — 저장도 엔진 재기동(reloadUserRuntime)도 하지 않는다(캐시된 옛 화면이 보내는 공개 설정 등).
        val requested = req.discordWebhookUrl
            ?: return mapOf("has_discord_webhook" to !user.discordWebhookUrl.isNullOrBlank())
        // 해제는 빈 문자열이다(normalizeDiscordWebhookUrl 이 null 로 만든다).
        val nextWebhook = requestValidators.normalizeDiscordWebhookUrl(requested)
        val saved = userRepository.save(user.copy(discordWebhookUrl = nextWebhook)).awaitSingle()
        try {
            userTradingManager.reloadUserRuntime(userId)
        } catch (e: RuntimeReloadFailedException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reloadFailureMessage(e), e)
        }
        return mapOf("has_discord_webhook" to !saved.discordWebhookUrl.isNullOrBlank())
    }

    @GetMapping("/user/me")
    suspend fun getMe(): Map<String, Any?> {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found")
        return mapOf(
            "id" to user.id,
            "username" to user.username,
            "has_upbit_keys" to (!user.upbitAccessKey.isNullOrBlank()),
            "has_discord_webhook" to (!user.discordWebhookUrl.isNullOrBlank()),
        )
    }
}

data class StartBotRequest(val tickers: List<String>? = null, val strategy: String? = null)
data class StrategyRequest(val strategy: String)
data class ClearHaltRequest(val ticker: String)
data class UserSettingsRequest(val discordWebhookUrl: String? = null)
data class UpbitKeysRequest(val accessKey: String, val secretKey: String)
