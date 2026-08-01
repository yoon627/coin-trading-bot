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
import com.trading.common.config.TradingProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import kotlin.math.floor

@Service
class TradeExecutionService(
    private val tradeRecordRepository: TradeRecordRepository,
    private val tradeExecutionRepository: TradeExecutionRepository,
    private val discordNotifier: DiscordNotifier,
    private val transactionalOperator: TransactionalOperator,
    private val tradingProperties: TradingProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 기록용 net pnl(%) — 왕복수수료 차감, PositionManager.sell 과 동일 기준. 평단·현재가 미상이면 null(가짜 −100.1% 방지). */
    private fun netPnlPercent(currentPrice: Double, avgBuyPrice: Double): Double? =
        if (avgBuyPrice > 0 && currentPrice > 0) {
            ((currentPrice - avgBuyPrice) / avgBuyPrice) * 100.0 - tradingProperties.roundTripFeeRate * 100
        } else {
            null
        }

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
        // placeOrder 실패(UpbitApiException 등)는 잡지 않고 전파 — UpbitErrorHandlerAdvice 가 429/418/insufficient_funds/
        // error_name 을 정교하게 매핑한다. 여기서 잡으면 그 매핑을 우회하고 rawBody(e.message)를 노출한다.
        // 성공 이후 조회/기록 실패만 recordOrder 가 recorded=false 로 흡수(주문은 접수됐으므로 재시도 유발 금지).
        val order = client.placeOrder(
            OrderRequest(
                market = market,
                side = "bid",
                ordType = "price",
                price = floor(amount).toLong().toString(),
            )
        )

        return recordOrder(client, order.uuid, market, username, discordWebhookUrl) {
            val currentPrice = client.getTicker(market).firstOrNull()?.tradePrice ?: 0.0
            val volume = if (currentPrice > 0) amount / currentPrice else 0.0
            TradeRecord(
                ticker = market,
                side = TradeSide.BUY,
                price = currentPrice,
                volume = volume,
                totalAmount = amount,
                strategy = strategy,
                userId = userId,
            )
        }
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
        val account = client.getAccounts().find { it.currency == currency }
            ?: return TradeExecutionResult.failure("no holdings for $currency")
        if (account.balanceDouble() <= 0) {
            return TradeExecutionResult.failure("no balance for $currency")
        }

        // placeOrder 실패는 전파(UpbitErrorHandlerAdvice 처리) — executeBuy 와 동일 이유.
        val order = client.placeOrder(
            OrderRequest(
                market = market,
                side = "ask",
                ordType = "market",
                volume = account.balance,
            )
        )

        return recordOrder(client, order.uuid, market, username, discordWebhookUrl) {
            val currentPrice = client.getTicker(market).firstOrNull()?.tradePrice ?: 0.0
            val vol = account.balanceDouble()
            TradeRecord(
                ticker = market,
                side = TradeSide.SELL,
                price = currentPrice,
                volume = vol,
                totalAmount = currentPrice * vol,
                pnlPercent = netPnlPercent(currentPrice, account.avgBuyPriceDouble()),
                reason = SellReason.MANUAL.name,
                strategy = strategy,
                userId = userId,
            )
        }
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

        // pnl 기준가(평단)는 매도 전에 확보 — 전량 매도면 체결 후 통화 잔고가 사라져 avgBuyPrice 를 잃고 pnl 이 null 이 된다(codex P2).
        val avgBuyPrice = client.getAccounts().find { it.currency == currency }?.avgBuyPriceDouble() ?: 0.0

        // placeOrder 실패는 전파(UpbitErrorHandlerAdvice 처리) — executeBuy 와 동일 이유.
        val order = client.placeOrder(
            OrderRequest(
                market = market,
                side = "ask",
                ordType = "market",
                volume = sellVolume,
            )
        )

        return recordOrder(client, order.uuid, market, username, discordWebhookUrl) {
            val currentPrice = client.getTicker(market).firstOrNull()?.tradePrice ?: 0.0
            val vol = sellVolume.toDoubleOrNull() ?: 0.0
            TradeRecord(
                ticker = market,
                side = TradeSide.SELL,
                price = currentPrice,
                volume = vol,
                totalAmount = currentPrice * vol,
                pnlPercent = netPnlPercent(currentPrice, avgBuyPrice),
                reason = SellReason.MANUAL.name,
                strategy = strategy,
                userId = userId,
            )
        }
    }

    /**
     * 감사 기록 저장만 수행 — **호출자가 연 트랜잭션에 참여**한다(자체 트랜잭션을 열지 않음).
     *
     * 엔진 체결 경로는 `trading_states` pending 해소와 이 저장을 한 트랜잭션으로 묶어야 한다(#52).
     * 따로 커밋하면 감사 저장이 실패했을 때 재시도 근거(pending)가 이미 사라져 기록이 영구 유실된다.
     *
     * @return 실제로 기록했으면 true, 이미 기록된 주문이라 건너뛰었으면 false(멱등).
     */
    suspend fun saveAudit(record: TradeRecord): Boolean {
        val userId = record.userId ?: 0
        // #20 멱등: 재시작 후 같은 주문 uuid 를 다시 reconcile 해도 이중 기록/알림하지 않게 skip.
        val orderId = record.exchangeOrderId
        if (orderId != null &&
            tradeExecutionRepository.existsByUserIdAndExchangeOrderId(userId, orderId).awaitSingle()
        ) {
            log.info("Trade already recorded — idempotent skip: userId={}, market={}, orderId={}", userId, record.ticker, orderId)
            return false
        }
        tradeRecordRepository.save(record)
        tradeExecutionRepository.save(
            TradeExecutionEntity(
                userId = userId,
                exchange = "UPBIT",
                market = record.ticker,
                side = record.side.name,
                price = record.price,
                volume = record.volume,
                totalAmount = record.totalAmount,
                pnlPercent = record.pnlPercent,
                reason = record.reason,
                strategy = record.strategy,
                exchangeOrderId = record.exchangeOrderId,
            )
        ).awaitSingle()
        return true
    }

    /**
     * 커밋 후 알림. 잔고 조회·Discord 발송은 외부 IO 라 트랜잭션 밖에서 수행한다.
     * 여기서 실패해도 이미 커밋된 거래 기록은 되돌리지 않는다(알림은 감사 기록의 부속이다).
     */
    suspend fun notifyTrade(
        record: TradeRecord,
        client: UpbitClient,
        username: String?,
        discordWebhookUrl: String?,
    ) {
        val krwBalance = try {
            client.getAccounts().find { it.currency == "KRW" }?.balanceDouble()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        try {
            discordNotifier.sendTradeEmbed(record, krwBalance, discordWebhookUrl, username)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Trade notification failed for {}: {}", record.ticker, e.message)
        }
    }

    /**
     * 엔진 체결 경로용 — **상태 전이 저장과 감사 기록을 한 트랜잭션으로 커밋**한다(#52).
     *
     * 이 원자화가 없으면 pending 해소가 먼저 커밋돼, 감사 저장이 transient 실패했을 때 재시도 근거가
     * 사라진 채 기록만 영구 유실된다(현금흐름은 발생했는데 거래·손익 기록이 없다).
     *
     * 실패 시 예외를 그대로 올린다 — 호출자는 **메모리 상태 전이를 적용하지 않아야** 다음 tick reconcile 이
     * 재시도할 수 있다. 반환값은 새 audit 행을 기록했는지이며, 알림은 호출자가 메모리 전이를 적용한 뒤 실행한다.
     *
     * @param persistState 트랜잭션 안에서 실행될 상태 저장(전이가 반영된 사본을 upsert)
     */
    suspend fun commitFill(
        persistState: suspend () -> Unit,
        record: TradeRecord,
    ): Boolean {
        val recorded = try {
            transactionalOperator.transactional(
                mono {
                    persistState()
                    saveAudit(record)
                }
            ).awaitSingle()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(
                "Fill commit failed (rolled back — pending kept for reconcile): userId={}, market={}, side={}",
                record.userId, record.ticker, record.side, e,
            )
            throw e
        }
        return recorded
    }

    /**
     * 수동 주문 경로용 — 자체 트랜잭션으로 감사 기록을 저장하고 알림까지 보낸다.
     * 엔진 체결 경로는 상태 전이와 원자화가 필요하므로 이 함수가 아니라 `saveAudit`+`notifyTrade` 를 쓴다(#52).
     */
    suspend fun saveAndNotify(
        record: TradeRecord,
        client: UpbitClient,
        username: String?,
        discordWebhookUrl: String?,
    ) {
        // trade_records 와 trade_executions 를 한 트랜잭션으로 묶어 한쪽만 남는 audit 불일치를 방지.
        // (R2DBC suspend 에선 @Transactional 대신 TransactionalOperator 사용)
        val recorded = try {
            transactionalOperator.transactional(mono { saveAudit(record) }).awaitSingle()
        } catch (e: Exception) {
            log.error(
                "Failed to persist trade atomically (rolled back): userId={}, market={}, side={}",
                record.userId, record.ticker, record.side, e,
            )
            throw e
        }
        if (!recorded) return
        notifyTrade(record, client, username, discordWebhookUrl)
    }

    /**
     * placeOrder 성공 이후의 기록/알림 단계. 주문은 이미 접수됐으므로 이 단계 실패는 failure(재시도 유발) 로 되돌리지 않고
     * success(uuid, recorded=false) 로 알려 사용자가 이중 접수하지 않게 한다. record 생성(getTicker 등)도 이 경계 안에서
     * 수행해 그 실패까지 흡수한다. 수동·엔진 주문 모두 동일 audit 경로(trade_executions) 를 거친다.
     */
    private suspend fun recordOrder(
        client: UpbitClient,
        orderUuid: String,
        market: String,
        username: String?,
        discordWebhookUrl: String?,
        buildRecord: suspend () -> TradeRecord,
    ): TradeExecutionResult {
        return try {
            saveAndNotify(buildRecord(), client, username, discordWebhookUrl)
            TradeExecutionResult.success(orderUuid)
        } catch (e: Exception) {
            log.error("Order placed but not recorded: orderUuid={}, market={}", orderUuid, market, e)
            TradeExecutionResult.success(orderUuid, recorded = false)
        }
    }
}

data class TradeExecutionResult(
    val success: Boolean,
    val orderUuid: String? = null,
    val error: String? = null,
    // 주문은 접수됐으나(success) 기록/알림 후처리가 실패한 경우 false — 호출자가 재시도 대신 경고를 노출하도록.
    val recorded: Boolean = true,
) {
    companion object {
        fun success(orderUuid: String, recorded: Boolean = true) =
            TradeExecutionResult(success = true, orderUuid = orderUuid, recorded = recorded)
        fun failure(error: String) = TradeExecutionResult(success = false, error = error)
    }
}
