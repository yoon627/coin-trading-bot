package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.kis.client.KisClientFactory
import com.trading.bot.kis.domain.KisHolding
import com.trading.bot.kis.engine.StockUserTradingManager
import com.trading.bot.persistence.UserRepository
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** KIS 주식 자동매매 봇 제어 (/api/stock). 수동주문은 KisTradeController(/api/kis/order) 별도. */
@RestController
@RequestMapping("/api/stock")
class StockBotController(
    private val stockUserTradingManager: StockUserTradingManager,
    private val userRepository: UserRepository,
    private val kisClientFactory: KisClientFactory,
    private val requestValidators: RequestValidators,
) {

    @PostMapping("/bot/start")
    suspend fun start(@RequestBody(required = false) req: StartStockBotRequest?): Map<String, Any> {
        val userId = currentUserId()
        val symbols = (req?.symbols ?: emptyList()).map(requestValidators::normalizeKisSymbol).distinct()
        if (symbols.isEmpty() || symbols.size > 20) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "symbols must contain 1..20 KIS codes")
        }
        val strategy = req?.strategy?.let(requestValidators::normalizeStrategy)
        val result = stockUserTradingManager.startBot(userId, symbols, strategy)
        result["error"]?.let { msg ->
            val status = if ((msg as? String)?.contains("not found", ignoreCase = true) == true) {
                HttpStatus.NOT_FOUND
            } else {
                HttpStatus.BAD_REQUEST
            }
            throw ResponseStatusException(status, msg.toString())
        }
        return result
    }

    @PostMapping("/bot/stop")
    suspend fun stop(): Map<String, Any> = stockUserTradingManager.stopBot(currentUserId())

    @GetMapping("/bot/status")
    suspend fun status(): Map<String, Any> = stockUserTradingManager.getStatus(currentUserId())

    @PostMapping("/bot/strategy")
    suspend fun strategy(@RequestBody req: StrategyRequest): Map<String, Any> {
        val strategy = requestValidators.normalizeStrategy(req.strategy)
        if (!stockUserTradingManager.setStrategy(currentUserId(), strategy)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown strategy: $strategy")
        }
        return mapOf("status" to "changed", "strategy" to strategy)
    }

    @GetMapping("/account")
    suspend fun account(): List<KisHolding> {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.kisAppKey.isNullOrBlank() || user.kisCano.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "KIS API keys not configured")
        }
        return kisClientFactory.forUser(user).getHoldings()
    }
}

data class StartStockBotRequest(val symbols: List<String>? = null, val strategy: String? = null)
