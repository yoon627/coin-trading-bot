package com.trading.bot.engine

import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.FeeKind
import com.trading.bot.domain.TradePnl
import com.trading.bot.domain.TradeRecord
import com.trading.bot.notification.TradeNotificationPayload
import com.trading.bot.notification.TradeNotificationQueued
import com.trading.bot.persistence.TradeNotificationOutboxRepository
import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity
import com.trading.common.config.TradingProperties
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator

@Service
class TradeExecutionService(
    private val outboxRepository: TradeNotificationOutboxRepository,
    private val orderLedger: OrderLedger,
    private val transactionalOperator: TransactionalOperator,
    private val tradingProperties: TradingProperties,
    private val events: ApplicationEventPublisher = ApplicationEventPublisher {},
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 주문 경로가 체결이 아닌 생애를 남기는 곳. 체결과 같은 orders 줄이라 같은 장부를 쓴다. */
    val orderLifecycle: OrderLifecycle get() = orderLedger

    /**
     * 거래 기록과 그 체결 알림 이벤트를 저장한다. 트랜잭션을 직접 열지 않고 호출자의 트랜잭션에 참여한다.
     * 이미 기록된 주문이면 건너뛰고 false 를 돌려준다. [notificationPayload] 는 트랜잭션 전에 만든 JSON 이다.
     */
    internal suspend fun saveAudit(record: TradeRecord, notificationPayload: String = TradeNotificationPayload.toJson(record)): Boolean {
        val (fee, feeKind) = fee(record)
        val orderRowId = when (val recorded = orderLedger.recordFill(record, fee, feeKind)) {
            // 재시작 뒤 같은 주문을 다시 확정해도 이중으로 기록·알림하지 않는다.
            OrderLedger.FillRecord.AlreadyRecorded -> {
                log.info("Trade already recorded — idempotent skip: userId={}, market={}, orderId={}", record.userId, record.ticker, record.exchangeOrderId)
                return false
            }
            is OrderLedger.FillRecord.Recorded -> recorded.orderRowId
        }
        // 체결과 같은 트랜잭션이라 이 삽입이 실패하면 체결도 되돌아간다. 그래서 실패할 경로(직렬화·길이 제한)를 두지 않았고,
        // FK 가 가리키는 orders 줄은 같은 트랜잭션에서 방금 넣었거나 FILLED 로 바꾼 줄이다.
        val now = clock.instant()
        outboxRepository.save(
            TradeNotificationOutboxEntity(
                orderId = orderRowId,
                userId = record.userId,
                exchange = record.exchange.name,
                payload = notificationPayload,
                nextAttemptAt = now,
                createdAt = now,
            )
        ).awaitSingle()
        return true
    }

    /** 기록할 수수료와 그 근거. 모르면 null 이다. */
    private fun fee(record: TradeRecord): Pair<Double?, FeeKind> = when (val basis = record.fee) {
        // NaN 이 한 번 들어가면 SUM(fee) 가 계속 NaN 이 되므로 DB 직전에서 다시 거른다.
        is FeeBasis.Measured -> basis.amount.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { it to FeeKind.MEASURED } ?: (null to FeeKind.UNRECORDED)
        // 거래소마다 매수·매도 비용이 다르면 생성부가 편도 요율을 싣는다.
        FeeBasis.Estimate -> (
            record.estimatedFeeRate?.takeIf { it.isFinite() && it >= 0.0 }?.let { record.totalAmount * it }
                ?: TradePnl.estimatedFee(record.totalAmount, tradingProperties.roundTripFeeRate)
            ) to FeeKind.ESTIMATED
        FeeBasis.Unrecorded -> null to FeeKind.UNRECORDED
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
        val payload = TradeNotificationPayload.toJson(record)
        val recorded = try {
            transactionalOperator.transactional(
                mono {
                    persistState()
                    saveAudit(record, payload)
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
        // 커밋된 뒤에만 깨운다. 발송기가 못 받아도 행이 남아 주기 실행이 보낸다 — 그래서 여기서 실패해도 커밋 결과를 바꾸지 않는다.
        if (recorded) {
            try {
                events.publishEvent(TradeNotificationQueued)
            } catch (e: Exception) {
                log.warn("Could not wake the trade notification dispatcher (the 5 s pass will send it): {}", e.message)
            }
        }
        return recorded
    }
}
