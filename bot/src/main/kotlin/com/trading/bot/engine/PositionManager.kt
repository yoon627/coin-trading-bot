package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.client.awaitFill
import com.trading.bot.client.newOrderIdentifier
import com.trading.bot.client.isRejectedAsBelowMinimumOrder
import com.trading.bot.client.provesOrderNotPlaced
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
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.AccumulateLadder
import com.trading.common.strategy.ExitGates
import com.trading.common.strategy.LadderAction
import com.trading.common.strategy.LadderParams
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class PositionManager(
    private val upbitClient: UpbitClient,
    private val tradingProperties: TradingProperties,
    private val tradingStateService: TradingStateService,
    private val userId: Long,
    /**
     * #52: 체결 확정 시 **상태 전이 저장 + 감사 기록**을 한 트랜잭션으로 커밋한다. 실패하면 예외를 던져
     * 호출자가 메모리 전이를 적용하지 않게 하고, pending 을 남겨 다음 tick reconcile 이 재시도하게 한다.
     *
     * 기본값은 상태 저장만 하는 구현 — 감사 기록이 관심사가 아닌 단위 테스트용이다.
     * **프로덕션 배선은 `UserTradingManager.createEngine` 이 DB 커밋과 커밋 후 알림을 각각 주입한다.**
     */
    private val commitFill: suspend (persistState: suspend () -> Unit, record: TradeRecord) -> Boolean =
        { persistState, _ -> persistState(); true },
    private val notifyTrade: suspend (record: TradeRecord) -> Unit = {},
    /** 막힌 매도 경과시간 판정용. 테스트가 임계 경계를 정확히 검증할 수 있도록 주입한다(#55). */
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MIN_ORDER_AMOUNT_KRW = AccumulateLadder.MIN_ORDER_KRW
        // 적립 단 매도는 요청 대비 이 비율 이상 체결됐을 때만 rung 을 소모한다 — 10% 체결로 한 단을 지우면 사다리가 어긋난다.
        // 원가 정합(LadderStateMapper)의 허용치와 짝이라 common 에 둔다.
        private const val RUNG_FILL_RATIO = AccumulateLadder.SELL_FILL_RATIO
        private const val BUDGET_TOLERANCE_KRW = 1.0
        private const val VOLUME_SCALE = 8
        // 응답을 못 받은 주문을 미접수로 확정하는 조건 — 연속으로 못 찾은 횟수와 처음 못 찾은 뒤 지난 시간(#227).
        private const val NOT_PLACED_MISSES = 2
        private val NOT_PLACED_AFTER: Duration = Duration.ofSeconds(60)
    }

    /**
     * identifier 조회로 못 찾은 이력. 비영속 — 재시작·reload 하면 [misses] 는 처음부터 다시 센다(더 오래 기다리는 쪽이라
     * 안전하다). [traced] 는 잔고 흔적을 한 번이라도 봤다는 뜻이고 그 주문은 자동으로 풀지 않는다 — 단 이 엔진 안에서만이다.
     * 재시작·reload 뒤에는 흔적이 남아 있으면 다시 잡지만, 그 사이 사라졌으면 일반 규칙으로 풀린다.
     */
    private data class IdentifierMiss(val misses: Int = 0, val firstMissAt: Instant? = null, val traced: Boolean = false)

    // 키가 identifier 라 다른 주문의 기록이 섞이지 않는다. 확정되지 않고 풀린 항목은 남지만 불명 주문 수만큼이라 무시한다.
    private val identifierMisses = ConcurrentHashMap<String, IdentifierMiss>()

    /** 거래소 계좌 목록에서 해당 통화 계좌 조회 (#21 — getAccounts().find 중복 헬퍼화). */
    private suspend fun findAccount(currency: String): Account? =
        upbitClient.getAccounts().find { it.currency == currency }

    /**
     * @param clearWhenEmpty 확인된 무잔고를 포지션 해제로 반영한다. 기본 false — 스윙은 감사 기록 없는 청산을 막기 위해
     *   phantom 정리를 `sell()` 에 맡기지만, 적립의 주기 동기화는 수동 전량 매도 뒤에도 장부가 "보유"로 남아
     *   다음 하락에 추가 단을 사 버리므로 여기서 내려야 한다(사다리 장부는 매퍼가 이어서 비운다).
     */
    suspend fun syncPosition(ticker: String, state: TradingState, clearWhenEmpty: Boolean = false) {
        try {
            val account = findAccount(ticker.substringAfter("-"))
            var heldNow = 0.0
            if (account != null) {
                // free 만 보면 안 된다 — 매도 주문이 떠 있는 채로 재시작하면 코인 전량이 locked 라 free 가 0 이다.
                // 그걸 "보유 없음" 으로 동기화하면 손절·익절이 한 번도 평가되지 않는 무방비 보유가 되고,
                // boughtToday 가 풀리는 순간 그 위에 추가 매수까지 들어간다.
                val held = heldVolume(account, ourSellLockCeiling(state))
                heldNow = held
                if (held > 0.0) {
                    state.position = true
                    state.avgBuyPrice = account.avgBuyPriceDouble()
                    state.holdVolume = held
                    log.info("Synced existing position for {}: price={}, volume={}", ticker, state.avgBuyPrice, state.holdVolume)
                } else if (isUnattributableLock(account, state)) {
                    // 우리 주문으로 설명되지 않는 lock — 출금 대기이거나 사용자가 직접 낸 주문이다. 팔 수 없으니
                    // 보유로 세지 않고 매수만 막는다. position 은 건드리지 않는다 — 여기서 내리면 markSold 를
                    // 우회한 청산이 되어 감사 기록 없이 포지션이 사라진다(정리는 sell() 의 phantom 경로 몫).
                    // 판정은 syncPosition 이 도는 시점(기동·unsynced 재시도·매도 무산 복원)에만 이뤄진다.
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
            if (clearWhenEmpty && heldNow <= 0.0 && state.position) {
                log.warn("Position for {} is gone on the exchange (manual sell?) — clearing holdings", ticker)
                state.position = false
                state.holdVolume = 0.0
                state.avgBuyPrice = 0.0
            }
            // 조회 성공(보유 유무 무관) → 동기화 완료, 매수 차단 해소.
            state.unsynced = false
            state.unattributableLockWarned = false
        } catch (e: CancellationException) {
            throw e // 취소는 전파(unsynced 로 삼키지 않는다).
        } catch (e: Exception) {
            // 동기화 실패 → position 상태 불확실. unsynced 로 표시해 buy() 가 신규 진입을 막고 다음 tick 재시도(processTicker).
            state.unsynced = true
            log.warn("Failed to sync position for {}: {}", ticker, e.message)
        }
    }

    /** 매수 진입 가드. 적립 단 추가만 [allowExisting] 로 "이미 보유"를 허용하고 나머지 가드는 두 경로가 같다. */
    private fun entryBlocked(ticker: String, state: TradingState, allowExisting: Boolean): Boolean {
        // 재매수 가드: 이미 보유 중이거나, 미해소 매수 주문(pending)이 있으면 신규 매수 금지.
        if (state.position && !allowExisting) {
            log.debug("Skip buy for {}: already holding position", ticker)
            return true
        }
        if (state.hasPendingBuy()) {
            log.debug("Skip buy for {}: pending order {} awaiting reconcile", ticker, state.pendingBuyRef())
            return true
        }
        if (state.hasPendingSell()) {
            // 같은 tick 에 낸 매도가 결과 불명으로 남았는데 사면, 그 매도의 확정(잔량·잔고 흔적)이 새 코인과 섞인다 —
            // dust 흡수 매수가 이 게이트에 닿을 수 있다(#234).
            log.debug("Skip buy for {}: pending sell {} awaiting reconcile", ticker, state.pendingSellRef())
            return true
        }
        if (state.unsynced) {
            // 보유 여부가 불확실 — 잔고 조회 실패이거나, 우리 주문으로 설명 안 되는 locked 가 있는 경우다.
            // 어느 쪽이든 신규 매수는 이미 보유분과 이중 포지션 위험. skip(processTicker 가 재시도).
            log.debug("Skip buy for {}: position not synced with exchange — avoiding double entry", ticker)
            return true
        }
        if (state.pendingPersistFailed) {
            // 직전 pending durable 기록이 실패 — 지금 매수하면 크래시 시 pending 유실로 복구 불가. 재기록 성공 전까지 진입 차단.
            log.warn("Skip buy for {}: pending persistence unhealthy — avoiding unrecoverable entry", ticker)
            return true
        }
        if (state.halted) {
            // #19: reconcile 무한 실패로 halt — 신규 진입만 막는다. 매도·reconcile·잔고 동기화는 계속 돌아야
            // 이미 잡힌 포지션이 청산되지 못한 채 갇히지 않는다(수동 해제 전까지).
            log.warn("Skip buy for {}: halted ({})", ticker, state.haltReason)
            return true
        }
        return false
    }

    /** @param reservedKrw 적립 프로파일이 아직 투입하지 않은 예산 — 스윙 사이징에서 뺀다(알트가 적립 현금을 선점하지 못하게). */
    suspend fun buy(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        strategyName: String,
        reservedKrw: Double = 0.0,
    ): TradeRecord? {
        // 팔 수 없는 dust 보유는 진입을 막지 않는다 — 사면 합쳐서 새 진입이 된다(#234). 아래에서 실잔고로 다시 판정한다.
        val onDust = state.isDustAt(currentPrice)
        if (entryBlocked(ticker, state, allowExisting = onDust)) return null
        val accounts = try {
            upbitClient.getAccounts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to fetch balance for buy {}: {}", ticker, e.message, e)
            return null
        }
        // 기동 뒤 런타임에 생긴 귀속 불명 lock(출금 대기·수동 주문)은 syncPosition 이 안 돌아 못 본다 — 사이징용 잔고에서
        // 같은 판정을 하고 재동기화(processTicker 의 unsynced 경로)에 넘긴다. 포지션은 건드리지 않는다(sell 의 phantom 경로 몫).
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
        val investAmount = calculateInvestAmount((krw - reservedKrw).coerceAtLeast(0.0))
        if (investAmount < MIN_ORDER_AMOUNT_KRW) {
            log.debug("Insufficient funds for {}: investAmount={}", ticker, investAmount)
            return null
        }
        // 손절 시점에도 최소주문 이상이어야 팔 수 있다 — 아니면 봇이 처음부터 손절 못 할 포지션을 연다(#234).
        // 손절폭은 이 진입이 스냅샷으로 가져갈 값이다.
        if (investAmount * (1 - tradingProperties.exitParamsSnapshot().maxLossPct / 100) < MIN_ORDER_AMOUNT_KRW) {
            log.debug("Skip buy for {}: investAmount={} would be unsellable at the stop-loss", ticker, investAmount)
            return null
        }
        // 장부 밖 잔고(수동 매수·dust)도 주문 전 보유량에 넣는다 — 체결 판정이 이 주문으로 늘어난 증분만 보게 된다.
        val priorVolume = coin?.let { heldVolume(it, ourSellLockCeiling(state)) } ?: 0.0
        if (onDust && !isBelowMinOrder(priorVolume, currentPrice)) {
            // 기록이 낡았다(그 사이 앱에서 더 샀거나 락이 풀렸다) — 실제로는 팔 수 있는 보유라 그 위에 사면 이중 포지션이다.
            // 실측으로 고쳐 다음 tick 부터 보유로 관리되게 한다.
            log.info("Skip buy for {}: holding is sellable on the exchange (volume={}) — resyncing", ticker, priorVolume)
            state.holdVolume = priorVolume
            coin?.let { state.avgBuyPrice = it.avgBuyPriceDouble() }
            return null
        }
        return placeBuy(ticker, state, currentPrice, investAmount, strategyName, triggerPrice = null, priorVolume = priorVolume, freshEntry = onDust)
    }

    /**
     * 적립 단 매수. 예산 상한은 장부(rung)가 아니라 **주문 직전 거래소 실측 원가**로 판정한다 — 런타임 수동 매매로
     * 장부가 낡아도 상한이 뚫리지 않는다. 건너뛴 사유는 상태에 남겨 API 로 드러낸다.
     */
    suspend fun buyRung(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        action: LadderAction.Buy,
        params: LadderParams,
    ): TradeRecord? {
        if (entryBlocked(ticker, state, allowExisting = true)) return null
        val accounts = try {
            upbitClient.getAccounts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to fetch balance for rung buy {}: {}", ticker, e.message, e)
            return null
        }
        val krw = accounts.find { it.currency == "KRW" }?.balanceDouble() ?: 0.0
        val coin = accounts.find { it.currency == ticker.substringAfter("-") }
        val priorVolume = coin?.let { heldVolume(it, ourSellLockCeiling(state)) } ?: 0.0
        // 예산은 매도 가능 수량이 아니라 계좌 총보유(locked 포함)로 잰다 — 수동 지정가·출금 대기로 잠긴 코인도
        // 이 예산으로 산 돈이고, 빼고 재면 손절 없는 프로파일의 유일한 상한이 뚫린다.
        val investedKrw = (coin?.avgBuyPriceDouble() ?: 0.0) * (coin?.totalBalance() ?: 0.0)
        val skip = when {
            investedKrw + action.amountKrw > params.budgetKrw + BUDGET_TOLERANCE_KRW ->
                "budget: invested %.0f + rung %.0f > %.0f".format(investedKrw, action.amountKrw, params.budgetKrw)
            krw < action.amountKrw -> "KRW balance %.0f < rung %.0f".format(krw, action.amountKrw)
            else -> null
        }
        if (skip != null) {
            // 같은 사유가 tick 마다 반복되므로 바뀔 때만 알린다.
            if (state.accumulateSkipReason != skip) log.warn("Accumulate rung skipped for {}: {}", ticker, skip)
            state.accumulateSkipReason = skip
            return null
        }
        state.accumulateSkipReason = null
        return placeBuy(ticker, state, currentPrice, action.amountKrw, AccumulateLadder.STRATEGY_NAME, action.triggerPrice, priorVolume)
    }

    /**
     * 주문 발행 이후의 공용 경로 — identifier 선기록 → 주문 → uuid 기록 → 체결 확인 → 확정. 가드·금액 결정은 호출부 몫.
     *
     * 응답을 못 받은 주문은 나갔을 수도 있다. 그 예외를 "미전송"으로 보면 다음 tick 에 한 번 더 사서 2배 포지션이 되고
     * 첫 매수는 기록되지 않는다(#227). 그래서 identifier 를 주문 전에 durable 로 남기고, 접수 여부를 확정하지 못하면
     * pending 을 유지해 [reconcilePendingBuy] 가 identifier 로 확정한다.
     */
    private suspend fun placeBuy(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        investAmount: Double,
        strategyName: String,
        triggerPrice: Double?,
        priorVolume: Double,
        freshEntry: Boolean = false,
    ): TradeRecord? {
        // 아래 블록은 취소를 받지 않는다 — 정지(stop/reload)가 시작된 뒤에는 새 주문을 시작하지 않는다.
        currentCoroutineContext().ensureActive()
        val identifier = newOrderIdentifier()
        // 신규 진입이면 여기가 이 포지션의 시작점 — 옛 진입 메타를 지운 상태로 pending 을 기록해야, 체결 확인 전에
        // 재시작해도(syncPosition 이 position=true 를 먼저 세운다) 복원된 잔재가 상속되지 않는다.
        // 적립 추가 단은 기존 포지션 위에 얹는다 — 지우면 미체결(cancel+0)로 끝났을 때 buyDate·entryStrategy 가 영구 유실돼
        // 프로파일을 끈 뒤 보유상한 청산이 동작하지 않는다.
        // dust 흡수(freshEntry)도 새 진입이다 — 장부(position·수량)는 그대로 두고 메타만 지운다. 체결 확정은 실잔고로
        // 수량·평단을 덮고(replace) 빈 메타를 오늘 날짜·이번 전략으로 채운다. 주문이 무산돼도 dust 는 계속 관리된다.
        if (!state.position || freshEntry) state.clearEntryMeta()
        state.beginBuyOrder(identifier, strategyName, triggerPrice, priorVolume)
        // 선기록부터 체결 반영까지 취소가 끊지 못하게 한다. reload/stop 이 tick 을 취소하면 cancelAndJoin 이 이 블록의 완주를
        // 기다린다. 그 대기를 넘겨 프로세스가 죽어도 선기록된 identifier 로 재시작 뒤 확정된다.
        return withContext(NonCancellable) {
            try {
                upsertState(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // durable 흔적 없는 주문은 크래시 뒤 아무도 확정하지 못한다 — 보내지 않고 다음 신호를 기다린다.
                state.clearPendingBuy()
                log.warn("Buy for {} not sent — could not record order {} first: {}", ticker, identifier, e.message)
                return@withContext null
            }
            val order = try {
                // Upbit market buy: ord_type=price, price=총 투자금액
                upbitClient.placeOrder(
                    OrderRequest(
                        market = ticker,
                        side = "bid",
                        ordType = "price",
                        price = floor(investAmount).toLong().toString(),
                        identifier = identifier,
                    )
                )
            } catch (e: CancellationException) {
                throw e // 전송 여부 불명 — identifier 가 남아 reconcile 이 확정한다. 취소는 ERROR 로 남기지 않는다.
            } catch (e: Exception) {
                if (e.provesOrderNotPlaced()) {
                    log.error("Failed to place buy order {}: {}", ticker, e.message, e)
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
            // H8: uuid 를 pending 으로 보존. 이후 체결 확인이 예외로 실패해도 다음 tick reconcilePendingBuy 가 이어받아
            // position 복구/미체결 확정 → 중복매수(2배 포지션) 방지. 이 기록이 실패해도 선기록된 identifier 로 확정된다.
            state.adoptBuyOrder(order.uuid)
            persistPending(state)
            try {
                val filled = upbitClient.awaitFill(order.uuid)
                applyFillOutcome(ticker, state, currentPrice, filled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Buy post-order processing failed for {} (pending kept for reconcile): {}", ticker, e.message, e)
                null // pending 유지 → 다음 tick reconcile
            }
        }
    }

    /**
     * H8: 미해소 매수 주문(pendingBuyUuid)을 거래소 상태로 확정한다. processTicker 가 매 tick 호출.
     * getOrder 장애 시 getAccounts 실잔고로 복원(무방비보유 방지). 미해소면 pending 유지(다음 tick 재시도).
     * uuid 없이 identifier 만 있으면(응답을 못 받은 주문) [resolveBuyByIdentifier] 가 확정한다.
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
            return when (val recovery = recoverFromBalance(ticker, state, currentPrice)) {
                is BalanceRecovery.Filled -> recovery.record
                // 잔고조회는 성공했고 "체결 안 됨" 을 확인한 정상 판정 — 장애가 아니므로 halt 카운터에 넣지 않는다.
                // (여기서 세면 미체결 주문 하나로 멀쩡한 ticker 가 매수 정지된다.) halt 조건은 "getOrder·잔고조회
                // 둘 다 실패" 이므로 잔고조회가 살아난 이 시점에 연속 카운트도 끊는다.
                BalanceRecovery.NoBalance -> {
                    state.reconcileFailureCount = 0
                    persist(state) // 리셋을 durable 에도 내려야 재시작 후 옛 카운터가 부활해 허위 halt 되지 않는다
                    null
                }
                // getOrder·잔고조회가 둘 다 실패 = 상태를 볼 수단이 없음. #19 무한 재시도를 막는 실패 카운터.
                BalanceRecovery.LookupFailed -> {
                    recordReconcileFailure(state)
                    persist(state)
                    null
                }
            }
        }
        // getOrder 응답을 받았으면(wait/done/cancel 판정 가능) 진전이므로 실패 카운터 해소.
        if (state.reconcileFailureCount != 0) {
            state.reconcileFailureCount = 0
            persist(state)
        }
        return applyFillOutcome(ticker, state, currentPrice, filled)
    }

    private sealed interface IdentifierLookup {
        data class Found(val order: Order) : IdentifierLookup

        /** 거래소가 그 identifier 를 모른다. */
        data object NotFound : IdentifierLookup

        /** 조회 자체가 실패했다 — 있는지 없는지 모른다. */
        data object Unknown : IdentifierLookup
    }

    private suspend fun lookupByIdentifier(ticker: String, identifier: String): IdentifierLookup =
        try {
            val order = upbitClient.getOrderByIdentifier(identifier)
            when {
                order == null -> IdentifierLookup.NotFound
                order.uuid.isBlank() -> {
                    log.warn("identifier lookup for {} order {} returned no uuid", ticker, identifier)
                    IdentifierLookup.Unknown
                }
                else -> IdentifierLookup.Found(order)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("identifier lookup failed for {} order {}: {}", ticker, identifier, e.message)
            IdentifierLookup.Unknown
        }

    /**
     * 응답을 못 받은 매수(identifier 만 있음)를 확정한다. 거래소가 알면 uuid 를 이어받아 기존 경로로 넘긴다.
     * 잔고만으로는 체결을 기록하지 않는다 — uuid 없이는 이 주문의 체결인지 같은 시각의 수동 매매인지 가를 수 없고,
     * 감사 기록의 중복 방지 키도 없다.
     */
    private suspend fun resolveBuyByIdentifier(ticker: String, state: TradingState, currentPrice: Double, identifier: String): TradeRecord? {
        when (val lookup = lookupByIdentifier(ticker, identifier)) {
            is IdentifierLookup.Found -> {
                identifierMisses.remove(identifier)
                state.adoptBuyOrder(lookup.order.uuid)
                state.reconcileFailureCount = 0
                persist(state)
                return applyFillOutcome(ticker, state, currentPrice, lookup.order)
            }
            IdentifierLookup.NotFound -> {
                val trace = buyTrace(ticker, state)
                // 잔고도 못 봤다 — getOrder·잔고조회 동시 장애와 같은 halt 카운터로 사람을 부른다.
                if (trace == null) recordReconcileFailure(state)
                // 카운터는 확정(찾음·미접수)에서만 되돌린다. 404 마다 되돌리면 간헐 장애가 해제 조건과 halt 를 둘 다
                // 계속 끊어, 매매가 멈춘 채 아무 알림도 나가지 않는다.
                if (confirmNotPlaced(ticker, "Buy", identifier, trace)) {
                    state.clearPendingBuy()
                    state.reconcileFailureCount = 0
                }
            }
            IdentifierLookup.Unknown -> {
                breakMissStreak(identifier)
                recordReconcileFailure(state)
            }
        }
        persist(state)
        return null
    }

    /** 주문 전보다 보유가 늘었나 — 시장가 매수가 접수됐다면 곧바로 체결돼 코인이 들어온다. null = 판단할 수 없다. */
    private suspend fun buyTrace(ticker: String, state: TradingState): Boolean? {
        val prior = state.pendingBuyPriorVolume ?: return null
        return try {
            val held = findAccount(ticker.substringAfter("-"))?.let { heldVolume(it, ourSellLockCeiling(state)) } ?: 0.0
            held - prior > VOLUME_EPSILON
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("balance check for {} unknown buy failed: {}", ticker, e.message)
            null
        }
    }

    /** 주문 전보다 free 가 줄었나 — 매도가 접수됐다면 코인이 팔렸거나 주문에 잠겼다. null = 판단할 수 없다. */
    private suspend fun sellTrace(ticker: String, state: TradingState): Boolean? {
        val prior = state.pendingSellPriorVolume ?: return null
        return try {
            val free = findAccount(ticker.substringAfter("-"))?.balanceDouble() ?: 0.0
            prior - free > VOLUME_EPSILON
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("balance check for {} unknown sell failed: {}", ticker, e.message)
            null
        }
    }

    /**
     * 거래소가 모르는 주문을 미접수로 확정해도 되는가. 흔적 없는 404 가 끊김 없이 [NOT_PLACED_MISSES] 회 이상 이어지고
     * 그 첫 404 뒤 [NOT_PLACED_AFTER] 가 지났을 때만 true — 조회 실패·잔고 불명이 끼면 처음부터 다시 센다.
     *
     * 증명이 아니다 — 시장가 주문은 접수되면 곧바로 체결돼 잔고가 움직인다는 전제에 기대고, 조회 가시성 지연은 문서에 없다.
     * 그래서 흔적이 한 번이라도 보인 주문은 끝까지 자동으로 판단하지 않고 ERROR 로 사람을 부른다(pending 유지, 조회는
     * 계속 — 찾히면 정상 확정). 흔적이 나중에 사라져도 이 엔진 안에서는 풀지 않는다: 그 사이의 수동 매매를 이 주문과
     * 구분할 수 없다([IdentifierMiss] 의 범위 참조).
     *
     * @param trace 주문 전 기준값 대비 잔고 흔적. null = 잔고를 못 봤다.
     */
    private fun confirmNotPlaced(ticker: String, side: String, identifier: String, trace: Boolean?): Boolean {
        val prev = identifierMisses[identifier] ?: IdentifierMiss()
        if (trace == true && !prev.traced) {
            log.error(
                "{} 주문 {}({}) 을 거래소가 찾지 못하는데 잔고가 주문 전과 다릅니다 — 자동 처리하지 않습니다(이 티커의 매매가 멈춥니다). " +
                    "봇을 정지하고 Upbit 주문 내역을 확인한 뒤 trading_states 의 pending 을 정리하고 재기동하세요 — " +
                    "봇이 도는 중에 DB 를 고치면 다음 tick 이 메모리 상태로 다시 덮어씁니다.",
                side, identifier, ticker,
            )
        }
        if (trace != false || prev.traced) {
            identifierMisses[identifier] = IdentifierMiss(traced = prev.traced || trace == true)
            return false
        }
        val now = clock.instant()
        val next = prev.copy(misses = prev.misses + 1, firstMissAt = prev.firstMissAt ?: now)
        val waited = Duration.between(next.firstMissAt, now)
        if (next.misses >= NOT_PLACED_MISSES && waited >= NOT_PLACED_AFTER) {
            identifierMisses.remove(identifier)
            log.warn(
                "{} order {} for {} was never placed (not found {} times over {}s, no balance change) — released",
                side, identifier, ticker, next.misses, waited.seconds,
            )
            return true
        }
        identifierMisses[identifier] = next
        return false
    }

    /** 조회 자체가 실패했다 — 못 찾음이 끊김 없이 이어졌다고 말할 수 없다. 흔적 기록은 남긴다. */
    private fun breakMissStreak(identifier: String) {
        identifierMisses.computeIfPresent(identifier) { _, miss -> IdentifierMiss(traced = miss.traced) }
    }

    /**
     * 체결 판정 후 상태 반영 (buy 후처리·reconcile 공용). C1 과 동일하게 executedVolume>0 을 state 보다 우선 판정.
     * 전제: Upbit 시장가 매수(ord_type=price)는 즉시 체결 후 소액잔량을 환불하며 종료(done/cancel)되어 wait 로
     * 장기 잔존하지 않는다. 지정가(limit) 매수 도입 시 wait+부분체결의 잔여주문 취소 확인 로직이 필요하다.
     */
    private suspend fun applyFillOutcome(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        filled: Order?,
    ): TradeRecord? {
        val executed = filled?.executedVolume?.toDoubleOrNull() ?: 0.0
        return when {
            // filled != null 은 executed > 0.0 이 이미 함의하지만, 명시하면 smart-cast 가 걸려
            // 아래에서 도달 불가 분기 없이 filled 를 그대로 쓸 수 있다.
            filled != null && executed > 0.0 -> {
                // 부분체결(cancel/wait) 포함 — 실제 코인을 받았으므로 매수 확정. 실수량/평단은 실잔고로 재확인.
                val account = findAccount(ticker.substringAfter("-"))
                // wait(폴링 소진)로 여기 왔으면 funds 는 아직 진행 중인 값이고, completeBuy 가 pending 을 해소해
                // 뒤에 갱신할 길이 없다 — terminal 응답의 합만 이 주문의 금액으로 믿는다(#146).
                completeBuy(ticker, state, currentPrice, executed, account, filled.feeBasis(), terminalFunds(filled))
            }
            filled?.state == "wait" -> null // 아직 진행중 — pending 유지, 다음 tick 재시도
            else -> {
                // cancel+0 등 미체결 — 주문 무산, pending 해소
                log.warn("Pending buy unfilled for {}: state={} — order abandoned", ticker, filled?.state)
                state.clearPendingBuy()
                persist(state)
                null
            }
        }
    }

    /**
     * getOrder 장애 시 거래소 실잔고로 체결 여부 추정 복원. 주문 전 보유량(`pendingBuyPriorVolume`)을 넘는 증분이 있으면
     * 그만큼을 이 주문의 체결로 확정, 없으면 pending 유지(다음 tick). 스윙·적립 모두 주문 직전 보유량(장부 밖 dust·수동 매수
     * 포함)을 기록하므로 이 주문으로 늘어난 증분만 센다. 같은 시각의 수동 매수 혼입 보정은 범위 밖(M3·수동매매 동기화 별도).
     */
    private sealed interface BalanceRecovery {
        data class Filled(val record: TradeRecord) : BalanceRecovery

        /** 잔고조회 성공 + 잔고 0 — 주문이 아직 체결 안 된 정상 상태. */
        data object NoBalance : BalanceRecovery

        /** 잔고조회 자체가 실패 — 체결 여부를 판단할 수단이 없는 장애 상태. */
        data object LookupFailed : BalanceRecovery
    }

    private suspend fun recoverFromBalance(ticker: String, state: TradingState, currentPrice: Double): BalanceRecovery {
        val account = try {
            findAccount(ticker.substringAfter("-"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("reconcile balance recovery failed for {} ({}) — pending kept", ticker, e.message)
            return BalanceRecovery.LookupFailed
        }
        val balance = account?.balanceDouble() ?: 0.0
        // 적립 추가 단은 주문 전부터 코인이 있다 — 주문 시점 보유량을 넘는 증분만 이 주문의 체결로 본다.
        // 그 값이 없는 옛 pending 은 종전대로 잔고 전체(스윙은 position=false 였으므로 0 과 같다).
        val prior = state.pendingBuyPriorVolume ?: 0.0
        val executed = balance - prior
        if (executed <= VOLUME_EPSILON) {
            log.warn("reconcile pending kept for {}: order unknown and no balance", ticker)
            return BalanceRecovery.NoBalance
        }
        // 주문 응답이 없어 실제 수수료를 알 수 없다. 잔고 전제는 *수량 귀속*의 근거이지
        // 수수료 복원의 근거가 아니므로, 틀린 추정 대신 미기록으로 남긴다(#133).
        // 커밋 실패는 기본 경로처럼 호출부로 올라간다 — 조회 장애가 아니라 halt 카운터에 세지 않는다.
        return BalanceRecovery.Filled(
            // 주문 응답이 없다 — 수수료도 이 주문의 금액도 실측 불가.
            completeBuy(ticker, state, currentPrice, executed, account, FeeBasis.Unrecorded, orderAmount = null)
        )
    }

    /**
     * 실잔고/평단으로 markBought + TradeRecord. account==null/잔고0 이면 executedVolume·currentPrice fallback.
     *
     * @param feeBasis 수수료 출처. 이 함수가 만드는 `totalAmount` 는 **포지션 전체 원가**라 추정 기준으로
     *   쓸 수 없으므로(#133) 호출자가 정한다 — 주문 응답이 있으면 실측, 없으면 미기록.
     */
    private suspend fun completeBuy(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        executedVolume: Double,
        account: Account?,
        feeBasis: FeeBasis,
        orderAmount: Double?,
    ): TradeRecord {
        // pending 은 buy 에서 항상 strategy 와 함께 set 되므로 정상흐름상 non-null. null 은 그대로 두어
        // entryStrategy=null → resolveExitStrategy 가 조용히 fallback(빈 문자열 "" 은 WARN 스팸 유발).
        val strategy = state.pendingBuyStrategy
        val orderUuid = state.pendingBuyUuid // markBought 가 clear 하기 전에 캡처 — 멱등 dedup 키.
        // 적립 단이면 트리거가가 있다. 체결이 조금이라도 있으면 한 단으로 센다 — 시장가 매수(ord_type=price)는 잔량 환불로
        // 종결되므로 미달 체결은 드물고, "미달이면 안 센다"는 규칙은 다음 tick 의 장부 정합(원가 기반 rung 추정)과 모순된다.
        // 총 투입은 어차피 실측 원가 예산 게이트가 막는다.
        val triggerPrice = state.pendingBuyTriggerPrice
        val rungFilled = triggerPrice != null
        // 매수 직후라 우리 매도 주문은 없다 → 상한 0 = free 만 센다(holdVolume 정의를 매수 경로도 공유).
        // 계좌를 못 읽었으면 체결분에 주문 전 보유량을 더한다 — 추가 단에서 체결분만 쓰면 replace=true 가 기존 보유를 지운다.
        val volume = account?.let { heldVolume(it, 0.0) }?.takeIf { it > 0.0 }
            ?: (executedVolume + (state.pendingBuyPriorVolume ?: 0.0))
        // 평단도 계좌를 못 읽었으면 가중평균 — 추가 단에서 현재가로 replace 하면 포지션 전체 평단이 이번 단 가격이 된다.
        val priorVolume = state.pendingBuyPriorVolume ?: 0.0
        val fillPrice = account?.avgBuyPriceDouble()?.takeIf { it > 0.0 }
            ?: if (priorVolume > 0.0 && state.avgBuyPrice > 0.0 && volume > 0.0) {
                (state.avgBuyPrice * priorVolume + currentPrice * executedVolume) / volume
            } else {
                currentPrice
            }
        val totalAmount = fillPrice * volume
        val record = TradeRecord(
            userId = userId,
            ticker = ticker,
            side = TradeSide.BUY,
            price = fillPrice,
            volume = volume,
            totalAmount = totalAmount,
            pnlPercent = null, // 진입 — 실현 손익 없음
            pnlAmount = null,
            strategy = strategy,
            // totalAmount 가 포지션 전체 원가라 추정 기준으로 쓸 수 없다(#133). 호출자가 실측/미기록을 정한다.
            fee = feeBasis,
            // 이번 주문에 실제로 오간 돈은 totalAmount(스냅샷)가 아니라 이것이다(#146).
            orderAmount = orderAmount,
            exchangeOrderId = orderUuid,
        )
        // #52: 전이를 한 곳에 모아 사본과 원본에 각각 적용한다. `now` 를 고정해 두 적용이 동일한 결과를 낸다.
        val now = LocalDateTime.now(TradingDay.KST)
        val snapshot = tradingProperties.exitParamsSnapshot()
        val applyTransition: (TradingState) -> Unit = { s ->
            // 체결 확정 = reconcile 진전이므로 실패 카운터 해소.
            s.reconcileFailureCount = 0
            // replace=true: 거래소 실잔고를 절대값으로 반영해 syncPosition 복원분과 이중계상되지 않게(#20). markBought 가 pendingBuy* 를 모두 비운다.
            s.markBought(fillPrice, volume, strategy, replace = true, now = now)
            // 진입 시점 청산 파라미터 스냅샷. markBought 뒤에 찍는다 — 신규 진입이면 markBought 가 옛 스냅샷을 비우므로
            // 여기서 현재 설정으로 새로 찍히고, 재시작 복원(기존 포지션 연장)이면 durable 값이 그대로 유지된다.
            s.exitParams = s.exitParams ?: snapshot
            // 사다리 장부는 이 커밋 안에서만 바뀐다 — 밖에서 올리면 크래시 창에서 같은 단을 다시 산다.
            if (rungFilled) {
                s.rungsFilled += 1
                s.lastActionPrice = triggerPrice!!
            }
        }
        // 전이가 반영된 사본을 감사 기록과 한 트랜잭션으로 커밋한 뒤 원본 메모리 전이를 적용한다(#52).
        commitFillAndApply(state, record, applyTransition)
        log.info("BUY {} filled: volume={}, avgPrice={}, amount={}", ticker, volume, fillPrice, totalAmount)
        return record
    }

    /** DB 커밋 성공 후 메모리 전이를 적용하고, 멱등 skip이 아니면 알림을 best-effort로 보낸다. */
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
        // 이 커밋도 peakPrice 를 포함한 스냅샷을 저장한다 — 원본 dirty 를 남기면 매수 후에는
        // 불필요한 재시도가, 매도 후에는 position=false 라 재시도 경로조차 못 타 영영 남는다(#54).
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
            // log.error → DiscordErrorLogAppender 로 자동 alert.
            log.error(
                "[HALT] {} pending reconcile failed {} times — auto-trading stopped, manual clear required",
                state.ticker, state.reconcileFailureCount,
            )
        }
    }

    /** 메타(peakPrice/boughtToday/entryStrategy/halt 등) durable 반영. best-effort — 실패 시 다음 전이에서 재기록. */
    /**
     * 모든 durable 쓰기의 단일 통로. 저장되는 스냅샷에 `peakPrice` 가 포함되므로, 어느 경로로
     * 저장했든 peak dirty 는 함께 해소된다 — 개별 호출자마다 해제하면 빠뜨린 경로에서 불필요한
     * 재시도가 매 tick 돈다(#54).
     */
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

    /** pending durable 기록 — 실패 시 pendingPersistFailed 게이트로 신규 진입을 차단(#20 크래시 윈도우 방어). */
    private suspend fun persistPending(state: TradingState) {
        try {
            upsertState(state)
            state.pendingPersistFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.pendingPersistFailed = true
            log.error("pending persist failed for {} — blocking new entries (retry next tick): {}", state.ticker, e.message, e)
        }
    }

    /** 메타 변경 후 durable 반영(best-effort) — 실패해도 다음 전이에서 재기록된다. */
    internal suspend fun persistState(state: TradingState) = persist(state)

    /** 스윙 보유가 팔 수 없는 크기라 매도하지 않는다고 포지션당 한 번 알린다(ERROR 가 아니다 — 사람이 할 일이 없다). */
    private fun warnDustOnce(ticker: String, state: TradingState, detail: String) {
        if (state.dustWarned) return
        log.warn(
            "Swing holding on {} is dust below the {} KRW minimum order ({}) — not selling it",
            ticker, MIN_ORDER_AMOUNT_KRW.toLong(), detail,
        )
        state.dustWarned = true
    }

    /**
     * 더는 새로 살 수 없는 티커(목록 밖 잔류, #226)의 dust 를 장부에서 내린다 — 흡수될 길도 팔 길도 없어 잔류가 영구화된다(#234).
     * 기록이 아니라 **실잔고**로 판정한다: 팔 수 있는 보유를 내리면 기록 없이 관리 밖으로 떨어진다. 판 것이 아니므로 거래
     * 기록은 남기지 않고(코인은 계좌에 남는다), 진입 메타까지 지워 재시작 때 잔류로 다시 실리지 않게 한다.
     */
    suspend fun releaseDust(ticker: String, state: TradingState, currentPrice: Double) {
        // 같은 tick 에 낸 매도가 결과 불명으로 남았으면 내리지 않는다 — markSold 가 그 매도의 확정 근거(pending)를 지운다.
        if (state.hasPendingSell()) return
        val account = try {
            findAccount(ticker.substringAfter("-"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to read balance before releasing dust on {}: {}", ticker, e.message)
            return
        }
        // 사용자 지정가 등으로 잠긴 코인이 있으면 풀려서 돌아올 수 있다 — 여기서 내리면 목록 밖이라 다시 편입할 길이 없다.
        if (account != null && account.lockedDouble() > 0.0) return
        val held = account?.let { heldVolume(it, 0.0) } ?: 0.0
        if (!isBelowMinOrder(held, currentPrice)) {
            state.holdVolume = held // 기록이 낡았다 — 팔 수 있는 보유로 계속 관리한다
            return
        }
        log.warn("Releasing unsellable dust on {} (volume={}) — the ticker is outside the list, so nothing can absorb it", ticker, held)
        state.markSold()
        persist(state)
    }

    /** durable 복원본. 런타임에 새로 활성화되는 티커가 빈 상태로 시딩돼 pending uuid·halt 를 덮어쓰지 않게 한다. */
    internal suspend fun loadState(ticker: String): TradingState? = tradingStateService.loadState(userId, ticker)

    /** 잔고(free+locked)가 있는 코인 통화 — 재시작 시 메타 없는 durable 행을 살릴지 계좌 조회 1회로 판정한다. */
    internal suspend fun heldCurrencies(): Set<String> =
        upbitClient.getAccounts().filter { it.currency != "KRW" && it.totalBalance() > 0.0 }.map { it.currency }.toSet()

    /**
     * 신고점 durable 반영. 실패를 [TradingState.peakPersistFailed] 로 남겨 다음 tick 이 재시도하게 한다 —
     * flush 가 갱신 tick 에만 걸리므로, 실패를 흘리면 하락 전환 후에는 재기록 기회가 없다(#54).
     * 진입 게이트는 건드리지 않는다.
     */
    internal suspend fun persistPeak(state: TradingState) {
        try {
            upsertState(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.peakPersistFailed = true
            log.warn("peak persist failed for {} — retry next tick: {}", state.ticker, e.message)
        }
    }

    /** 실패를 호출자에게 알려야 하는 durable 반영(halt 해제 등 사용자 응답이 걸린 경로). */
    internal suspend fun persistStateOrThrow(state: TradingState) = upsertState(state)

    /** pending 기록 실패로 막힌 진입을 푸는 유일한 경로 — 성공해야 pendingPersistFailed 가 해제된다. */
    internal suspend fun retryPendingPersistIfNeeded(state: TradingState) {
        if (state.pendingPersistFailed) persistPending(state)
    }

    /**
     * 매도판 H8: 미해소 매도 주문(pendingSellUuid, 없으면 identifier — [resolveSellByIdentifier])을 거래소 상태로 확정.
     * processTicker 가 매 tick 호출.
     * getOrder 장애 시 실잔고로 체결 추정(잔고 0 = 청산됨). 미해소면 pending 유지(다음 tick 재시도).
     * 주문 결과를 받은 뒤 반영하지 못하면(체결 커밋·계좌 재조회 실패) 추정으로 넘기지 않고 pending 을 둔다.
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
            // 조회 실패는 각 단계가 먼저 받는다 — 여기 오는 것은 조회 뒤 기록 단계의 실패다(주문 결과 반영의 계좌 재조회·커밋,
            // 잔고복원의 커밋 — 커밋 원인은 commitFill 이 ERROR 로 남긴다). 주문 결과가 있는데 잔고 추정으로 넘기면 실측 체결을
            // 추정치로 영구 기록한다. 전이가 적용되지 않아 pending 이 남고 다음 tick 이 다시 확정한다.
            log.warn("reconcile sell for {} was resolved but could not be recorded ({}) — pending kept", ticker, e.message, e)
            null
        }
        // 미해소 pending sell 은 processTicker 에서 매도·매수 평가를 통째로 막는다 — 오래 끌면 보유 포지션이
        // 손절도 못 한 채 방치되므로, 매수판 halt 와 같은 상한에서 한 번 ERROR 로 올려 사람이 개입하게 한다.
        // (halt 는 신규 매수만 막는 플래그라 여기선 쓰지 않는다 — 필요한 건 차단이 아니라 알림.)
        state.pendingSellRef()?.let { warnIfSellStuckTooLong(ticker, state, it) }
        // 매도 pending 전이(clearPendingSell/markSold/부분갱신) durable 반영. wait(무전이)도 upsert 무해.
        persist(state)
        return result
    }

    /** uuid 로 매도를 확정한다. 잔고 추정은 getOrder 가 실패했을 때만이다 — 기록 단계의 실패는 호출부가 받는다. */
    private suspend fun confirmSellByUuid(ticker: String, state: TradingState, currentPrice: Double, uuid: String): TradeRecord? {
        val filled = try {
            upbitClient.getOrder(uuid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("reconcile sell getOrder failed for {} ({}): falling back to balance", ticker, e.message)
            return recoverSellFromBalance(ticker, state, currentPrice)
        }
        return applySellFillOutcome(ticker, state, currentPrice, filled)
    }

    /**
     * 응답을 못 받은 매도(identifier 만 있음)를 확정한다. 매수판([resolveBuyByIdentifier])과 같은 규칙이다 — 거래소가 알면
     * uuid 를 이어받고, 잔고만으로는 청산을 기록하지 않는다. 조회 실패는 pending 유지 — 경과시간 알림이 사람을 부른다.
     */
    private suspend fun resolveSellByIdentifier(ticker: String, state: TradingState, currentPrice: Double, identifier: String): TradeRecord? =
        when (val lookup = lookupByIdentifier(ticker, identifier)) {
            is IdentifierLookup.Found -> {
                identifierMisses.remove(identifier)
                state.adoptSellOrder(lookup.order.uuid)
                persist(state)
                applySellFillOutcome(ticker, state, currentPrice, lookup.order)
            }
            IdentifierLookup.NotFound -> {
                if (confirmNotPlaced(ticker, "Sell", identifier, sellTrace(ticker, state))) {
                    // 코인은 그대로다 — 포지션을 유지하고 다음 tick 에 다시 판다(무산 분기와 같은 복원).
                    if (!state.position) syncPosition(ticker, state)
                    state.clearPendingSell()
                }
                null
            }
            IdentifierLookup.Unknown -> {
                breakMissStreak(identifier)
                null
            }
        }

    /**
     * 막힌 매도를 사람에게 한 번 알린다. 카운터가 아니라 **경과시간**으로 판정하는 이유는 pending 이
     * durable 이기 때문이다 — 메모리 카운터는 배포·크래시마다 0 으로 돌아가 임계에 영영 못 닿았고,
     * 그 사이 processTicker 는 매도·매수 평가를 통째로 막아 보유 포지션이 손절 없이 방치됐다(#55).
     *
     * 임계는 기존 의미(`reconcileHaltThreshold` tick)를 시간으로 환산해 유지한다.
     * (halt 는 신규 매수만 막는 플래그라 여기선 쓰지 않는다 — 필요한 건 차단이 아니라 알림.)
     */
    private fun warnIfSellStuckTooLong(ticker: String, state: TradingState, orderRef: String) {
        if (state.pendingSellAlerted) return
        val since = state.pendingSellSince ?: run {
            // 이 마이그레이션 이전에 시작된 pending 은 시각이 없다 — 지금부터 센다.
            state.pendingSellSince = clock.instant()
            return
        }
        val threshold = Duration.ofSeconds(
            tradingProperties.reconcileHaltThreshold.toLong() * tradingProperties.intervalSeconds,
        )
        val stuckFor = Duration.between(since, clock.instant())
        if (stuckFor < threshold) return
        // 알린 **뒤에** 표시한다. 먼저 표시하면 그 사이 크래시·전송 실패 시 재알림이 막혀 알림이
        // 영구 유실된다 — 중복 알림이 유실보다 낫다. (전송 성공까지 보장하려면 outbox 가 필요하다.)
        log.error(
            "매도 reconcile 미해소 {}초 — {} 의 청산이 막혀 있습니다(주문 {}). 수동 확인 필요.",
            stuckFor.seconds, ticker, orderRef,
        )
        state.pendingSellAlerted = true
    }

    suspend fun sell(ticker: String, state: TradingState, currentPrice: Double, reason: SellReason): TradeRecord? =
        // 판단가를 pending 에 남긴다 — 체결이 reconcile 로 늦게 확정돼도 기록은 이 가격으로 한다(#235).
        placeSell(ticker, state, currentPrice, reason, triggerPrice = currentPrice) { account ->
            SellQuantity(account.balance, account.balanceDouble())
        }

    /**
     * 적립 단 매도. 마지막 단은 거래소 잔고 원문으로 전량, 아니면 8자리 내림 수량. 요청이 free 잔고를 넘어도
     * 전량으로 승격하지 않는다 — 나머지가 locked(수동 지정가·출금 대기)일 수 있어 전량 청산으로 확정하면 장부가 틀린다.
     */
    suspend fun sellVolume(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        action: LadderAction.Sell,
    ): TradeRecord? =
        placeSell(ticker, state, currentPrice, SellReason.ACCUMULATE_STEP, action.triggerPrice) { account ->
            val sellable = account.balanceDouble()
            if (action.isFinal) {
                SellQuantity(account.balance, sellable)
            } else {
                // 주문 문자열은 8자리로 절삭된다 — 장부·기록·최소주문 검사도 실제로 보낸 그 수량을 써야 맞는다.
                val orderVolume = formatVolume(minOf(action.volume, sellable))
                SellQuantity(orderVolume, orderVolume.toDouble())
            }
        }

    /** 주문에 실을 수량 문자열(거래소 원문 또는 plain decimal)과 그 수치. */
    private class SellQuantity(val orderVolume: String, val volume: Double)

    // Double.toString() 은 소액에서 지수표기("5.0E-5")를 내고 거래소가 거부한다. BigDecimal(double) 생성자는 이진
    // 근사값(0.0003 → 0.000299999…)을 그대로 써서 내림이 한 자리 깎이므로 valueOf(십진 표기)로 만든다.
    private fun formatVolume(volume: Double): String =
        BigDecimal.valueOf(volume).setScale(VOLUME_SCALE, RoundingMode.DOWN).stripTrailingZeros().toPlainString()

    private suspend fun placeSell(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        reason: SellReason,
        triggerPrice: Double,
        quantity: (Account) -> SellQuantity,
    ): TradeRecord? {
        if (!state.position) return null
        // 미해소 매도 주문이 있으면 신규 매도 금지 — reconcile 로 확정될 때까지 이중 매도 방지(매수 pending 가드 미러).
        if (state.hasPendingSell()) {
            log.debug("Skip sell for {}: pending sell {} awaiting reconcile", ticker, state.pendingSellRef())
            return null
        }

        // 잔고 조회·phantom 판정: 실패하면 주문 전이므로 pending 없이 종료(포지션 유지 → 다음 tick 재매도).
        // 매도 수량은 state.holdVolume(조작 가능)이 아니라 거래소 실잔고(sellable)를 사용.
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
            // M4(#122): 여기서는 미해소 매도가 없으므로(위 가드) locked 는 우리 매도 주문의 것이 아니다 — 출금 대기나
            // 사용자가 직접 낸 주문이다. [heldVolume] 의 상한 규칙과 같은 판정으로 보유를 내린다. 보류하면 syncPosition
            // ("정리는 sell() 몫")과 서로 미뤄 유령 포지션이 영구히 남고 매 tick 경고만 쌓인다.
            // 진입 메타는 남기고(releaseHoldings) unsynced 를 켜 다음 tick 의 재동기화에 넘긴다: 락이 풀려 코인이 free 로
            // 돌아오면 재편입돼 보유상한·트레일링이 이어지고, 여전히 불명이면 매수만 막힌다(경고 1회).
            // (durable pending 유실 후 재시작한 우리 주문은 position 이 복원되지 않아 여기 오지 않는다 — syncPosition 몫.)
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
            // 사다리는 여기서부터의 눌림을 기다린다 — 옛 고점이 남으면 수동 청산 직후 곧바로 첫 단이 들어간다.
            if (reason == SellReason.ACCUMULATE_STEP) state.flatPeak = currentPrice
            persist(state)
            return null
        }

        // Upbit market sell: ord_type=market. 전량이면 거래소 원본 문자열, 부분이면 plain decimal.
        val qty = quantity(account!!)
        // 실제 주문이 최소주문 아래면 거래소가 매 tick 거부한다 — 보내지 않는다.
        if (isBelowMinOrder(qty.volume, currentPrice)) {
            if (reason == SellReason.ACCUMULATE_STEP) {
                // 사다리 판정은 장부 수량으로 최소주문을 봤다 — free 로 축소된 실제 주문이 여기 걸린다. 매수 skip 과 같은
                // 창구로 드러낸다 — 조용히 멈추면 운영자가 사다리가 왜 안 파는지 모른다.
                val skip = "sell: %s × %.0f < min order".format(qty.orderVolume, currentPrice)
                if (state.accumulateSkipReason != skip) log.warn("Accumulate sell skipped for {}: {}", ticker, skip)
                state.accumulateSkipReason = skip
            } else {
                // 스윙 dust(#234) — 팔 수 없다. 실측 수량을 남겨 엔진이 dust 로 보고 진입을 열게 한다(사면 합쳐져 새 진입).
                // 청산 평가는 매 tick 계속 돌므로 락이 풀려 free 가 돌아오면 다음 청산 사유에서 정상으로 판다.
                state.holdVolume = heldVolume(account, ourSellLockCeiling(state))
                warnDustOnce(ticker, state, "%s × %.0f".format(qty.orderVolume, currentPrice))
            }
            return null
        }
        // 매수판([placeBuy])과 같은 순서 — 정지 중이면 시작하지 않고, identifier 를 먼저 남긴 뒤 보낸다(#227).
        currentCoroutineContext().ensureActive()
        val identifier = newOrderIdentifier()
        // 재시작 후에는 잔고·평단이 이미 비어 있으므로 청산 기록의 근거를 주문 시점 값으로 남긴다. 미해소가 얼마나 끌었는지는
        // 재시작 횟수와 무관해야 한다 — 시작 시각을 durable 로 남긴다(#55). 주문 전 free 보유량은 부분 체결 뒤 unlock
        // 지연으로 거래소 잔량이 과소일 때의 하한이자, 응답을 못 받은 주문의 흔적 판정 기준이다.
        state.beginSellOrder(identifier, reason, clock.instant(), qty.volume, triggerPrice, sellable)
        // 매수판과 동일 — 선기록부터 체결 반영까지 취소가 끊지 못하게 해야 청산 기록이 유실되지 않는다.
        return withContext(NonCancellable) {
            // 기록 장애가 손절을 막으면 안 된다 — 매수와 달리 실패해도 보낸다. 메모리 identifier 가 이 엔진의 이중
            // 매도를 막고, 크래시 창은 pendingPersistFailed(진입 차단·매 tick 재기록)가 좁힌다.
            persistPending(state)
            val order = try {
                upbitClient.placeOrder(
                    OrderRequest(
                        market = ticker,
                        side = "ask",
                        ordType = "market",
                        volume = qty.orderVolume,
                        identifier = identifier,
                    )
                )
            } catch (e: CancellationException) {
                throw e // 전송 여부 불명 — identifier 가 남아 reconcile 이 확정한다. 취소는 ERROR 로 남기지 않는다.
            } catch (e: Exception) {
                if (e.provesOrderNotPlaced()) {
                    if (e.isRejectedAsBelowMinimumOrder()) {
                        // tick 가격으론 가드를 넘었지만 거래소 판정 가격으론 최소주문 미만 — 거래소 판정이 기준이다(#234).
                        warnDustOnce(ticker, state, "rejected by the exchange as below the minimum order")
                    } else {
                        log.error("Failed to place sell order {}: {}", ticker, e.message, e)
                    }
                    state.clearPendingSell() // 코인은 그대로 — 포지션 유지, 다음 tick 재매도
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
            // 매도판 H8: uuid 보존. 이후 체결확인이 실패/미확정이어도 다음 tick reconcilePendingSell 이 이어받아
            // 청산 확정·기록 → 이중매도·감사유실 방지.
            state.adoptSellOrder(order.uuid)
            persistPending(state)
            try {
                val filled = upbitClient.awaitFill(order.uuid)
                if (filled?.state == "done") {
                    // 즉시 체결 — 주문량으로 기록. done 은 upbit 시장가 매도의 정상 종결.
                    // #52: 상태 전이 저장과 감사 기록을 원자 커밋하고, 성공 후에만 메모리 전이를 적용한다.
                    completeSellAtomically(
                        ticker, state, currentPrice, qty.volume, reason,
                        remaining = sellable - qty.volume,
                        // 판단가(currentPrice)와의 차이가 실행 슬리피지다 — 백테에 없는 항목이라 실물로만 얻는다.
                        executedVwap = filled.filledVwap(),
                        feeBasis = sellFeeBasis(filled),
                        orderAmount = terminalFunds(filled),
                    )
                } else {
                    // 미확정(wait/cancel) 또는 부분체결(cancel+executed>0) — pending 유지, 다음 tick reconcilePendingSell.
                    log.warn("Sell not confirmed for {}: state={} — pending kept for reconcile", ticker, filled?.state)
                    persist(state) // pending 유지 상태 durable 반영
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Sell post-order processing failed for {} (pending kept for reconcile): {}", ticker, e.message, e)
                null // pending 유지 → 다음 tick reconcile
            }
        }
    }

    /**
     * 매도 체결 판정 후 상태 반영 — 즉시경로(sell() 의 done) 밖의 모든 응답: reconcile 의 wait/cancel/done, identifier 로
     * 찾은 주문(대개 done).
     * wait 는 executedVolume>0(부분 진행중)이어도 terminal 이 아니다 — Upbit 는 미체결 잔량을 locked 로 묶어 free
     * balance=0 일 수 있고, 여기서 확정하면 아직 열린 주문을 markSold 로 오판해 잔여 체결분을 잃고 미정산 포지션에 새 거래를
     * 허용한다(codex P2). terminal(done/cancel)에서만 체결분을 확정한다.
     */
    private suspend fun applySellFillOutcome(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        filled: Order?,
    ): TradeRecord? {
        if (filled?.state == "wait") {
            // #120 관측: Upbit 가 wait 중 부분체결을 remaining_volume 에 반영하는지 공식 문서에 없다 — 운영 근거를 남긴다.
            val snapshot = "${filled.executedVolume}/${filled.remainingVolume}"
            if ((filled.executedVolume?.toDoubleOrNull() ?: 0.0) > 0.0 && state.partialSellFillLogged != snapshot) {
                log.info(
                    "Partial fill while waiting for {} order {}: executed={} remaining={} of {}",
                    ticker, filled.uuid, filled.executedVolume, filled.remainingVolume, filled.volume,
                )
                state.partialSellFillLogged = snapshot
            }
            return null // 진행중 — pending 유지, 다음 tick 재시도
        }
        val executed = filled?.executedVolume?.toDoubleOrNull() ?: 0.0
        return when {
            executed > 0.0 -> {
                // terminal(done 전량 또는 cancel 부분) + 체결분. 잔여는 우리 주문이 못 판 몫까지만 센다 —
                // 취소된 미체결 잔량은 free 로 돌아오지만 반영이 늦을 수 있고, 반대로 남은 locked 가 우리
                // 주문 것이 아닐 수도 있다(출금 대기 등). 둘 다 [heldVolume] 의 상한이 처리한다.
                // buildSellRecord 는 평단이 필요하므로 전이 전에 만든다. 잔고 조회도 트랜잭션 밖에서 끝낸다(#52).
                val record = buildSellRecord(
                    ticker, state, currentPrice, executed,
                    executedVwap = filled?.filledVwap(), feeBasis = sellFeeBasis(filled), orderAmount = terminalFunds(filled),
                )
                val account = findAccount(ticker.substringAfter("-"))
                val unfilled = (ourSellLockCeiling(state) - executed).coerceAtLeast(0.0)
                val exchangeRemaining = account?.let { heldVolume(it, unfilled) } ?: 0.0
                // 적립은 잔량이 곧 다음 단의 분모다. 취소된 미체결 잔량의 unlock 이 늦으면 거래소 기준이 과소(free 0.75 +
                // locked 0.15 → 0.75)라 원가 정합이 rung 을 조기에 줄인다 — 주문 전 보유 − 체결량을 하한으로 둔다(60초 주기
                // 동기화가 이후 실측으로 다시 맞춘다). 스윙은 종전 규칙 그대로.
                val priorVolume = state.pendingSellPriorVolume ?: state.holdVolume
                // 계좌 행이 없으면(null = 조회 성공 + 잔고 0 — 실패는 예외로 올라가 pending 이 남는다) 확인된 0 이라 하한을 쓰지 않는다.
                val remaining = if (state.pendingSellReason == SellReason.ACCUMULATE_STEP && account != null && priorVolume > 0.0) {
                    maxOf(exchangeRemaining, priorVolume - executed)
                } else {
                    exchangeRemaining
                }
                val recoveredAvg = account?.avgBuyPriceDouble() ?: 0.0
                val now = LocalDateTime.now(TradingDay.KST)
                commitFillAndApply(state, record, sellTransition(state, executed, remaining, recoveredAvg, now))
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
                // cancel+0 미체결 — 매도 무산, pending 해소. 코인 그대로이므로 position 유지(다음 tick 재매도).
                // 복원 직후라 position 이 false 로 남아 있을 수 있어 실잔고로 되살린다(위 부분체결 분기와 같은 이유).
                log.warn("Pending sell unfilled for {}: state={} — order abandoned, position kept", ticker, filled?.state)
                if (!state.position) syncPosition(ticker, state)
                state.clearPendingSell()
                null
            }
        }
    }

    /**
     * getOrder 장애 시 실잔고로 매도 체결 여부 추정 복원. free+locked 합(totalBalance)이 0 = 청산됨(markSold 이전
     * holdVolume 으로 기록), 남음 = 미체결/진행중(pending 유지, 다음 tick). free 만 보면 미체결 잔량이 locked 로 묶인
     * 진행중 주문을 청산으로 오판한다(codex P2). 전제: 1 ticker = 1 position, pending 생존 중 잔고 변화는 이 매도 결과.
     *
     * 여기만 [heldVolume] 의 상한 규칙 밖이다 — "다 나갔나"만 판정하므로 locked 를 넉넉히 세는 쪽이 보수적
     * (잔고가 남아 보이면 pending 을 유지해 사람이 확인하게 둔다). 상세는 #56 Deferred.
     */
    private suspend fun recoverSellFromBalance(ticker: String, state: TradingState, currentPrice: Double): TradeRecord? {
        val total = try {
            findAccount(ticker.substringAfter("-"))?.totalBalance() ?: 0.0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("reconcile sell balance recovery failed for {} ({}) — pending kept", ticker, e.message)
            return null
        }
        if (total > 0.0) {
            log.warn("reconcile sell pending kept for {}: order unknown and balance remains (total={})", ticker, total)
            return null
        }
        // 주문 시점 수량이 우선 — 재시작 후 holdVolume 은 이미 0 으로 동기화돼 있다.
        val volume = state.pendingSellVolume ?: state.holdVolume
        if (volume <= 0.0) {
            // 근거 없이 잔고 0 만으로 확정하면 수량 0 의 유령 SELL 이 감사에 남는다 — pending 을 유지해
            // getOrder 복구나 사람 개입으로 확정하게 둔다.
            log.error("reconcile sell for {}: zero balance but no recorded sell volume — pending kept for manual review", ticker)
            return null
        }
        // 주문 응답이 없어 실측할 수 없다 — 매도 대금 기준 추정이 이 경로의 최선이다.
        val record = buildSellRecord(ticker, state, currentPrice, volume, feeBasis = FeeBasis.Estimate, orderAmount = null)
        val now = LocalDateTime.now(TradingDay.KST)
        // #52: 잔고 기반 복원도 감사 기록과 원자 커밋 — 실패는 호출부가 받고 pending 이 남아 다음 tick 이 재시도한다.
        commitFillAndApply(state, record, sellTransition(state, volume, remaining = 0.0, recoveredAvg = 0.0, now = now))
        log.info("SELL {} recovered from zero balance (getOrder down): volume={}", ticker, volume)
        return record
    }

    /** 매도 전량 확정 — 기록 생성 후 markSold. sell() 즉시경로(done) 전용. */
    /**
     * 매도 체결 확정 — #52 원자 커밋판. `buildSellRecord` 는 평단(avgBuyPrice)이 필요하므로 반드시
     * `markSold` **이전**에 호출한다. 커밋이 실패하면 예외가 올라가 메모리 전이가 적용되지 않으므로
     * `pendingSellUuid` 가 살아남아 다음 tick `reconcilePendingSell` 이 재시도한다.
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
        commitFillAndApply(state, record, sellTransition(state, volume, remaining, state.avgBuyPrice, now))
        log.info(
            "SELL {} filled: price={}, volume={}, net pnl={}%, reason={}",
            ticker, record.price, volume, record.pnlPercent?.let { "%.2f".format(it) } ?: "-", record.reason,
        )
        return record
    }

    /**
     * 매도 TradeRecord 생성 — 기록용 pnl 은 왕복수수료 차감(net, 백테스트 feeRate×2 와 통일). 청산 게이트는 gross 유지.
     * 평단 미상(외부 입금분 syncPosition 복원 등)이면 pnl null — 0%−fee 의 가짜 손실(−0.1%) 기록 방지.
     * markSold 이전에 호출해야 avgBuyPrice·entryStrategy 가 살아있어 손익과 전략 귀속이 복원된다.
     * reason 미지정 시 state.pendingSellReason 사용.
     *
     * 가격은 이 주문을 결정한 tick 의 가격(`pendingSellTriggerPrice`)이다 — reconcile 이 몇 tick·재시작 뒤에 확정해도
     * 기록의 price·pnl 이 확정 tick 시세로 바뀌지 않는다. 그 값이 없는 옛 pending 만 [currentPrice] 로 떨어진다.
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
        // 재시작 복원 경로에서는 avgBuyPrice 가 이미 0 으로 동기화돼 있으므로 주문 시점 평단을 쓴다.
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
            // 청산은 진입 전략의 성과로 귀속한다. 매도 시점의 활성 전략을 쓰면 설정을 바꾼 뒤의 청산이
            // 엉뚱한 전략 몫으로 잡힌다. markSold 가 clearEntryMeta 로 지우기 전이라 값이 살아 있다.
            // 적립 단 매도는 편입된 스윙 포지션이어도 적립 몫이다 — 그 규칙으로 팔았다.
            strategy = if (reason == SellReason.ACCUMULATE_STEP) AccumulateLadder.STRATEGY_NAME else state.entryStrategy,
            fee = feeBasis,
            // totalAmount 는 판단 tick 평가액이고 이것이 실제 체결 대금이다(#146).
            orderAmount = orderAmount,
            reason = reason?.name,
            // markSold 이전 호출이라 pendingSellUuid 가 살아있음 — 재시작 reconcile 중복 기록을 막는 dedup 키.
            exchangeOrderId = state.pendingSellUuid,
        )
    }

    /**
     * 매도 수수료 출처 — 주문 응답에 `paid_fee` 가 있으면 매수와 같은 실측(#148). 없으면 추정으로 떨어뜨린다:
     * 매도의 totalAmount 는 이 체결의 대금(가격×수량)이라 추정 기준이 맞다 — 매수가 [FeeBasis.Unrecorded] 로 두는
     * 이유(포지션 전체 원가 스냅샷, #133)가 여기엔 없다. 주문 응답이 없는 잔고복원 경로는 호출하지 않는다.
     */
    private fun sellFeeBasis(filled: Order?): FeeBasis = filled?.sellFeeBasis() ?: FeeBasis.Estimate

    /** 이 주문의 체결 대금 — terminal(done/cancel) 응답의 Σfunds 만. 진행 중(wait) 합은 최종값이 아니라 버린다(#146). */
    private fun terminalFunds(filled: Order?): Double? =
        filled?.takeIf { it.isTerminal() }?.filledFunds()

    /**
     * 매도 확정 전이 — 즉시경로·reconcile(부분·전량)·잔고복원 네 곳이 모두 이 하나를 쓴다. 갈라지면 어느 한 경로에서
     * 사다리 장부가 안 줄어 같은 단을 반복 매도한다. 사유·요청수량·트리거가는 durable pending 에서 읽으므로
     * 재시작 뒤 reconcile 에서도 같은 판정이 나온다.
     */
    private fun sellTransition(
        state: TradingState,
        executed: Double,
        remaining: Double,
        recoveredAvg: Double,
        now: LocalDateTime,
    ): (TradingState) -> Unit {
        val isLadder = state.pendingSellReason == SellReason.ACCUMULATE_STEP
        val requested = state.pendingSellVolume ?: executed
        val triggerPrice = state.pendingSellTriggerPrice
        val rungConsumed = isLadder && executed >= RUNG_FILL_RATIO * requested
        return { s ->
            if (remaining > 0.0) {
                // 부분 체결 — 잔여 실잔고로 갱신, avgBuyPrice 유지. pending 해소(잔여분은 다음 tick 재평가).
                // position 을 실측으로 되살린다: 매도 주문에 잠긴 잔고 때문에 복원 시 false 였을 수 있고,
                // 그대로 두면 잔여 포지션이 청산 평가를 영영 못 받는다.
                s.position = true
                s.holdVolume = remaining
                if (s.avgBuyPrice <= 0.0) s.avgBuyPrice = recoveredAvg
                s.clearPendingSell()
                // 잔량이 있으면 사다리는 최소 1단이어야 한다 — 마지막 단이 90~99% 체결되면 rung 0·잔고>0 이 되어
                // decide 가 영구 Hold 에 빠진다(적립엔 다른 청산 게이트가 없다). 잔량은 다음 상승에 isFinal 로 팔린다.
                if (rungConsumed) s.rungsFilled = (s.rungsFilled - 1).coerceAtLeast(if (isLadder) 1 else 0)
            } else {
                s.markSold(now)
                // 전량 청산 후 첫 단은 여기서부터의 눌림을 기다린다.
                if (isLadder && triggerPrice != null) s.flatPeak = triggerPrice
            }
            if (rungConsumed && triggerPrice != null) s.lastActionPrice = triggerPrice
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
        // Update peak price
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
        // 잔액의 investRatio 비율만 투자하되 maxInvestAmount 로 상한.
        return minOf(krwBalance * tradingProperties.investRatio, tradingProperties.maxInvestAmount)
    }
}
