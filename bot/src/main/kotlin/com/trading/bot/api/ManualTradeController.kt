package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.engine.TradeExecutionService
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.persistence.UserRepository
import com.trading.bot.security.UserSecretsService
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/trade")
class ManualTradeController(
    private val userTradingManager: UserTradingManager,
    private val tradeExecutionService: TradeExecutionService,
    private val userRepository: UserRepository,
    private val requestValidators: RequestValidators,
    private val userSecretsService: UserSecretsService,
) {
    @PostMapping("/buy")
    suspend fun manualBuy(@RequestBody req: ManualBuyRequest): Map<String, Any> {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.upbitAccessKey.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Upbit API keys not configured")
        }
        val market = requestValidators.normalizeMarket(req.market)
        requestValidators.validateOrderAmount(req.amount)

        val client = userTradingManager.createUpbitClient(userSecretsService.decryptUserSecrets(user))
        val result = tradeExecutionService.executeBuy(
            client = client,
            market = market,
            amount = req.amount,
            strategy = "manual",
            userId = userId,
            username = user.username,
            discordWebhookUrl = user.discordWebhookUrl,
        )

        if (!result.success) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, result.error)
        }
        return mapOf("status" to "success", "order_uuid" to (result.orderUuid ?: ""))
    }

    @PostMapping("/sell")
    suspend fun manualSell(@RequestBody req: ManualSellRequest): Map<String, Any> {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.upbitAccessKey.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Upbit API keys not configured")
        }
        val market = requestValidators.normalizeMarket(req.market)

        val client = userTradingManager.createUpbitClient(userSecretsService.decryptUserSecrets(user))

        val result = if (req.sellAll == true) {
            tradeExecutionService.executeSellAll(
                client = client,
                market = market,
                strategy = "manual",
                userId = userId,
                username = user.username,
                discordWebhookUrl = user.discordWebhookUrl,
            )
        } else {
            val volume = requestValidators.normalizeSellVolume(
                req.volume ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Specify volume or sell_all")
            )
            tradeExecutionService.executeSellVolume(
                client = client,
                market = market,
                sellVolume = volume,
                strategy = "manual",
                userId = userId,
                username = user.username,
                discordWebhookUrl = user.discordWebhookUrl,
            )
        }

        if (!result.success) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, result.error)
        }
        return mapOf("status" to "success", "order_uuid" to (result.orderUuid ?: ""))
    }
}

data class ManualBuyRequest(val market: String, val amount: Double)
data class ManualSellRequest(val market: String, val volume: String? = null, val sellAll: Boolean? = null)
