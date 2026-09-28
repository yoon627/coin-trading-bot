package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.TradePnl
import com.trading.bot.domain.TradeRecord
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
     * 감사 기록 저장만 수행 — **호출자가 연 트랜잭션에 참여**한다(자체 트랜잭션을 열지 않음).
     *
     * 엔진 체결 경로는 `trading_states` pending 해소와 이 저장을 한 트랜잭션으로 묶어야 한다(#52).
     * 따로 커밋하면 감사 저장이 실패했을 때 재시도 근거(pending)가 이미 사라져 기록이 영구 유실된다.
     *
     * @return 실제로 기록했으면 true, 이미 기록된 주문이라 건너뛰었으면 false(멱등).
     */
    internal suspend fun saveAudit(record: TradeRecord): Boolean {
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
                // 수수료 출처는 경로가 정한다(#133·#148·#105) — 엔진 매수와 모든 매도는 terminal 응답에 paid_fee 가
                // 있으면 실측, 매도는 없으면 추정, 매수 잔고복원은 미기록(과거 수동 매수 행은 추정). 한 행만 보고
                // 실측인지 추정인지 구분할 마커는 없다.
                fee = when (val basis = record.fee) {
                    // 파싱 단계에서 이미 거르지만 여기서 한 번 더 본다 — `Measured` 는 public 생성자라
                    // 다른 경로가 생기면 검증을 건너뛸 수 있고, NaN 이 컬럼에 들어가면 이후 SUM(fee) 이
                    // 영구히 NaN 이 된다(되돌릴 수 없다). DB 에 닿는 마지막 지점이 방어할 자리다.
                    is FeeBasis.Measured -> basis.amount.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                    FeeBasis.Estimate -> TradePnl.estimatedFee(record.totalAmount, tradingProperties.roundTripFeeRate)
                    // 0 = 미기록. V21 이 세운 규약이라 이 값을 새로 정의하지 않는다.
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

    /**
     * 커밋 후 알림. 잔고 조회·Discord 발송은 외부 IO 라 트랜잭션 밖에서 수행한다.
     * Discord 발송 예외는 호출자에게 전파한다 — PositionManager 가 이미 적용한 메모리 전이를 유지한 채 격리한다.
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
        discordNotifier.sendTradeEmbed(record, krwBalance, discordWebhookUrl, username)
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
}
