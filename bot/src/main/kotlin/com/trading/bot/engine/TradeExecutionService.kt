package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.TradeExecutionRepository
import com.trading.bot.persistence.entity.TradeExecutionEntity
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.floor

@Service
class TradeExecutionService(
    private val tradeRecordRepository: TradeRecordRepository,
    private val tradeExecutionRepository: TradeExecutionRepository,
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
     * TradingEngine에서 사용 - 이미 만들어진 TradeRecord를 저장 + Discord 알림
     */
    suspend fun saveAndNotify(
        record: TradeRecord,
        client: UpbitClient,
        username: String?,
        discordWebhookUrl: String?,
    ) {
        tradeRecordRepository.save(record)

        val executionEntity = TradeExecutionEntity(
            userId = record.userId ?: 0,
            exchange = "UPBIT",
            market = record.ticker,
            side = record.side.name,
            price = record.price,
            volume = record.volume,
            totalAmount = record.totalAmount,
            pnlPercent = record.pnlPercent,
            reason = record.reason,
            strategy = record.strategy,
        )
        // trade_executions 저장. tradeRecordRepository.save 와는 별도 트랜잭션이므로
        // 실패해도 record 는 남는다. 운영 가시성을 위해 명시적으로 error 로깅하고 호출자가 인지하도록 throw 한다.
        try {
            tradeExecutionRepository.save(executionEntity).awaitSingle()
        } catch (e: Exception) {
            log.error(
                "Failed to persist trade execution (record saved but execution row missing): userId={}, market={}, side={}",
                record.userId, record.ticker, record.side, e,
            )
            throw e
        }

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
        // 수동·엔진 주문 모두 동일 audit 경로 (trade_executions) 를 거치도록 통합.
        // execution save 실패는 failure 반환으로 호출자에게 가시화.
        return try {
            saveAndNotify(record, client, username, discordWebhookUrl)
            TradeExecutionResult.success(orderUuid)
        } catch (e: Exception) {
            log.error("Failed to save and notify trade execution: orderUuid={}, market={}", orderUuid, record.ticker, e)
            TradeExecutionResult.failure(e.message ?: "save failed")
        }
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
