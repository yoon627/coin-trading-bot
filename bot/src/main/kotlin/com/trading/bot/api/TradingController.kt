package com.trading.bot.api

import com.trading.bot.auth.currentUserId
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
        return userTradingManager.startBot(userId, tickers, strategy)
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
        return if (success) {
            mapOf("status" to "changed", "strategy" to strategy)
        } else {
            mapOf("status" to "error", "message" to "Unknown strategy: $strategy")
        }
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
        userTradingManager.reloadUserRuntime(userId)
        return mapOf("status" to "saved")
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
            "public_profile" to user.publicProfile,
            "public_strategy" to user.publicStrategy,
            "has_discord_webhook" to (!user.discordWebhookUrl.isNullOrBlank()),
        )
    }
}

data class StartBotRequest(val tickers: List<String>? = null, val strategy: String? = null)
data class StrategyRequest(val strategy: String)
data class UpbitKeysRequest(val accessKey: String, val secretKey: String)
