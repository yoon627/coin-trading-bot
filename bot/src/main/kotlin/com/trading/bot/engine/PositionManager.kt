package com.trading.bot.engine

import com.trading.bot.client.awaitFill
import com.trading.bot.client.UpbitOrderErrorClassifier
import com.trading.bot.client.newOrderIdentifier
import com.trading.bot.domain.Account
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradePnl
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.BuyFill
import com.trading.bot.exchange.BuyRecovery
import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.exchange.FillAccounting
import com.trading.bot.exchange.FillPollPolicy
import com.trading.bot.exchange.MarketBuySize
import com.trading.bot.exchange.MarketRules
import com.trading.bot.exchange.OrderErrorClassifier
import com.trading.bot.exchange.OrderFailure
import com.trading.bot.exchange.OrderGate
import com.trading.bot.exchange.SellRecovery
import com.trading.bot.exchange.TradingCalendar
import com.trading.bot.exchange.UnknownOrderPolicy
import com.trading.bot.exchange.UpbitMarketRules
import com.trading.bot.exchange.UpbitTradingCalendar
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.ExitGates
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** trading_states.halt_reason·review_reason 컬럼 길이. */
private const val HALT_REASON_MAX_LENGTH = 200

class PositionManager(
    private val upbitClient: ExchangeClient,
    private val tradingProperties: TradingProperties,
    private val tradingStateService: TradingStateService,
    private val userId: Long,
    /** 상태 저장과 거래 기록을 한 트랜잭션으로 커밋한다. 기본값은 상태만 저장하는 테스트용이다. */
    private val commitFill: suspend (persistState: suspend () -> Unit, record: TradeRecord) -> Boolean =
        { persistState, _ -> persistState(); true },
    private val notifyTrade: suspend (record: TradeRecord) -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
    private val rules: MarketRules = UpbitMarketRules,
    private val calendar: TradingCalendar = UpbitTradingCalendar,
    private val orderGate: OrderGate = OrderGate.OPEN,
    private val fillPoll: FillPollPolicy = FillPollPolicy.UPBIT,
    private val fillAccounting: FillAccounting = BalanceFillAccounting(upbitClient),
    private val errorClassifier: OrderErrorClassifier = UpbitOrderErrorClassifier,
    unknownOrderPolicy: UnknownOrderPolicy = UnknownOrderPolicy.UPBIT,
    /** 운영 안내 문구에 넣는 거래소 이름. */
    private val displayName: String = "Upbit",
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 못 찾은 이력의 수명이 이 엔진과 같아야 해서 주입하지 않고 여기서 만든다.
    private val unknownOrders = UnknownOrderResolver(upbitClient, clock, unknownOrderPolicy, displayName)

    private suspend fun findAccount(currency: String): Account? =
        upbitClient.getAccounts().find { it.currency == currency }

    suspend fun syncPosition(ticker: String, state: TradingState) {
        try {
            val account = findAccount(ticker.substringAfter("-"))
            if (account != null) {
                // 매도 주문이 걸린 채 재시작하면 코인이 전부 locked 라, free 만 보면 보유 없음으로 잘못 동기화된다.
                val held = heldVolume(account, ourSellLockCeiling(state))
                if (held > 0.0) {
                    state.position = true
                    state.avgBuyPrice = account.avgBuyPriceDouble()
                    state.holdVolume = held
                    log.info("Synced existing position for {}: price={}, volume={}", ticker, state.avgBuyPrice, state.holdVolume)
                } else if (isUnattributableLock(account, state)) {
                    // 우리 주문으로 설명되지 않는 lock 은 보유로 세지 않고 매수만 막는다.
                    // 여기서 position 을 내리면 거래 기록 없이 포지션이 사라지므로 정리는 sell() 에 맡긴다.
                    if (!state.unattributableLockWarned) {
                        log.warn(
                            "Unattributable locked balance for {}: locked={} — blocking new entries until it clears",
                            ticker, account.lockedDouble(),
                        )
                        state.unattributableLockWarned = true
                    }
                    state.unsynced = true
                    return
                }
            }
            state.unsynced = false
            state.unattributableLockWarned = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 보유 여부를 모르므로 다음 tick 에 다시 동기화될 때까지 신규 매수를 막는다.
            state.unsynced = true
            log.warn("Failed to sync position for {}: {}", ticker, e.message)
        }
    }

    /** 매수 진입 가드. dust 를 흡수하는 매수만 [allowExisting] 으로 기존 보유를 허용한다. */
    private fun entryBlocked(ticker: String, state: TradingState, allowExisting: Boolean): Boolean {
        if (state.position && !allowExisting) {
            log.debug("Skip buy for {}: already holding position", ticker)
            return true
        }
        if (state.hasPendingBuy()) {
            log.debug("Skip buy for {}: pending order {} awaiting reconcile", ticker, state.pendingBuyRef())
            return true
        }
        if (state.hasPendingSell()) {
            // 결과를 모르는 매도가 남은 채 사면 그 매도의 확정이 새로 산 코인과 섞인다.
            log.debug("Skip buy for {}: pending sell {} awaiting reconcile", ticker, state.pendingSellRef())
            return true
        }
        if (state.unsynced) {
            // 보유 여부를 모르는 상태에서 사면 이중 포지션이 될 수 있다.
            log.debug("Skip buy for {}: position not synced with exchange — avoiding double entry", ticker)
            return true
        }
        if (state.pendingPersistFailed) {
            // pending 기록이 실패한 상태에서 주문하면 크래시 뒤 복구할 근거가 없다.
            log.warn("Skip buy for {}: pending persistence unhealthy — avoiding unrecoverable entry", ticker)
            return true
        }
        if (state.halted) {
            // halt 는 신규 진입만 막는다. 매도와 reconcile 은 계속 돌아야 포지션이 청산되지 못한 채 갇히지 않는다.
            log.warn("Skip buy for {}: halted ({})", ticker, state.haltReason)
            return true
        }
        return false
    }

    suspend fun buy(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        strategyName: String,
    ): TradeRecord? {
        // 팔 수 없는 dust 는 진입을 막지 않는다. 사면 합쳐서 새 포지션이 되고, 아래에서 실잔고로 다시 확인한다.
        val onDust = rules.isDust(state, currentPrice)
        if (entryBlocked(ticker, state, allowExisting = onDust)) return null
        val accounts = try {
            upbitClient.getAccounts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to fetch balance for buy {}: {}", ticker, e.message, e)
            return null
        }
        // 기동 뒤에 생긴 귀속 불명 lock 은 syncPosition 이 보지 못하므로 여기서도 확인하고 재동기화로 넘긴다.
        val coin = accounts.find { it.currency == ticker.substringAfter("-") }
        if (coin != null && isUnattributableLock(coin, state)) {
            if (!state.unattributableLockWarned) {
                log.warn("Unattributable locked balance for {} at buy: locked={} — deferring to re-sync", ticker, coin.lockedDouble())
                state.unattributableLockWarned = true
            }
            state.unsynced = true
            return null
        }
        val krw = accounts.find { it.currency == "KRW" }?.balanceDouble() ?: 0.0
        val investAmount = calculateInvestAmount(krw)
        if (investAmount < rules.minOrderNotional) {
            log.debug("Insufficient funds for {}: investAmount={}", ticker, investAmount)
            return null
        }
        // 손절가까지 내려가도 최소주문 이상이어야 손절할 수 있다.
        if (investAmount * (1 - tradingProperties.exitParamsSnapshot().maxLossPct / 100) < rules.minOrderNotional) {
            log.debug("Skip buy for {}: investAmount={} would be unsellable at the stop-loss", ticker, investAmount)
            return null
        }
        // 장부 밖 잔고도 주문 전 보유량에 넣어야 체결 판정이 이 주문의 증분만 본다.
        val priorVolume = coin?.let { heldVolume(it, ourSellLockCeiling(state)) } ?: 0.0
        if (onDust && !rules.isBelowMinOrder(priorVolume, currentPrice)) {
            // 기록이 낡아 실제로는 팔 수 있는 보유다. 그 위에 사면 이중 포지션이므로 실측값으로 고친다.
            log.info("Skip buy for {}: holding is sellable on the exchange (volume={}) — resyncing", ticker, priorVolume)
            state.holdVolume = priorVolume
            coin?.let { state.avgBuyPrice = it.avgBuyPriceDouble() }
            return null
        }
        val size = rules.buySize(investAmount, currentPrice) ?: run {
            log.debug("Skip buy for {}: investAmount={} buys nothing at price {}", ticker, investAmount, currentPrice)
            return null
        }
        return placeBuy(ticker, state, currentPrice, size, strategyName, priorVolume)
    }

    /**
     * identifier 를 먼저 기록한 뒤 주문한다. 응답을 못 받은 주문도 실제로 나갔을 수 있어서,
     * 접수 여부를 모르면 pending 을 유지하고 [reconcilePendingBuy] 가 identifier 로 확정한다.
     */
    private suspend fun placeBuy(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        size: MarketBuySize,
        strategyName: String,
        priorVolume: Double,
    ): TradeRecord? {
        // 아래 블록은 취소되지 않으므로, 정지가 시작됐으면 새 주문을 내지 않는다.
        currentCoroutineContext().ensureActive()
        if (!orderGate.mayPlace(ticker, TradeSide.BUY, clock.instant())) {
            log.debug("Skip buy for {}: order gate closed", ticker)
            return null
        }
        val identifier = newOrderIdentifier()
        // 체결 확인 전에 재시작해도 이전 진입 정보가 이어지지 않도록 진입 메타를 지우고 pending 을 기록한다.
        // dust 흡수일 때도 수량은 두고 메타만 지운다. 체결이 확정되면 실잔고로 덮어쓴다.
        state.clearEntryMeta()
        state.beginBuyOrder(identifier, strategyName, priorVolume)
        // 기록부터 체결 반영까지는 stop/reload 가 끊지 못하게 한다.
        // 그 사이 프로세스가 죽어도 기록된 identifier 로 재시작 뒤 확정한다.
        return withContext(NonCancellable) {
            try {
                upsertState(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 기록 없이 나간 주문은 크래시 뒤 확정할 방법이 없으므로 보내지 않는다.
                state.clearPendingBuy()
                log.warn("Buy for {} not sent — could not record order {} first: {}", ticker, identifier, e.message)
                return@withContext null
            }
            val order = try {
                upbitClient.placeOrder(rules.buyOrderRequest(ticker, size, identifier))
            } catch (e: CancellationException) {
                throw e // 전송 여부는 모르지만 identifier 가 남아 있어 reconcile 이 확정한다.
            } catch (e: Exception) {
                if (errorClassifier.classify(e) != OrderFailure.UNKNOWN_OUTCOME) {
                    log.error("Failed to place buy order {}: {}", ticker, e.message, e)
                    orderGate.onDefinitiveRejection(ticker, TradeSide.BUY, clock.instant())
                    state.clearPendingBuy()
                    persist(state)
                } else {
                    log.error("Buy order {} for {} has an unknown outcome — kept pending, confirming by identifier: {}", identifier, ticker, e.message, e)
                }
                return@withContext null
            }
            if (order.uuid.isBlank()) {
                log.error("Buy order {} for {} was answered without a uuid — kept pending, confirming by identifier", identifier, ticker)
                return@withContext null
            }
            // 이후 체결 확인이 실패해도 다음 tick reconcile 이 이 uuid 로 이어받아 중복 매수를 막는다.
            state.adoptBuyOrder(order.uuid)
            persistPending(state)
            try {
                val filled = upbitClient.awaitFill(order.uuid, fillPoll)
                applyFillOutcome(ticker, state, currentPrice, filled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Buy post-order processing failed for {} (pending kept for reconcile): {}", ticker, e.message, e)
                null
            }
        }
    }

    /**
     * 미해소 매수 주문을 거래소 상태로 확정한다. getOrder 가 실패하면 [fillAccounting] 의 판정으로 복원하고,
     * uuid 없이 identifier 만 있으면 [resolveBuyByIdentifier] 로 넘긴다.
     */
    suspend fun reconcilePendingBuy(ticker: String, state: TradingState, currentPrice: Double): TradeRecord? {
        val uuid = state.pendingBuyUuid
            ?: return state.pendingBuyIdentifier?.let { resolveBuyByIdentifier(ticker, state, currentPrice, it) }
        val filled = try {
            upbitClient.getOrder(uuid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("reconcile getOrder failed for {} ({}): falling back to balance", ticker, e.message)
            return when (val recovery = fillAccounting.recoverBuyWithoutOrder(ticker, state, currentPrice)) {
                // 주문 응답이 없어 수수료와 주문 금액을 알 수 없으므로 추정하지 않고 미기록으로 둔다.
                // 커밋 실패는 조회 장애가 아니라서 halt 카운터에 세지 않고 호출부로 올린다.
                is BuyRecovery.Filled -> completeBuy(ticker, state, recovery.fill, FeeBasis.Unrecorded, orderAmount = null)
                // 아직 체결되지 않았음을 확인한 정상 판정이라 halt 카운터를 리셋한다.
                BuyRecovery.NoChange -> {
                    log.warn("reconcile pending kept for {}: order unknown and no balance", ticker)
                    state.reconcileFailureCount = 0
                    persist(state) // 재시작 뒤 옛 카운터로 halt 되지 않게 저장한다
                    null
                }
                // getOrder 와 잔고 조회가 모두 실패하면 상태를 볼 수 없으므로 halt 카운터를 올린다.
                is BuyRecovery.LookupFailed -> {
                    log.warn("reconcile balance recovery failed for {} ({}) — pending kept", ticker, recovery.message)
                    recordReconcileFailure(state)
                    persist(state)
                    null
                }
                BuyRecovery.Unsupported -> {
                    recordReconcileFailure(state)
                    persist(state)
                    null
                }
            }
        }
        // getOrder 가 응답했으면 진전이 있으므로 카운터를 리셋한다.
        if (state.reconcileFailureCount != 0) {
            state.reconcileFailureCount = 0
            persist(state)
        }
        return applyFillOutcome(ticker, state, currentPrice, filled)
    }

    /**
     * 응답을 못 받은 매수를 확정한다. 잔고 변화만으로는 체결을 기록하지 않는다.
     * uuid 가 없으면 같은 시각의 수동 매매와 구분할 수 없고 중복 기록 방지 키도 없다.
     */
    private suspend fun resolveBuyByIdentifier(ticker: String, state: TradingState, currentPrice: Double, identifier: String): TradeRecord? {
        when (val verdict = unknownOrders.resolve(ticker, TradeSide.BUY, state, identifier)) {
            is UnknownOrderVerdict.Found -> {
                state.adoptBuyOrder(verdict.order.uuid)
                state.reconcileFailureCount = 0
                persist(state)
                return applyFillOutcome(ticker, state, currentPrice, verdict.order)
            }
            UnknownOrderVerdict.NotPlaced -> {
                state.clearPendingBuy()
                state.reconcileFailureCount = 0
            }
            is UnknownOrderVerdict.NeedsReview -> markNeedsReview(ticker, state, TradeSide.BUY, verdict.reason)
            is UnknownOrderVerdict.Undecided -> when (verdict.reason) {
                // 주문 상태를 볼 수단이 없으면 halt 카운터로 사람을 부른다.
                UnknownOrderVerdict.Reason.ORDER_LOOKUP_FAILED, UnknownOrderVerdict.Reason.BALANCE_UNSEEN -> recordReconcileFailure(state)
                // 카운터는 확정됐을 때만 리셋한다. 404 마다 리셋하면 간헐 장애가 halt 를 계속 미뤄
                // 매매가 멈춘 채 알림이 나가지 않는다.
                UnknownOrderVerdict.Reason.TRACED, UnknownOrderVerdict.Reason.AWAITING_CONFIRMATION,
                UnknownOrderVerdict.Reason.NOT_YET_VISIBLE -> Unit
            }
        }
        persist(state)
        return null
    }

    /**
     * 매수 체결 결과를 반영한다. wait 는 부분체결이어도 확정하지 않는다. pending 을 풀면 이후 체결분을 기록할 길이 없다.
     * 끝난 주문은 체결량이 있으면 확정한다. 잔량을 환불하고 cancel 로 끝난 시장가 매수도 코인은 받았다.
     */
    private suspend fun applyFillOutcome(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        filled: Order?,
    ): TradeRecord? {
        val executed = filled?.executedVolume?.toDoubleOrNull() ?: 0.0
        if (filled?.state == "wait") {
            val snapshot = "${filled.executedVolume}/${filled.remainingVolume}"
            if (executed > 0.0 && state.partialBuyFillLogged != snapshot) {
                log.info(
                    "Partial fill while waiting for {} buy order {}: executed={} remaining={} — kept pending",
                    ticker, filled.uuid, filled.executedVolume, filled.remainingVolume,
                )
                state.partialBuyFillLogged = snapshot
            }
            return null
        }
        return when {
            // executed > 0 이면 filled 는 null 이 아니지만 smart cast 를 위해 함께 쓴다.
            filled != null && executed > 0.0 -> {
                val fill = fillAccounting.buyFill(ticker, state, filled, executed, currentPrice)
                completeBuy(ticker, state, fill, filled.feeBasis(), terminalFunds(filled))
            }
            else -> {
                log.warn("Pending buy unfilled for {}: state={} — order abandoned", ticker, filled?.state)
                state.clearPendingBuy()
                persist(state)
                null
            }
        }
    }

    /**
     * [fill] 의 포지션 수량·평단으로 매수를 확정하고 기록을 만든다.
     * totalAmount 가 포지션 전체 원가라 수수료를 추정할 수 없어 [feeBasis] 는 호출자가 정한다.
     */
    private suspend fun completeBuy(
        ticker: String,
        state: TradingState,
        fill: BuyFill,
        feeBasis: FeeBasis,
        orderAmount: Double?,
    ): TradeRecord {
        val strategy = state.pendingBuyStrategy
        val orderUuid = state.pendingBuyUuid // markBought 가 지우기 전에 잡아 둔다. 중복 기록 방지 키다.
        val volume = fill.volume
        val fillPrice = fill.avgPrice
        val totalAmount = fillPrice * volume
        val record = TradeRecord(
            userId = userId,
            ticker = ticker,
            side = TradeSide.BUY,
            price = fillPrice,
            volume = volume,
            totalAmount = totalAmount,
            pnlPercent = null,
            pnlAmount = null,
            strategy = strategy,
            fee = feeBasis,
            // 이 주문에 실제로 오간 금액. totalAmount 는 포지션 전체 원가다.
            orderAmount = orderAmount,
            exchangeOrderId = orderUuid,
            exchange = state.exchange,
        )
        // 같은 전이를 저장용 사본과 메모리 원본에 적용하므로 now 를 고정한다.
        val now = LocalDateTime.now(TradingDay.KST)
        val tradingDate = calendar.tradingDate(now)
        val snapshot = tradingProperties.exitParamsSnapshot()
        val applyTransition: (TradingState) -> Unit = { s ->
            s.reconcileFailureCount = 0
            // 실잔고를 절대값으로 반영해 syncPosition 이 복원한 수량과 이중으로 세지 않는다.
            s.markBought(fillPrice, volume, strategy, replace = true, now = now, tradingDate = tradingDate)
            // markBought 뒤에 찍어야 신규 진입은 현재 설정을, 재시작 복원은 저장된 스냅샷을 쓴다.
            s.exitParams = s.exitParams ?: snapshot
        }
        commitFillAndApply(state, record, applyTransition)
        log.info("BUY {} filled: volume={}, avgPrice={}, amount={}", ticker, volume, fillPrice, totalAmount)
        return record
    }

    /** DB 커밋이 성공한 뒤에만 메모리 상태를 바꾸고, 새로 기록된 거래만 알린다. */
    private suspend fun commitFillAndApply(
        state: TradingState,
        record: TradeRecord,
        applyTransition: (TradingState) -> Unit,
    ) {
        val recorded = commitFill(
            { tradingStateService.upsert(userId, state.copy().also(applyTransition)) },
            record,
        )
        applyTransition(state)
        // 이 커밋에도 peakPrice 가 들어가므로 peak 재기록 표시를 지운다. 매도 뒤에는 달리 지울 경로가 없다.
        state.peakPersistFailed = false
        if (!recorded) return
        try {
            notifyTrade(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Trade notification failed after commit for {}: {}", record.ticker, e.message)
        }
    }

    private fun recordReconcileFailure(state: TradingState) {
        state.reconcileFailureCount++
        if (!state.halted && state.reconcileFailureCount >= tradingProperties.reconcileHaltThreshold) {
            state.halted = true
            state.haltReason = "pending reconcile ${state.reconcileFailureCount}회 연속 실패 (getOrder·잔고조회 장애)"
            // ERROR 로그는 DiscordErrorLogAppender 가 알림으로 보낸다.
            log.error(
                "[HALT] {} pending reconcile failed {} times — auto-trading stopped, manual clear required",
                state.ticker, state.reconcileFailureCount,
            )
        }
    }

    /**
     * 자동으로 확정할 수 없는 주문이 있어 그 종목의 신규 진입을 halt 로 막고 ERROR 로 한 번 알린다. pending 은 그대로 두고 저장은 호출부가 한다.
     * ERROR 로그는 DiscordErrorLogAppender 가 알림으로 보낸다. 같은 주문이면 [reason] 이 바뀌어도 다시 알리지 않는다.
     * [TradingState.reviewReason] 이 있는 동안 halt 해제는 거절된다. 그 사유는 pending 이 모두 비면(자동 확정 또는 pending 해제) 사라진다.
     */
    internal fun markNeedsReview(ticker: String, state: TradingState, side: TradeSide, reason: String) {
        val ref = state.pendingRef(side)?.ref
        val alertKey = "주문 확인 필요(${side.name} $ref)".take(HALT_REASON_MAX_LENGTH)
        if (state.halted && state.haltReason?.startsWith(alertKey) == true) return
        state.halted = true
        // 원문은 아래 ERROR 로그에만 남긴다. 길이를 넘기면 저장이 매번 실패해 체결 확정까지 커밋되지 못한다.
        state.haltReason = "$alertKey: $reason".take(HALT_REASON_MAX_LENGTH)
        state.reviewReason = state.haltReason
        log.error(
            "[REVIEW] {} {} 주문 {} 을 자동으로 확정할 수 없습니다 — 신규 진입을 멈춥니다. {} 주문 내역을 확인하세요: {}",
            ticker, side.name, ref, displayName, reason,
        )
    }

    /** 모든 저장이 이 함수를 거친다. 저장 스냅샷에 peakPrice 가 들어 있어 peak 재기록 표시도 여기서 지운다. */
    private suspend fun upsertState(state: TradingState) {
        tradingStateService.upsert(userId, state)
        state.peakPersistFailed = false
    }

    private suspend fun persist(state: TradingState) {
        try {
            upsertState(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("trading_state persist failed for {} — retry on next transition: {}", state.ticker, e.message)
        }
    }

    /** pending 을 저장한다. 실패하면 다시 기록될 때까지 신규 진입을 막는다. */
    private suspend fun persistPending(state: TradingState) {
        try {
            upsertState(state)
            state.pendingPersistFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.pendingPersistFailed = true
            log.error("pending persist failed for {} — blocking new entries until it is recorded: {}", state.ticker, e.message, e)
        }
    }

    /** 실패해도 다음 저장 때 다시 기록되므로 예외를 올리지 않는다. */
    internal suspend fun persistState(state: TradingState) = persist(state)

    /** dust 라 팔지 않는다는 경고를 포지션당 한 번 남긴다. 사람이 할 일이 없어 ERROR 로 올리지 않는다. */
    private fun warnDustOnce(ticker: String, state: TradingState, detail: String) {
        if (state.dustWarned) return
        log.warn(
            "Swing holding on {} is dust below the {} KRW minimum order ({}) — not selling it",
            ticker, rules.minOrderNotional.toLong(), detail,
        )
        state.dustWarned = true
    }

    /**
     * 더 살 수 없는 티커에 남은 dust 를 장부에서 내린다. 흡수도 매도도 못 해 계속 남기 때문이다.
     * 판 것이 아니라 거래 기록은 남기지 않고, 실잔고가 팔 수 있는 양이면 내리지 않는다.
     */
    suspend fun releaseDust(ticker: String, state: TradingState, currentPrice: Double) {
        // 결과를 모르는 매도가 있으면 내리지 않는다. markSold 가 그 매도의 pending 을 지운다.
        if (state.hasPendingSell()) return
        val account = try {
            findAccount(ticker.substringAfter("-"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to read balance before releasing dust on {}: {}", ticker, e.message)
            return
        }
        // 잠긴 코인은 풀려서 돌아올 수 있는데, 여기서 내리면 목록 밖이라 다시 편입할 길이 없다.
        if (account != null && account.lockedDouble() > 0.0) return
        val held = account?.let { heldVolume(it, 0.0) } ?: 0.0
        if (!rules.isBelowMinOrder(held, currentPrice)) {
            state.holdVolume = held // 기록이 낡았다. 팔 수 있는 보유로 계속 관리한다
            return
        }
        log.warn("Releasing unsellable dust on {} (volume={}) — the ticker is outside the list, so nothing can absorb it", ticker, held)
        state.markSold()
        persist(state)
    }

    /**
     * 신고점을 저장한다. 고점 갱신 tick 에만 호출되므로, 실패하면 [TradingState.peakPersistFailed] 를 남겨
     * 다음 tick 이나 [TradingEngine.flushUnpersisted] 가 다시 쓰게 한다.
     */
    internal suspend fun persistPeak(state: TradingState) {
        try {
            upsertState(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.peakPersistFailed = true
            log.warn("peak persist failed for {} — the stored peak lags until a later write succeeds: {}", state.ticker, e.message)
        }
    }

    /** halt 해제처럼 사용자 응답이 걸린 경로라 실패를 호출자에게 올린다. */
    internal suspend fun persistStateOrThrow(state: TradingState) = upsertState(state)

    /** pending 기록 실패로 막힌 진입은 이 재기록이 성공해야 풀린다. */
    internal suspend fun retryPendingPersistIfNeeded(state: TradingState) {
        if (state.pendingPersistFailed) persistPending(state)
    }

    /**
     * 미해소 매도 주문을 거래소 상태로 확정한다. getOrder 가 실패하면 [fillAccounting] 의 판정으로 추정하고, uuid 가 없으면 identifier 로 찾는다.
     * 주문 결과를 받고도 기록하지 못했으면 추정으로 넘어가지 않고 pending 을 둔다.
     */
    suspend fun reconcilePendingSell(ticker: String, state: TradingState, currentPrice: Double): TradeRecord? {
        val uuid = state.pendingSellUuid
        val identifier = state.pendingSellIdentifier
        val result = try {
            when {
                uuid != null -> confirmSellByUuid(ticker, state, currentPrice, uuid)
                identifier != null -> resolveSellByIdentifier(ticker, state, currentPrice, identifier)
                else -> return null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 조회 실패는 각 단계가 처리하므로 여기 오는 것은 기록 단계의 실패다.
            // 주문 결과가 있는데 잔고 추정으로 넘기면 실제 체결을 추정치로 기록하게 된다.
            log.warn("reconcile sell for {} was resolved but could not be recorded ({}) — pending kept", ticker, e.message, e)
            null
        }
        // pending 매도가 남아 있으면 그 티커는 손절도 평가하지 못하므로, 오래 끌면 ERROR 로 사람을 부른다.
        state.pendingSellRef()?.let { warnIfSellStuckTooLong(ticker, state, it) }
        persist(state)
        return result
    }

    /** 잔고 추정은 getOrder 가 실패했을 때만 한다. 기록 단계의 실패는 호출부가 처리한다. */
    private suspend fun confirmSellByUuid(ticker: String, state: TradingState, currentPrice: Double, uuid: String): TradeRecord? {
        val filled = try {
            upbitClient.getOrder(uuid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("reconcile sell getOrder failed for {} ({}): falling back to balance", ticker, e.message)
            return recoverSellWithoutOrder(ticker, state, currentPrice)
        }
        return applySellFillOutcome(ticker, state, currentPrice, filled)
    }

    /** 응답을 못 받은 매도를 확정한다. 매수와 같은 규칙으로, 잔고 변화만으로는 청산을 기록하지 않는다. */
    private suspend fun resolveSellByIdentifier(ticker: String, state: TradingState, currentPrice: Double, identifier: String): TradeRecord? =
        when (val verdict = unknownOrders.resolve(ticker, TradeSide.SELL, state, identifier)) {
            is UnknownOrderVerdict.Found -> {
                state.adoptSellOrder(verdict.order.uuid)
                persist(state)
                applySellFillOutcome(ticker, state, currentPrice, verdict.order)
            }
            UnknownOrderVerdict.NotPlaced -> {
                // 코인이 그대로이므로 포지션을 되살리고 다음 tick 에 다시 판다.
                // 미접수면 주문량이 지금 free 이하라 락 상한이 0 이 되어, 동기화와 pending 해제의 순서는 결과에 영향이 없다.
                if (!state.position) syncPosition(ticker, state)
                state.clearPendingSell()
                null
            }
            is UnknownOrderVerdict.NeedsReview -> {
                markNeedsReview(ticker, state, TradeSide.SELL, verdict.reason)
                null
            }
            // halt 는 신규 매수만 막으므로 매도는 세지 않는다. 경과 시간 알림이 사람을 부른다.
            is UnknownOrderVerdict.Undecided -> null
        }

    /**
     * 막힌 매도를 한 번 알린다. 재시작해도 리셋되지 않도록 횟수 대신 영속된 시작 시각으로 판정한다.
     * 임계는 reconcileHaltThreshold tick 을 시간으로 환산한 값이다.
     */
    private fun warnIfSellStuckTooLong(ticker: String, state: TradingState, orderRef: String) {
        if (state.pendingSellAlerted) return
        val since = state.pendingSellSince ?: run {
            // 시작 시각이 없는 옛 pending 은 지금부터 센다.
            state.pendingSellSince = clock.instant()
            return
        }
        val threshold = Duration.ofSeconds(
            tradingProperties.reconcileHaltThreshold.toLong() * tradingProperties.intervalSeconds,
        )
        val stuckFor = Duration.between(since, clock.instant())
        if (stuckFor < threshold) return
        // 알린 뒤에 표시한다. 먼저 표시하면 크래시나 전송 실패 때 알림을 잃는다. 중복 알림이 유실보다 낫다.
        log.error(
            "매도 reconcile 미해소 {}초 — {} 의 청산이 막혀 있습니다(주문 {}). 수동 확인 필요 — " +
                "봇을 정지하고 {} 주문 내역을 확인한 뒤(미체결이면 먼저 취소) POST /api/bot/pending/clear 로 해제할 수 있습니다.",
            stuckFor.seconds, ticker, orderRef, displayName,
        )
        state.pendingSellAlerted = true
    }

    suspend fun sell(ticker: String, state: TradingState, currentPrice: Double, reason: SellReason): TradeRecord? {
        if (!state.position) return null
        // 미해소 매도가 있으면 확정될 때까지 새로 팔지 않는다.
        if (state.hasPendingSell()) {
            log.debug("Skip sell for {}: pending sell {} awaiting reconcile", ticker, state.pendingSellRef())
            return null
        }

        // 주문 수량은 state.holdVolume 이 아니라 거래소 실잔고를 쓴다.
        val account = try {
            findAccount(ticker.substringAfter("-"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to fetch balance for sell {}: {}", ticker, e.message, e)
            return null
        }
        val sellable = account?.balanceDouble() ?: 0.0
        if (sellable <= 0.0) {
            // 미해소 매도가 없으니 locked 는 출금 대기나 수동 주문이다. 여기서 보유를 내리지 않으면 syncPosition 과
            // 서로 미뤄 유령 포지션이 남는다. 진입 메타는 남기고 재동기화에 넘겨, 락이 풀리면 다시 편입된다.
            val locked = account?.lockedDouble() ?: 0.0
            if (locked > 0.0) {
                log.warn(
                    "Sell aborted for {}: free balance 0 and locked={} is not ours (no pending sell) — releasing holdings, re-sync will re-adopt them if the lock clears",
                    ticker, locked,
                )
                state.releaseHoldings()
                state.unsynced = true
            } else {
                log.warn("Sell aborted for {}: no balance on exchange — clearing phantom position", ticker)
                state.markSold()
            }
            persist(state)
            return null
        }

        // free 전량을 거래소가 준 잔고 문자열 그대로 시장가로 판다.
        val orderVolume = account!!.balance
        // 최소주문 미만이면 거래소가 매번 거절하므로 보내지 않는다.
        if (rules.isDustVolume(sellable, currentPrice)) {
            // 실측 수량을 남겨 dust 로 보고 진입을 열게 한다. 락이 풀려 free 가 돌아오면 다음 청산 판정에서 판다.
            state.holdVolume = heldVolume(account, ourSellLockCeiling(state))
            warnDustOnce(ticker, state, "%s × %.0f".format(orderVolume, currentPrice))
            return null
        }
        // [placeBuy] 와 같은 순서로, 정지 중이면 시작하지 않고 identifier 를 먼저 기록한 뒤 보낸다.
        currentCoroutineContext().ensureActive()
        if (!orderGate.mayPlace(ticker, TradeSide.SELL, clock.instant())) {
            log.debug("Skip sell for {}: order gate closed", ticker)
            return null
        }
        val identifier = newOrderIdentifier()
        // 재시작하면 잔고와 평단이 비므로 청산 기록에 쓸 값을 주문 시점에 남긴다. 판단 가격도 남겨
        // reconcile 이 늦게 확정해도 이 가격으로 기록하고, 주문 전 free 는 응답 없는 주문의 흔적 판정에 쓴다.
        state.beginSellOrder(identifier, reason, clock.instant(), sellable, currentPrice, sellable)
        // 매수와 같이 기록부터 체결 반영까지 취소가 끊지 못하게 한다.
        return withContext(NonCancellable) {
            // 기록 장애가 손절을 막으면 안 되므로 매수와 달리 기록이 실패해도 보낸다.
            // 이중 매도는 메모리의 identifier 가 막고, 크래시 창은 pendingPersistFailed(진입 차단·매 tick 재기록)가 좁힌다.
            persistPending(state)
            val order = try {
                upbitClient.placeOrder(
                    OrderRequest(
                        market = ticker,
                        side = "ask",
                        ordType = "market",
                        volume = orderVolume,
                        identifier = identifier,
                    )
                )
            } catch (e: CancellationException) {
                throw e // 전송 여부는 모르지만 identifier 가 남아 있어 reconcile 이 확정한다.
            } catch (e: Exception) {
                val failure = errorClassifier.classify(e)
                if (failure != OrderFailure.UNKNOWN_OUTCOME) {
                    if (failure == OrderFailure.REJECTED_BELOW_MIN_ORDER) {
                        // tick 가격으로는 통과했지만 거래소 기준으로는 최소주문 미만이다. 거래소 판정을 따른다.
                        warnDustOnce(ticker, state, "rejected by the exchange as below the minimum order")
                    } else {
                        log.error("Failed to place sell order {}: {}", ticker, e.message, e)
                    }
                    orderGate.onDefinitiveRejection(ticker, TradeSide.SELL, clock.instant())
                    state.clearPendingSell() // 코인은 그대로라 다음 tick 에 다시 판다
                    persist(state)
                } else {
                    log.error("Sell order {} for {} has an unknown outcome — kept pending, confirming by identifier: {}", identifier, ticker, e.message, e)
                }
                return@withContext null
            }
            if (order.uuid.isBlank()) {
                log.error("Sell order {} for {} was answered without a uuid — kept pending, confirming by identifier", identifier, ticker)
                return@withContext null
            }
            // 이후 체결 확인이 실패해도 다음 tick reconcile 이 이 uuid 로 이어받아 이중 매도와 기록 누락을 막는다.
            state.adoptSellOrder(order.uuid)
            persistPending(state)
            try {
                val filled = upbitClient.awaitFill(order.uuid, fillPoll)
                if (filled?.state == "done") {
                    // free 전량이 팔렸다. 남은 locked 는 우리 주문 것이 아니다.
                    completeSellAtomically(
                        ticker, state, currentPrice, sellable, reason,
                        remaining = 0.0,
                        // 판단 가격과의 차이가 실행 슬리피지다.
                        executedVwap = filled.filledVwap(),
                        feeBasis = sellFeeBasis(filled),
                        orderAmount = terminalFunds(filled),
                    )
                } else {
                    // wait·cancel·부분체결은 다음 tick reconcile 이 확정한다.
                    log.warn("Sell not confirmed for {}: state={} — pending kept for reconcile", ticker, filled?.state)
                    persist(state)
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Sell post-order processing failed for {} (pending kept for reconcile): {}", ticker, e.message, e)
                null
            }
        }
    }

    /**
     * 매도 체결 결과를 반영한다. Upbit 는 wait 중 미체결 잔량을 locked 로 묶어 free 가 0 일 수 있으므로,
     * 부분체결이어도 done·cancel 로 끝났을 때만 확정한다. 그 전에 확정하면 열린 주문을 청산으로 오판한다.
     */
    private suspend fun applySellFillOutcome(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        filled: Order?,
    ): TradeRecord? {
        if (filled?.state == "wait") {
            // Upbit 가 wait 중 부분체결을 remaining_volume 에 반영하는지 문서에 없어 관측값을 남긴다.
            val snapshot = "${filled.executedVolume}/${filled.remainingVolume}"
            if ((filled.executedVolume?.toDoubleOrNull() ?: 0.0) > 0.0 && state.partialSellFillLogged != snapshot) {
                log.info(
                    "Partial fill while waiting for {} order {}: executed={} remaining={} of {}",
                    ticker, filled.uuid, filled.executedVolume, filled.remainingVolume, filled.volume,
                )
                state.partialSellFillLogged = snapshot
            }
            return null
        }
        val executed = filled?.executedVolume?.toDoubleOrNull() ?: 0.0
        return when {
            filled != null && executed > 0.0 -> {
                // buildSellRecord 는 평단이 필요해 전이 전에 만들고, 잔량 계산도 트랜잭션 밖에서 끝낸다.
                val record = buildSellRecord(
                    ticker, state, currentPrice, executed,
                    executedVwap = filled.filledVwap(), feeBasis = sellFeeBasis(filled), orderAmount = terminalFunds(filled),
                )
                val (remaining, recoveredAvg) = fillAccounting.sellRemainder(ticker, state, filled, executed)
                val now = LocalDateTime.now(TradingDay.KST)
                commitFillAndApply(state, record, sellTransition(remaining, recoveredAvg, now))
                if (remaining > 0.0) {
                    log.info(
                        "SELL {} partial via reconcile: executed={}, remaining={}, price={} (tick {}) — position kept",
                        ticker, executed, remaining, record.price, currentPrice,
                    )
                } else {
                    log.info("SELL {} filled via reconcile: volume={}, price={} (tick {}), reason={}", ticker, executed, record.price, currentPrice, record.reason)
                }
                record
            }
            else -> {
                // 체결 없이 끝났다. 복원 직후라 position 이 false 일 수 있어 실잔고로 되살리고 다음 tick 에 다시 판다.
                log.warn("Pending sell unfilled for {}: state={} — order abandoned, position kept", ticker, filled?.state)
                if (!state.position) syncPosition(ticker, state)
                state.clearPendingSell()
                null
            }
        }
    }

    /** getOrder 가 실패한 매도를 [fillAccounting] 의 판정으로 확정한다. pending 이 있는 동안의 보유 변화는 이 매도의 결과라고 본다. */
    private suspend fun recoverSellWithoutOrder(ticker: String, state: TradingState, currentPrice: Double): TradeRecord? {
        when (val recovery = fillAccounting.recoverSellWithoutOrder(ticker, state)) {
            is SellRecovery.LookupFailed -> {
                log.warn("reconcile sell balance recovery failed for {} ({}) — pending kept", ticker, recovery.message)
                return null
            }
            is SellRecovery.StillHeld -> {
                log.warn("reconcile sell pending kept for {}: order unknown and balance remains (total={})", ticker, recovery.total)
                return null
            }
            // halt 는 신규 매수만 막으므로 매도는 세지 않는다. 경과 시간 알림이 사람을 부른다.
            SellRecovery.Unsupported -> return null
            SellRecovery.Cleared -> Unit
        }
        // 재시작 뒤에는 holdVolume 이 이미 0 이라 주문 시점 수량을 먼저 쓴다.
        val volume = state.pendingSellVolume ?: state.holdVolume
        if (volume <= 0.0) {
            // 수량 근거 없이 확정하면 수량 0 짜리 SELL 이 기록된다.
            log.error("reconcile sell for {}: zero balance but no recorded sell volume — pending kept for manual review", ticker)
            return null
        }
        // 주문 응답이 없어 매도 대금 기준으로 수수료를 추정한다.
        val record = buildSellRecord(ticker, state, currentPrice, volume, feeBasis = FeeBasis.Estimate, orderAmount = null)
        val now = LocalDateTime.now(TradingDay.KST)
        // 커밋이 실패하면 호출부로 올라가고 pending 이 남아 다음 tick 에 다시 시도한다.
        commitFillAndApply(state, record, sellTransition(remaining = 0.0, recoveredAvg = 0.0, now = now))
        log.info("SELL {} recovered from zero balance (getOrder down): volume={}", ticker, volume)
        return record
    }

    /**
     * sell() 에서 바로 done 을 받았을 때의 확정. 커밋이 실패하면 메모리 상태가 바뀌지 않아
     * pending 이 남고 다음 tick reconcile 이 다시 시도한다.
     */
    private suspend fun completeSellAtomically(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        volume: Double,
        reason: SellReason?,
        remaining: Double,
        executedVwap: Double?,
        feeBasis: FeeBasis,
        orderAmount: Double?,
    ): TradeRecord {
        val record = buildSellRecord(ticker, state, currentPrice, volume, reason, executedVwap, feeBasis, orderAmount)
        val now = LocalDateTime.now(TradingDay.KST)
        commitFillAndApply(state, record, sellTransition(remaining, state.avgBuyPrice, now))
        log.info(
            "SELL {} filled: price={}, volume={}, net pnl={}%, reason={}",
            ticker, record.price, volume, record.pnlPercent?.let { "%.2f".format(it) } ?: "-", record.reason,
        )
        return record
    }

    /**
     * 매도 기록을 만든다. 손익은 왕복 수수료를 뺀 값이고, 청산 판정은 수수료를 빼지 않는다.
     * 평단과 진입 전략이 필요하므로 markSold 전에 호출한다. 가격은 매도를 결정한 tick 의 가격이다.
     */
    private fun buildSellRecord(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        volume: Double,
        reason: SellReason? = state.pendingSellReason,
        executedVwap: Double? = null,
        feeBasis: FeeBasis,
        orderAmount: Double?,
    ): TradeRecord {
        // 재시작 복원 뒤에는 avgBuyPrice 가 0 이라 주문 시점 평단을 쓴다.
        val basisPrice = if (state.avgBuyPrice > 0) state.avgBuyPrice else state.pendingSellAvgPrice ?: 0.0
        val decisionPrice = state.pendingSellTriggerPrice ?: currentPrice
        val pnl = TradePnl.netPercent(decisionPrice, basisPrice, tradingProperties.roundTripFeeRate)
        return TradeRecord(
            userId = userId,
            ticker = ticker,
            side = TradeSide.SELL,
            price = decisionPrice,
            volume = volume,
            totalAmount = decisionPrice * volume,
            executedVwap = executedVwap,
            pnlPercent = pnl,
            pnlAmount = TradePnl.amount(pnl, basisPrice, volume),
            // 청산 성과는 진입 전략에 귀속한다. 매도 시점 전략을 쓰면 설정을 바꾼 뒤의 청산이 엉뚱한 전략으로 잡힌다.
            strategy = state.entryStrategy,
            fee = feeBasis,
            // totalAmount 는 판단 가격 기준 평가액이고 이것이 실제 체결 대금이다.
            orderAmount = orderAmount,
            reason = reason?.name,
            // 재시작 뒤 reconcile 이 같은 주문을 중복 기록하지 않게 하는 키다.
            exchangeOrderId = state.pendingSellUuid,
            exchange = state.exchange,
        )
    }

    /** 주문 응답에 paid_fee 가 있으면 실측, 없으면 추정이다. 매도의 totalAmount 는 이 체결의 대금이라 추정 기준으로 쓸 수 있다. */
    private fun sellFeeBasis(filled: Order?): FeeBasis = filled?.sellFeeBasis() ?: FeeBasis.Estimate

    /** done·cancel 로 끝난 응답의 체결 대금. wait 중의 합은 최종값이 아니라 쓰지 않는다. */
    private fun terminalFunds(filled: Order?): Double? =
        filled?.takeIf { it.isTerminal() }?.filledFunds()

    /** 매도를 확정하는 모든 경로가 이 전이를 쓴다. */
    private fun sellTransition(
        remaining: Double,
        recoveredAvg: Double,
        now: LocalDateTime,
    ): (TradingState) -> Unit =
        { s ->
            if (remaining > 0.0) {
                // 부분 체결. 복원 때 잔고가 매도 주문에 잠겨 position 이 false 였을 수 있어 되살린다.
                s.position = true
                s.holdVolume = remaining
                if (s.avgBuyPrice <= 0.0) s.avgBuyPrice = recoveredAvg
                s.clearPendingSell()
            } else {
                s.markSold(now)
            }
        }

    fun checkTakeProfit(state: TradingState, currentPrice: Double): Boolean {
        if (!state.position) return false
        return state.pnlPercent(currentPrice) >= state.exitParamsOrGlobal(tradingProperties).takeProfitPct
    }

    fun checkStopLoss(state: TradingState, currentPrice: Double): Boolean {
        if (!state.position) return false
        return state.pnlPercent(currentPrice) <= -state.exitParamsOrGlobal(tradingProperties).maxLossPct
    }

    fun checkTrailingStop(state: TradingState, currentPrice: Double): Boolean {
        if (!state.position) return false
        state.updatePeakPrice(currentPrice)
        val params = state.exitParamsOrGlobal(tradingProperties)
        return ExitGates.isTrailingStopTriggered(
            pnlPct = state.pnlPercent(currentPrice),
            peakPnlPct = state.pnlPercent(state.peakPrice),
            dropFromPeakPct = state.dropFromPeakPercent(currentPrice),
            trailingStopPct = params.trailingStopPct,
            trailingArmPct = params.trailingArmPct,
        )
    }

    private fun calculateInvestAmount(krwBalance: Double): Double {
        return minOf(krwBalance * tradingProperties.investRatio, tradingProperties.maxInvestAmount)
    }
}
