package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.client.awaitFill
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.FillOutcome
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradePnl
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.TradeExecutionRepository
import com.trading.bot.persistence.entity.TradeExecutionEntity
import com.trading.common.config.TradingProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
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

    private fun netPnlPercent(currentPrice: Double, avgBuyPrice: Double): Double? =
        TradePnl.netPercent(currentPrice, avgBuyPrice, tradingProperties.roundTripFeeRate)

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

        // 주문은 나갔으므로 기록·알림은 요청 취소에도 완주한다(매도 recordSellFill 과 같은 이유).
        return withContext(NonCancellable) {
            recordOrder(client, order.uuid, market, username, discordWebhookUrl) {
                val currentPrice = client.getTicker(market).firstOrNull()?.tradePrice ?: 0.0
                val volume = if (currentPrice > 0) amount / currentPrice else 0.0
                TradeRecord(
                    ticker = market,
                    side = TradeSide.BUY,
                    price = currentPrice,
                    volume = volume,
                    totalAmount = amount,
                    pnlPercent = null, // 진입 — 실현 손익 없음
                    pnlAmount = null,
                    strategy = strategy,
                    // totalAmount 가 이 주문의 금액이라 추정 기준이 맞다. placeOrder 응답은 체결 전이라
                    // paid_fee 를 신뢰할 수 없고, 확인하려면 getOrder 재조회가 필요하다(범위 밖 — #133).
                    fee = FeeBasis.Estimate,
                    // placeOrder 즉시 응답뿐이라 실체결 대금을 모른다 — 요청액을 넣지 않는다(#146).
                    orderAmount = null,
                    userId = userId,
                )
            }
        }
    }

    /**
     * 전량 매도 주문 실행 + 체결 확정 + 기록 저장 + Discord 알림
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

        // 평단은 주문 전 계좌값 — 전량 매도 뒤엔 통화 잔고가 사라져 다시 읽을 수 없다.
        return recordSellFill(
            client, order, market, requestedVolume = account.balance, avgBuyPrice = account.avgBuyPriceDouble(),
            strategy = strategy, userId = userId, username = username, discordWebhookUrl = discordWebhookUrl,
        )
    }

    /**
     * 지정 수량 매도 주문 실행 + 체결 확정 + 기록 저장 + Discord 알림
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

        return recordSellFill(
            client, order, market, requestedVolume = sellVolume, avgBuyPrice = avgBuyPrice,
            strategy = strategy, userId = userId, username = username, discordWebhookUrl = discordWebhookUrl,
        )
    }

    /**
     * 접수된 매도의 체결을 확정해 기록한다. terminal(done/cancel) 응답의 체결량만 기록하고, 확인하지 못하면
     * 요청 수량으로 폴백하지 않는다 — 틀린 수량은 라운드트립 조회가 보유 포지션을 청산으로 오판하게 한다(#105).
     * `wait` 는 executed>0 이어도 확정하지 않는다: 열린 주문의 잔여 체결분이 더 올 수 있고 수동 경로엔 이를
     * 다시 잡을 reconcile 이 없다. 미확정은 행 없이 사용자에게 알린다(uuid 로 거래소에서 대조).
     *
     * 주문은 이미 나갔으므로 여기서부터는 요청 취소에도 완주한다(엔진 `placeSell` 과 같은 이유 — 브라우저 이탈이 감사 유실이 되지 않게).
     */
    private suspend fun recordSellFill(
        client: UpbitClient,
        order: Order,
        market: String,
        requestedVolume: String,
        avgBuyPrice: Double,
        strategy: String,
        userId: Long,
        username: String?,
        discordWebhookUrl: String?,
    ): TradeExecutionResult = withContext(NonCancellable) {
        val filled = try {
            client.awaitFill(order.uuid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Manual sell fill lookup failed: userId={}, market={}, orderUuid={}: {}", userId, market, order.uuid, e.message)
            null
        }
        val terminal = filled?.takeIf { it.isTerminal() }
        val executed = terminal?.executedVolume?.toDoubleOrNull()?.takeIf { it.isFinite() }
        if (terminal == null || executed == null || executed <= 0.0) {
            // terminal 이고 체결이 0 이하면 확정된 미체결(취소). 그 외는 모른다 — 둘을 같은 경고로 묶으면 오경보가 된다.
            val outcome = if (terminal != null && executed != null) FillOutcome.NOT_FILLED else FillOutcome.UNCONFIRMED
            return@withContext skipRecord(outcome, order, market, requestedVolume, filled, userId, username, discordWebhookUrl)
        }
        val currentPrice = tickPriceOrZero(client, market)
        val filledFunds = terminal.filledFunds()
        if (currentPrice <= 0.0 && filledFunds == null) {
            // 체결은 알아도 대금을 전혀 못 구하면 totalAmount=0 행이 되어 라운드트립이 전액 손실로 계산한다 — 적지 않는다.
            return@withContext skipRecord(FillOutcome.UNCONFIRMED, order, market, requestedVolume, filled, userId, username, discordWebhookUrl)
        }

        recordOrder(client, order.uuid, market, username, discordWebhookUrl, fill = FillOutcome.CONFIRMED) {
            val pnl = netPnlPercent(currentPrice, avgBuyPrice)
            TradeRecord(
                ticker = market,
                side = TradeSide.SELL,
                price = currentPrice,
                volume = executed,
                // tick 을 못 읽으면 평가액 대신 실측 대금 — 0 을 적으면 라운드트립이 전액 손실로 계산한다. 추정이 아니라 실측이다.
                totalAmount = if (currentPrice > 0) currentPrice * executed else filledFunds ?: 0.0,
                pnlPercent = pnl,
                pnlAmount = TradePnl.amount(pnl, avgBuyPrice, executed),
                reason = SellReason.MANUAL.name,
                strategy = strategy,
                fee = terminal.sellFeeBasis(),
                orderAmount = filledFunds,
                executedVwap = terminal.filledVwap(),
                exchangeOrderId = order.uuid,
                userId = userId,
            )
        }
    }

    /** 행을 남기지 않는 결말 — WARN 로그(사후 대조용 uuid·수량·상태)와 사용자 알림. */
    private fun skipRecord(
        outcome: FillOutcome,
        order: Order,
        market: String,
        requestedVolume: String,
        filled: Order?,
        userId: Long,
        username: String?,
        discordWebhookUrl: String?,
    ): TradeExecutionResult {
        log.warn(
            "Manual sell {} — not recorded: userId={}, market={}, orderUuid={}, requested={}, state={}, executed={}",
            outcome, userId, market, order.uuid, requestedVolume, filled?.state, filled?.executedVolume,
        )
        notifyUnrecorded(outcome, market, order.uuid, requestedVolume, filled, discordWebhookUrl, username)
        return TradeExecutionResult.unrecorded(order.uuid, outcome)
    }

    /** 판단 시점 tick 가격. 조회 실패는 0 으로 두고 호출자가 실측 대금 폴백 여부를 정한다. */
    private suspend fun tickPriceOrZero(client: UpbitClient, market: String): Double =
        try {
            client.getTicker(market).firstOrNull()?.tradePrice ?: 0.0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Ticker lookup failed after manual sell: market={}: {}", market, e.message)
            0.0
        }

    private fun notifyUnrecorded(
        outcome: FillOutcome,
        market: String,
        orderUuid: String,
        requestedVolume: String,
        filled: Order?,
        discordWebhookUrl: String?,
        username: String?,
    ) {
        try {
            discordNotifier.sendOrderUnrecorded(
                outcome, market, orderUuid, requestedVolume, filled?.state, filled?.executedVolume, discordWebhookUrl, username,
            )
        } catch (e: Exception) {
            log.error("Unconfirmed-order alert failed: market={}, orderUuid={}", market, orderUuid, e)
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
                // 수수료 출처는 경로가 정한다(#133·#148·#105) — 엔진 매수와 모든 매도는 terminal 응답에 paid_fee 가
                // 있으면 실측, 매도는 없으면 추정, 수동 매수는 추정, 매수 잔고복원은 미기록. 한 행만 보고 실측인지
                // 추정인지 구분할 마커는 없다.
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
     * Discord 발송 예외는 호출자에게 전파한다. 수동 주문은 이를 recorded=false 로 노출하고,
     * 엔진 체결은 PositionManager가 이미 적용한 메모리 전이를 유지한 채 격리한다.
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
        fill: FillOutcome? = null,
        buildRecord: suspend () -> TradeRecord,
    ): TradeExecutionResult {
        return try {
            saveAndNotify(buildRecord(), client, username, discordWebhookUrl)
            TradeExecutionResult.success(orderUuid, fill = fill)
        } catch (e: CancellationException) {
            // 취소를 "후처리 실패"로 위장하면 호출자가 기록 여부를 오판한다 — 전파하되 경보(ERROR → Discord)는 남긴다.
            log.error("Order placed but recording was cancelled: orderUuid={}, market={}", orderUuid, market, e)
            throw e
        } catch (e: Exception) {
            log.error("Order placed but not recorded: orderUuid={}, market={}", orderUuid, market, e)
            TradeExecutionResult.success(orderUuid, recorded = false, fill = fill)
        }
    }
}

data class TradeExecutionResult(
    val success: Boolean,
    val orderUuid: String? = null,
    val error: String? = null,
    // 주문은 접수됐으나(success) 기록/알림 후처리가 실패한 경우 false — 호출자가 재시도 대신 경고를 노출하도록.
    // 행이 있을 수도(알림만 실패) 없을 수도 있다 — 행이 확실히 없는 경우는 fill=NOT_FILLED/UNCONFIRMED 가 함께 온다.
    val recorded: Boolean = true,
    val fill: FillOutcome? = null,
) {
    companion object {
        fun success(orderUuid: String, recorded: Boolean = true, fill: FillOutcome? = null) =
            TradeExecutionResult(success = true, orderUuid = orderUuid, recorded = recorded, fill = fill)
        fun unrecorded(orderUuid: String, fill: FillOutcome) =
            TradeExecutionResult(success = true, orderUuid = orderUuid, recorded = false, fill = fill)
        fun failure(error: String) = TradeExecutionResult(success = false, error = error)
    }
}
