package com.trading.bot.engine

import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.TradePnl
import com.trading.bot.domain.TradeRecord
import com.trading.bot.exchange.ExchangeClient
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

@Service
class TradeExecutionService(
    private val tradeRecordRepository: TradeRecordRepository,
    private val tradeExecutionRepository: TradeExecutionRepository,
    private val discordNotifier: DiscordNotifier,
    private val transactionalOperator: TransactionalOperator,
    private val tradingProperties: TradingProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 거래 기록을 저장한다. 트랜잭션을 직접 열지 않고 호출자의 트랜잭션에 참여한다.
     * 이미 기록된 주문이면 건너뛰고 false 를 돌려준다.
     */
    internal suspend fun saveAudit(record: TradeRecord): Boolean {
        val userId = record.userId ?: 0
        // 재시작 뒤 같은 주문을 다시 reconcile 해도 이중으로 기록·알림하지 않는다.
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
                // 저장된 행만 보고는 실측 수수료인지 추정치인지 구분할 수 없다.
                fee = when (val basis = record.fee) {
                    // NaN 이 한 번 들어가면 SUM(fee) 가 계속 NaN 이 되므로 DB 직전에서 다시 거른다.
                    is FeeBasis.Measured -> basis.amount.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                    FeeBasis.Estimate -> TradePnl.estimatedFee(record.totalAmount, tradingProperties.roundTripFeeRate)
                    // 0 은 미기록을 뜻한다(V21 스키마 규약).
                    FeeBasis.Unrecorded -> 0.0
                },
                pnlPercent = record.pnlPercent,
                pnlAmount = record.pnlAmount,
                reason = record.reason,
                strategy = record.strategy,
                exchangeOrderId = record.exchangeOrderId,
            )
        ).awaitSingle()
        return true
    }

    /** 커밋 뒤 트랜잭션 밖에서 보내는 체결 알림. Discord 발송 예외는 호출자가 처리한다. */
    suspend fun notifyTrade(
        record: TradeRecord,
        client: ExchangeClient,
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
        discordNotifier.sendTradeEmbed(record, krwBalance, discordWebhookUrl, username)
    }

    /**
     * 상태 저장과 거래 기록을 한 트랜잭션으로 커밋한다.
     * 따로 커밋하면 기록 저장이 실패했을 때 pending 이 이미 지워져 그 거래 기록을 잃는다.
     * 실패하면 예외를 그대로 던지고, 호출자는 메모리 상태를 바꾸지 않아야 다음 tick 에 다시 시도된다.
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
}
