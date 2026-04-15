package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.TradeRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.floor

@Service
class TradeExecutionService(
    private val tradeRecordRepository: TradeRecordRepository,
    private val discordNotifier: DiscordNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 매수 주문 실행 + 기록 저장 + Discord 알림
     */
    suspend fun executeBuy(
        client: UpbitClient,
        market: String,
        amount: Double,
        strategy: String,
        userId: Long,
        username: String? = null,
        discordWebhookUrl: String? = null,
    ): TradeExecutionResult {
        val order = client.placeOrder(
            OrderRequest(
                market = market,
                side = "bid",
                ordType = "price",
                price = floor(amount).toLong().toString(),
            )
        )

        val currentPrice = client.getTicker(market).firstOrNull()?.tradePrice ?: 0.0
        val volume = if (currentPrice > 0) amount / currentPrice else 0.0

        val record = TradeRecord(
            ticker = market,
            side = TradeSide.BUY,
            price = currentPrice,
            volume = volume,
            totalAmount = amount,
            strategy = strategy,
            userId = userId,
        )

        return saveAndNotify(client, record, username, discordWebhookUrl, order.uuid)
    }

    /**
     * 전량 매도 주문 실행 + 기록 저장 + Discord 알림
     */
    suspend fun executeSellAll(
        client: UpbitClient,
        market: String,
        strategy: String,
        userId: Long,
        username: String? = null,
        discordWebhookUrl: String? = null,
    ): TradeExecutionResult {
        val currency = market.substringAfter("-")
        val accounts = client.getAccounts()
        val account = accounts.find { it.currency == currency }
            ?: return TradeExecutionResult.failure("no holdings for $currency")
        if (account.balanceDouble() <= 0) {
            return TradeExecutionResult.failure("no balance for $currency")
        }

        val order = client.placeOrder(
            OrderRequest(
                market = market,
                side = "ask",
                ordType = "market",
                volume = account.balance,
            )
        )

        val currentPrice = client.getTicker(market).firstOrNull()?.tradePrice ?: 0.0
        val avgBuyPrice = account.avgBuyPriceDouble()
        val pnl = if (avgBuyPrice > 0) ((currentPrice - avgBuyPrice) / avgBuyPrice) * 100.0 else null
        val vol = account.balanceDouble()

        val record = TradeRecord(
            ticker = market,
            side = TradeSide.SELL,
            price = currentPrice,
            volume = vol,
            totalAmount = currentPrice * vol,
            pnlPercent = pnl,
            reason = SellReason.MANUAL.name,
            strategy = strategy,
            userId = userId,
        )

        return saveAndNotify(client, record, username, discordWebhookUrl, order.uuid)
    }

    /**
     * 지정 수량 매도 주문 실행 + 기록 저장 + Discord 알림
     */
    suspend fun executeSellVolume(
        client: UpbitClient,
        market: String,
        sellVolume: String,
        strategy: String,
        userId: Long,
        username: String? = null,
        discordWebhookUrl: String? = null,
    ): TradeExecutionResult {
        val currency = market.substringAfter("-")

        val currentPrice = client.getTicker(market).firstOrNull()?.tradePrice ?: 0.0
        val avgBuyPrice = client.getAccounts().find { it.currency == currency }?.avgBuyPriceDouble() ?: 0.0
        val pnl = if (avgBuyPrice > 0) ((currentPrice - avgBuyPrice) / avgBuyPrice) * 100.0 else null

        val order = client.placeOrder(
            OrderRequest(
                market = market,
                side = "ask",
                ordType = "market",
                volume = sellVolume,
            )
        )

        val vol = sellVolume.toDoubleOrNull() ?: 0.0
        val record = TradeRecord(
            ticker = market,
            side = TradeSide.SELL,
            price = currentPrice,
            volume = vol,
            totalAmount = currentPrice * vol,
            pnlPercent = pnl,
            reason = SellReason.MANUAL.name,
            strategy = strategy,
            userId = userId,
        )

        return saveAndNotify(client, record, username, discordWebhookUrl, order.uuid)
    }

    /**
     * TradingEngine에서 사용 - 이미 만들어진 TradeRecord를 저장 + 알림
     */
    suspend fun saveAndNotify(
        record: TradeRecord,
        client: UpbitClient,
        username: String?,
        discordWebhookUrl: String?,
    ) {
        tradeRecordRepository.save(record)
        val krwBalance = try {
            client.getAccounts().find { it.currency == "KRW" }?.balanceDouble()
        } catch (_: Exception) {
            null
        }
        discordNotifier.sendTradeEmbed(record, krwBalance, discordWebhookUrl, username)
    }

    private suspend fun saveAndNotify(
        client: UpbitClient,
        record: TradeRecord,
        username: String?,
        discordWebhookUrl: String?,
        orderUuid: String,
    ): TradeExecutionResult {
        tradeRecordRepository.save(record)
        val krwBalance = try {
            client.getAccounts().find { it.currency == "KRW" }?.balanceDouble()
        } catch (_: Exception) {
            null
        }
        discordNotifier.sendTradeEmbed(record, krwBalance, discordWebhookUrl, username)
        return TradeExecutionResult.success(orderUuid)
    }
}

data class TradeExecutionResult(
    val success: Boolean,
    val orderUuid: String? = null,
    val error: String? = null,
) {
    companion object {
        fun success(orderUuid: String) = TradeExecutionResult(success = true, orderUuid = orderUuid)
        fun failure(error: String) = TradeExecutionResult(success = false, error = error)
    }
}
