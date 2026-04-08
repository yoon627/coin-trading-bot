package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.UserRepository
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import kotlin.math.floor

@RestController
@RequestMapping("/api/trade")
class ManualTradeController(
    private val userTradingManager: UserTradingManager,
    private val userRepository: UserRepository,
    private val tradeRecordRepository: TradeRecordRepository,
    private val discordNotifier: DiscordNotifier,
) {
    @PostMapping("/buy")
    suspend fun manualBuy(@RequestBody req: ManualBuyRequest): Map<String, Any> {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.upbitAccessKey.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Upbit API keys not configured")
        }
        if (req.amount < 5000) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Minimum order: 5,000 KRW")
        }

        val client = userTradingManager.createUpbitClient(user)

        val order = client.placeOrder(
            OrderRequest(
                market = req.market,
                side = "bid",
                ordType = "price",
                price = floor(req.amount).toLong().toString(),
            )
        )

        val ticker = client.getTicker(req.market).firstOrNull()
        val currentPrice = ticker?.tradePrice ?: 0.0
        val volume = if (currentPrice > 0) req.amount / currentPrice else 0.0

        val record = TradeRecord(
            ticker = req.market, side = TradeSide.BUY, price = currentPrice,
            volume = volume, totalAmount = req.amount, strategy = "manual", userId = userId,
        )
        tradeRecordRepository.save(record)

        val krwBalance = try {
            client.getAccounts().find { it.currency == "KRW" }?.balanceDouble()
        } catch (_: Exception) { null }
        discordNotifier.sendTradeEmbed(record, krwBalance, user.discordWebhookUrl, user.username)

        return mapOf("status" to "success", "order_uuid" to order.uuid)
    }

    @PostMapping("/sell")
    suspend fun manualSell(@RequestBody req: ManualSellRequest): Map<String, Any> {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.upbitAccessKey.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Upbit API keys not configured")
        }

        val client = userTradingManager.createUpbitClient(user)
        val currency = req.market.substringAfter("-")

        val sellVolume = if (req.sellAll == true) {
            val accounts = client.getAccounts()
            val account = accounts.find { it.currency == currency }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No holdings for $currency")
            val balance = account.balanceDouble()
            if (balance <= 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No balance for $currency")
            balance.toString()
        } else {
            req.volume ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Specify volume or sell_all")
        }

        val ticker = client.getTicker(req.market).firstOrNull()
        val currentPrice = ticker?.tradePrice ?: 0.0
        val avgBuyPrice = client.getAccounts().find { it.currency == currency }?.avgBuyPriceDouble() ?: 0.0
        val pnl = if (avgBuyPrice > 0) ((currentPrice - avgBuyPrice) / avgBuyPrice) * 100.0 else null

        val order = client.placeOrder(
            OrderRequest(
                market = req.market,
                side = "ask",
                ordType = "market",
                volume = sellVolume,
            )
        )

        val vol = sellVolume.toDoubleOrNull() ?: 0.0
        val record = TradeRecord(
            ticker = req.market, side = TradeSide.SELL, price = currentPrice,
            volume = vol, totalAmount = currentPrice * vol, pnlPercent = pnl,
            reason = SellReason.MANUAL.name, strategy = "manual", userId = userId,
        )
        tradeRecordRepository.save(record)

        val krwBalance = try {
            client.getAccounts().find { it.currency == "KRW" }?.balanceDouble()
        } catch (_: Exception) { null }
        discordNotifier.sendTradeEmbed(record, krwBalance, user.discordWebhookUrl, user.username)

        return mapOf("status" to "success", "order_uuid" to order.uuid)
    }
}

data class ManualBuyRequest(val market: String, val amount: Double)
data class ManualSellRequest(val market: String, val volume: String? = null, val sellAll: Boolean? = null)
