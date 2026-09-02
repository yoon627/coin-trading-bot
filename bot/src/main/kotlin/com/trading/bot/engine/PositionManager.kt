package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.ExitParamsSnapshot
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
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
        private const val MIN_ORDER_AMOUNT_KRW = 5000.0
        private const val FILL_POLL_ATTEMPTS = 10
        private const val FILL_POLL_DELAY_MS = 300L
        // 적립 단 매도는 요청 대비 이 비율 이상 체결됐을 때만 rung 을 소모한다 — 10% 체결로 한 단을 지우면 사다리가 어긋난다.
        private const val RUNG_FILL_RATIO = 0.9
        private const val BUDGET_TOLERANCE_KRW = 1.0
        private const val VOLUME_SCALE = 8
        private const val VOLUME_EPSILON = 1e-8
    }

    /** 거래소 계좌 목록에서 해당 통화 계좌 조회 (#21 — getAccounts().find 중복 헬퍼화). */
    private suspend fun findAccount(currency: String): Account? =
        upbitClient.getAccounts().find { it.currency == currency }

    /**
     * 보유 수량 = `free + min(locked, 우리 매도 주문이 아직 잠그고 있을 수 있는 최대 수량)`.
     *
     * Upbit 의 `locked` 는 "출금이나 주문 등에 잠겨 있는 잔액"이라 우리 주문 외 사유가 섞인다. 그대로 더하면
     * `sell()` 이 주문할 수 없는(free 만 판다) 수량을 보유로 세어 유령 포지션이 남고, 반대로 통째로 버리면
     * 우리 주문이 방금 풀렸지만 아직 free 로 안 돌아온 잔량을 잃는다. 상한을 두면 양쪽을 다 피하고,
     * 거래소가 locked→free 를 언제 반영하든 같은 답이 나온다.
     *
     * @param ourLockCeiling 우리 매도 주문이 잠그고 있을 수 있는 **최대** 수량. 이미 free 로 돌아온 몫은
     *   상한에서 빠진다 — 그래서 주문이 이미 풀렸으면 상한이 0 이 되어 타 사유 locked 가 새지 않는다.
     */
    private fun heldVolume(account: Account, ourLockCeiling: Double): Double {
        val free = account.balanceDouble()
        return free + minOf(account.lockedDouble(), (ourLockCeiling - free).coerceAtLeast(0.0))
    }

    /**
     * 우리 매도 주문이 잠그고 있을 수 있는 최대 수량 = 주문 수량. 미해소 주문이 없으면 0, 주문은 있는데
     * 수량 기록이 없으면(레거시 durable row) 상한을 걸지 않는다 — 그 경우 [heldVolume] 은 종전대로
     * free+locked 가 된다.
     *
     * **느슨한 상한이다.** `pendingSellVolume` 은 주문 시점 수량이고 부분체결로 줄지 않으므로, 주문이 `wait`
     * 로 남아 일부만 체결된 구간에서는 체결분만큼 여유가 생긴다. 체결분을 아는 호출부는 그만큼 빼서 넘긴다.
     */
    private fun ourSellLockCeiling(state: TradingState): Double =
        if (state.pendingSellUuid == null) 0.0 else state.pendingSellVolume ?: Double.POSITIVE_INFINITY

    suspend fun syncPosition(ticker: String, state: TradingState) {
        try {
            val account = findAccount(ticker.substringAfter("-"))
            if (account != null) {
                // free 만 보면 안 된다 — 매도 주문이 떠 있는 채로 재시작하면 코인 전량이 locked 라 free 가 0 이다.
                // 그걸 "보유 없음" 으로 동기화하면 손절·익절이 한 번도 평가되지 않는 무방비 보유가 되고,
                // boughtToday 가 풀리는 순간 그 위에 추가 매수까지 들어간다.
                val held = heldVolume(account, ourSellLockCeiling(state))
                if (held > 0.0) {
                    state.position = true
                    state.avgBuyPrice = account.avgBuyPriceDouble()
                    state.holdVolume = held
                    log.info("Synced existing position for {}: price={}, volume={}", ticker, state.avgBuyPrice, state.holdVolume)
                } else if (account.lockedDouble() > 0.0) {
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
        if (state.pendingBuyUuid != null) {
            log.debug("Skip buy for {}: pending order {} awaiting reconcile", ticker, state.pendingBuyUuid)
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
        if (entryBlocked(ticker, state, allowExisting = false)) return null
        val investAmount = try {
            calculateInvestAmount((getKrwBalance() - reservedKrw).coerceAtLeast(0.0))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to fetch balance for buy {}: {}", ticker, e.message, e)
            return null
        }
        if (investAmount < MIN_ORDER_AMOUNT_KRW) {
            log.debug("Insufficient funds for {}: investAmount={}", ticker, investAmount)
            return null
        }
        // 스윙은 position=false 가드를 지나왔으므로 주문 전 보유량은 0 이다.
        return placeBuy(ticker, state, currentPrice, investAmount, strategyName, triggerPrice = null, priorVolume = 0.0)
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

    /** 주문 발행 이후의 공용 경로 — pending durable 기록 → 체결 확인 → 확정. 가드·금액 결정은 호출부 몫. */
    private suspend fun placeBuy(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        investAmount: Double,
        strategyName: String,
        triggerPrice: Double?,
        priorVolume: Double,
    ): TradeRecord? {
        // placeOrder 까지: 실패하면 주문이 나가지 않았으므로 그대로 종료(pending 없음 → 다음 tick 정상 재매수).
        val order = try {
            // Upbit market buy: ord_type=price, price=총 투자금액
            upbitClient.placeOrder(
                OrderRequest(
                    market = ticker,
                    side = "bid",
                    ordType = "price",
                    price = floor(investAmount).toLong().toString(),
                )
            )
        } catch (e: CancellationException) {
            throw e // 취소는 오탐 ERROR 로 로깅하지 않고 전파(Discord 스팸 방지).
        } catch (e: Exception) {
            log.error("Failed to place buy order {}: {}", ticker, e.message, e)
            return null
        }

        // H8: 주문 접수 성공 → uuid 를 pending 으로 보존. 이후 체결 확인이 예외로 실패해도 uuid 를 잃지 않고
        // 다음 tick reconcilePendingBuy 가 이어받아 position 복구/미체결 확정 → 중복매수(2배 포지션) 방지.
        state.pendingBuyUuid = order.uuid
        state.pendingBuyStrategy = strategyName
        state.pendingBuyTriggerPrice = triggerPrice
        state.pendingBuyPriorVolume = priorVolume
        // 신규 진입이면 여기가 이 포지션의 시작점 — 옛 진입 메타를 지운 상태로 pending 을 기록해야, 체결 확인 전에
        // 재시작해도(syncPosition 이 position=true 를 먼저 세운다) 복원된 잔재가 상속되지 않는다.
        // 적립 추가 단은 기존 포지션 위에 얹는다 — 지우면 미체결(cancel+0)로 끝났을 때 buyDate·entryStrategy 가 영구 유실돼
        // 프로파일을 끈 뒤 보유상한 청산이 동작하지 않는다.
        if (!state.position) state.clearEntryMeta()
        return try {
            // 주문은 이미 나갔다 — 체결확인·상태반영은 취소돼도 원자적으로 완주해야 한다. reload/stop 이 tick 코루틴을
            // 취소하면 이 후처리가 중단돼 pending 이 폐기될 states 에만 남고(H8 방어망 무력화), 새 엔진이 같은 tick 을
            // 재매수해 이중 포지션이 된다. NonCancellable 로 원자화하면 cancelAndJoin 이 완주를 기다린다.
            withContext(NonCancellable) {
                // #20: pending 을 durable 로 먼저 기록해야 이 시점 크래시/재시작에도 reconcile 이 이어진다.
                // placeOrder↔기록 사이를 취소가 끊지 못하게 NonCancellable 안·awaitFill 이전에 수행.
                persistPending(state)
                val filled = awaitFill(order.uuid)
                applyFillOutcome(ticker, state, currentPrice, filled)
            }
        } catch (e: CancellationException) {
            throw e // 취소는 삼키지 않고 전파(구조적 동시성).
        } catch (e: Exception) {
            log.error("Buy post-order processing failed for {} (pending kept for reconcile): {}", ticker, e.message, e)
            null // pending 유지 → 다음 tick reconcile
        }
    }

    /**
     * H8: 미해소 매수 주문(pendingBuyUuid)을 거래소 상태로 확정한다. processTicker 가 매 tick 호출.
     * getOrder 장애 시 getAccounts 실잔고로 복원(무방비보유 방지). 미해소면 pending 유지(다음 tick 재시도).
     */
    suspend fun reconcilePendingBuy(ticker: String, state: TradingState, currentPrice: Double): TradeRecord? {
        val uuid = state.pendingBuyUuid ?: return null
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
                // ord_type=price 의 `price` 는 요청 KRW — 적립 단의 체결 비율 판정 근거.
                completeBuy(ticker, state, currentPrice, executed, account, filled.feeBasis())
            }
            filled?.state == "wait" -> null // 아직 진행중 — pending 유지, 다음 tick 재시도
            else -> {
                // cancel+0 등 미체결 — 주문 무산, pending 해소
                log.warn("Pending buy unfilled for {}: state={} — order abandoned", ticker, filled?.state)
                state.pendingBuyUuid = null
                state.pendingBuyStrategy = null
                state.pendingBuyTriggerPrice = null
                state.pendingBuyPriorVolume = null
                persist(state)
                null
            }
        }
    }

    /**
     * getOrder 장애 시 거래소 실잔고로 체결 여부 추정 복원. 잔고 있으면 확정, 없으면 pending 유지(다음 tick).
     * 전제: 1 ticker = 1 position, pending 생존 중 position=false(이전 봇 보유분 없음)이므로 해당 통화 잔고는
     * 이 주문 체결분이다. dust/수동매수 혼입 보정은 범위 밖(M3·수동매매 동기화 별도).
     */
    private sealed interface BalanceRecovery {
        data class Filled(val record: TradeRecord) : BalanceRecovery

        /** 잔고조회 성공 + 잔고 0 — 주문이 아직 체결 안 된 정상 상태. */
        data object NoBalance : BalanceRecovery

        /** 잔고조회 자체가 실패 — 체결 여부를 판단할 수단이 없는 장애 상태. */
        data object LookupFailed : BalanceRecovery
    }

    private suspend fun recoverFromBalance(ticker: String, state: TradingState, currentPrice: Double): BalanceRecovery {
        return try {
            val account = findAccount(ticker.substringAfter("-"))
            val balance = account?.balanceDouble() ?: 0.0
            // 적립 추가 단은 주문 전부터 코인이 있다 — 주문 시점 보유량을 넘는 증분만 이 주문의 체결로 본다.
            // 그 값이 없는 옛 pending 은 종전대로 잔고 전체(스윙은 position=false 였으므로 0 과 같다).
            val prior = state.pendingBuyPriorVolume ?: 0.0
            val executed = balance - prior
            if (executed > VOLUME_EPSILON) {
                // 주문 응답이 없어 실제 수수료를 알 수 없다. 잔고 전제는 *수량 귀속*의 근거이지
                // 수수료 복원의 근거가 아니므로, 틀린 추정 대신 미기록으로 남긴다(#133).
                BalanceRecovery.Filled(
                    completeBuy(ticker, state, currentPrice, executed, account, FeeBasis.Unrecorded)
                )
            } else {
                log.warn("reconcile pending kept for {}: order unknown and no balance", ticker)
                BalanceRecovery.NoBalance
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("reconcile balance recovery failed for {} ({}) — pending kept", ticker, e.message)
            BalanceRecovery.LookupFailed
        }
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
        val volume = account?.let { heldVolume(it, 0.0) }?.takeIf { it > 0.0 } ?: executedVolume
        val fillPrice = account?.avgBuyPriceDouble()?.takeIf { it > 0.0 } ?: currentPrice
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
            exchangeOrderId = orderUuid,
        )
        // #52: 전이를 한 곳에 모아 사본과 원본에 각각 적용한다. `now` 를 고정해 두 적용이 동일한 결과를 낸다.
        val now = LocalDateTime.now(TradingDay.KST)
        val snapshot = snapshotExitParams()
        val applyTransition: (TradingState) -> Unit = { s ->
            // 체결 확정 = reconcile 진전이므로 실패 카운터 해소.
            s.reconcileFailureCount = 0
            // replace=true: 거래소 실잔고를 절대값으로 반영해 syncPosition 복원분과 이중계상되지 않게(#20). markBought 가 pendingBuy* clear.
            s.markBought(fillPrice, volume, strategy, replace = true, now = now)
            // 진입 시점 청산 파라미터 스냅샷. markBought 뒤에 찍는다 — 신규 진입이면 markBought 가 옛 스냅샷을 비우므로
            // 여기서 현재 설정으로 새로 찍히고, 재시작 복원(기존 포지션 연장)이면 durable 값이 그대로 유지된다.
            s.exitParams = s.exitParams ?: snapshot
            // 사다리 장부는 이 커밋 안에서만 바뀐다 — 밖에서 올리면 크래시 창에서 같은 단을 다시 산다.
            if (rungFilled) {
                s.rungsFilled += 1
                s.lastActionPrice = triggerPrice!!
            }
            s.pendingBuyTriggerPrice = null
            s.pendingBuyPriorVolume = null
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

    private fun snapshotExitParams() = ExitParamsSnapshot(
        takeProfitPct = tradingProperties.takeProfitPct,
        maxLossPct = tradingProperties.maxLossPct,
        trailingStopPct = tradingProperties.trailingStopPct,
        trailingArmPct = tradingProperties.trailingArmPct,
        maxHoldDays = tradingProperties.maxHoldDays,
    )

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

    /** durable 복원본. 런타임에 새로 활성화되는 티커가 빈 상태로 시딩돼 pending uuid·halt 를 덮어쓰지 않게 한다. */
    internal suspend fun loadState(ticker: String): TradingState? = tradingStateService.loadState(userId, ticker)

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
     * 매도판 H8: 미해소 매도 주문(pendingSellUuid)을 거래소 상태로 확정. processTicker 가 매 tick 호출.
     * getOrder 장애 시 실잔고로 체결 추정(잔고 0 = 청산됨). 미해소면 pending 유지(다음 tick 재시도).
     */
    suspend fun reconcilePendingSell(ticker: String, state: TradingState, currentPrice: Double): TradeRecord? {
        val uuid = state.pendingSellUuid ?: return null
        val result = try {
            val filled = upbitClient.getOrder(uuid)
            applySellFillOutcome(ticker, state, currentPrice, filled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("reconcile sell getOrder failed for {} ({}): falling back to balance", ticker, e.message)
            recoverSellFromBalance(ticker, state, currentPrice)
        }
        // 미해소 pending sell 은 processTicker 에서 매도·매수 평가를 통째로 막는다 — 오래 끌면 보유 포지션이
        // 손절도 못 한 채 방치되므로, 매수판 halt 와 같은 상한에서 한 번 ERROR 로 올려 사람이 개입하게 한다.
        // (halt 는 신규 매수만 막는 플래그라 여기선 쓰지 않는다 — 필요한 건 차단이 아니라 알림.)
        if (state.pendingSellUuid != null) {
            warnIfSellStuckTooLong(ticker, state, uuid)
        }
        // 매도 pending 전이(clearPendingSell/markSold/부분갱신) durable 반영. wait(무전이)도 upsert 무해.
        persist(state)
        return result
    }

    /**
     * 막힌 매도를 사람에게 한 번 알린다. 카운터가 아니라 **경과시간**으로 판정하는 이유는 pending 이
     * durable 이기 때문이다 — 메모리 카운터는 배포·크래시마다 0 으로 돌아가 임계에 영영 못 닿았고,
     * 그 사이 processTicker 는 매도·매수 평가를 통째로 막아 보유 포지션이 손절 없이 방치됐다(#55).
     *
     * 임계는 기존 의미(`reconcileHaltThreshold` tick)를 시간으로 환산해 유지한다.
     * (halt 는 신규 매수만 막는 플래그라 여기선 쓰지 않는다 — 필요한 건 차단이 아니라 알림.)
     */
    private fun warnIfSellStuckTooLong(ticker: String, state: TradingState, uuid: String) {
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
            stuckFor.seconds, ticker, uuid,
        )
        state.pendingSellAlerted = true
    }

    suspend fun sell(ticker: String, state: TradingState, currentPrice: Double, reason: SellReason): TradeRecord? =
        placeSell(ticker, state, currentPrice, reason, triggerPrice = null) { account ->
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
                val volume = minOf(action.volume, sellable)
                SellQuantity(formatVolume(volume), volume)
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
        triggerPrice: Double?,
        quantity: (Account) -> SellQuantity,
    ): TradeRecord? {
        if (!state.position) return null
        // 미해소 매도 주문이 있으면 신규 매도 금지 — reconcile 로 확정될 때까지 이중 매도 방지(매수 pending 가드 미러).
        if (state.pendingSellUuid != null) {
            log.debug("Skip sell for {}: pending sell {} awaiting reconcile", ticker, state.pendingSellUuid)
            return null
        }

        // 잔고 조회·phantom 판정·placeOrder 까지: 실패하면 주문이 나가지 않았으므로 pending 없이 종료(포지션 유지 → 다음 tick 재매도).
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
            // M4: balance=0 이어도 locked>0 이면 매도 주문이 진행 중(잔고가 locked 로 이동)일 수 있다.
            // locked>0 이면 phantom 이 아니므로 markSold 하지 않고 보류(다음 tick 재시도). balance+locked 가
            // 둘 다 0 일 때만 진짜 phantom 으로 청산. (locked 무한상주 시 미체결주문 취소 후 재매도는 M3 별도 PR.)
            val locked = account?.lockedDouble() ?: 0.0
            if (locked > 0.0) {
                log.warn("Sell deferred for {}: free balance 0 but locked={} (order in flight) — keeping position", ticker, locked)
                return null
            }
            log.warn("Sell aborted for {}: no balance on exchange — clearing phantom position", ticker)
            state.markSold()
            // 사다리는 여기서부터의 눌림을 기다린다 — 옛 고점이 남으면 수동 청산 직후 곧바로 첫 단이 들어간다.
            if (reason == SellReason.ACCUMULATE_STEP) state.flatPeak = currentPrice
            persist(state)
            return null
        }

        // Upbit market sell: ord_type=market. 전량이면 거래소 원본 문자열, 부분이면 plain decimal.
        val qty = quantity(account!!)
        val order = try {
            upbitClient.placeOrder(
                OrderRequest(
                    market = ticker,
                    side = "ask",
                    ordType = "market",
                    volume = qty.orderVolume,
                )
            )
        } catch (e: CancellationException) {
            throw e // 취소는 오탐 ERROR 로 로깅하지 않고 전파(Discord 스팸 방지).
        } catch (e: Exception) {
            log.error("Failed to place sell order {}: {}", ticker, e.message, e)
            return null
        }

        // 매도판 H8: 주문 접수 성공 → uuid·사유 보존. 이후 체결확인이 실패/미확정이어도 uuid 를 잃지 않고
        // 다음 tick reconcilePendingSell 이 이어받아 청산 확정·기록 → 이중매도·감사유실 방지.
        state.pendingSellUuid = order.uuid
        state.pendingSellReason = reason
        // 미해소가 얼마나 끌었는지는 재시작 횟수와 무관해야 한다 — 시작 시각을 durable 로 남긴다(#55).
        state.pendingSellSince = clock.instant()
        state.pendingSellAlerted = false
        // 재시작 후에는 잔고·평단이 이미 비어 있으므로, 청산 기록의 근거를 주문 시점 값으로 함께 남긴다.
        state.pendingSellVolume = qty.volume
        state.pendingSellAvgPrice = state.avgBuyPrice
        state.pendingSellTriggerPrice = triggerPrice
        return try {
            // 매수판과 동일 — 주문 접수 후 체결확인·상태반영은 취소돼도 원자 완주해야 청산 기록이 유실되지 않는다.
            withContext(NonCancellable) {
                // 매도 pending 을 durable 로 먼저 기록(취소·크래시가 placeOrder 와 기록 사이를 끊지 못하게).
                persistPending(state)
                val filled = awaitFill(order.uuid)
                if (filled?.state == "done") {
                    // 즉시 체결 — 주문량으로 기록. done 은 upbit 시장가 매도의 정상 종결.
                    // #52: 상태 전이 저장과 감사 기록을 원자 커밋하고, 성공 후에만 메모리 전이를 적용한다.
                    completeSellAtomically(ticker, state, currentPrice, qty.volume, reason, remaining = sellable - qty.volume)
                } else {
                    // 미확정(wait/cancel) 또는 부분체결(cancel+executed>0) — pending 유지, 다음 tick reconcilePendingSell.
                    log.warn("Sell not confirmed for {}: state={} — pending kept for reconcile", ticker, filled?.state)
                    persist(state) // pending 유지 상태 durable 반영
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e // 취소는 삼키지 않고 전파(구조적 동시성).
        } catch (e: Exception) {
            log.error("Sell post-order processing failed for {} (pending kept for reconcile): {}", ticker, e.message, e)
            null // pending 유지 → 다음 tick reconcile
        }
    }

    /**
     * 매도 체결 판정 후 상태 반영 (reconcile 전용). done 은 sell() 즉시경로가 처리하므로 여기로 오는 건 wait/cancel.
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
        if (filled?.state == "wait") return null // 진행중 — pending 유지, 다음 tick 재시도
        val executed = filled?.executedVolume?.toDoubleOrNull() ?: 0.0
        return when {
            executed > 0.0 -> {
                // terminal(done 전량 또는 cancel 부분) + 체결분. 잔여는 우리 주문이 못 판 몫까지만 센다 —
                // 취소된 미체결 잔량은 free 로 돌아오지만 반영이 늦을 수 있고, 반대로 남은 locked 가 우리
                // 주문 것이 아닐 수도 있다(출금 대기 등). 둘 다 [heldVolume] 의 상한이 처리한다.
                // buildSellRecord 는 평단이 필요하므로 전이 전에 만든다. 잔고 조회도 트랜잭션 밖에서 끝낸다(#52).
                val record = buildSellRecord(ticker, state, currentPrice, executed)
                val account = findAccount(ticker.substringAfter("-"))
                val unfilled = (ourSellLockCeiling(state) - executed).coerceAtLeast(0.0)
                val remaining = account?.let { heldVolume(it, unfilled) } ?: 0.0
                val recoveredAvg = account?.avgBuyPriceDouble() ?: 0.0
                val now = LocalDateTime.now(TradingDay.KST)
                commitFillAndApply(state, record, sellTransition(state, executed, remaining, recoveredAvg, now))
                if (remaining > 0.0) {
                    log.info("SELL {} partial via reconcile: executed={}, remaining={} — position kept", ticker, executed, remaining)
                } else {
                    log.info("SELL {} filled via reconcile: volume={}, reason={}", ticker, executed, record.reason)
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
        return try {
            val total = findAccount(ticker.substringAfter("-"))?.totalBalance() ?: 0.0
            if (total <= 0.0) {
                // 주문 시점 수량이 우선 — 재시작 후 holdVolume 은 이미 0 으로 동기화돼 있다.
                val volume = state.pendingSellVolume ?: state.holdVolume
                if (volume <= 0.0) {
                    // 근거 없이 잔고 0 만으로 확정하면 수량 0 의 유령 SELL 이 감사에 남는다 — pending 을 유지해
                    // getOrder 복구나 사람 개입으로 확정하게 둔다.
                    log.error("reconcile sell for {}: zero balance but no recorded sell volume — pending kept for manual review", ticker)
                    return null
                }
                val record = buildSellRecord(ticker, state, currentPrice, volume)
                val now = LocalDateTime.now(TradingDay.KST)
                // #52: 잔고 기반 복원도 감사 기록과 원자 커밋 — 실패 시 pending 이 남아 다음 tick 이 재시도한다.
                commitFillAndApply(state, record, sellTransition(state, volume, remaining = 0.0, recoveredAvg = 0.0, now = now))
                log.info("SELL {} recovered from zero balance (getOrder down): volume={}", ticker, volume)
                record
            } else {
                log.warn("reconcile sell pending kept for {}: order unknown and balance remains (total={})", ticker, total)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("reconcile sell balance recovery failed for {} ({}) — pending kept", ticker, e.message)
            null
        }
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
    ): TradeRecord {
        val record = buildSellRecord(ticker, state, currentPrice, volume, reason)
        val now = LocalDateTime.now(TradingDay.KST)
        commitFillAndApply(state, record, sellTransition(state, volume, remaining, state.avgBuyPrice, now))
        log.info(
            "SELL {} filled: price={}, volume={}, net pnl={}%, reason={}",
            ticker, currentPrice, volume, record.pnlPercent?.let { "%.2f".format(it) } ?: "-", record.reason,
        )
        return record
    }

    /**
     * 매도 TradeRecord 생성 — 기록용 pnl 은 왕복수수료 차감(net, 백테스트 feeRate×2 와 통일). 청산 게이트는 gross 유지.
     * 평단 미상(외부 입금분 syncPosition 복원 등)이면 pnl null — 0%−fee 의 가짜 손실(−0.1%) 기록 방지.
     * markSold 이전에 호출해야 avgBuyPrice·entryStrategy 가 살아있어 손익과 전략 귀속이 복원된다.
     * reason 미지정 시 state.pendingSellReason 사용.
     */
    private fun buildSellRecord(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        volume: Double,
        reason: SellReason? = state.pendingSellReason,
    ): TradeRecord {
        // 재시작 복원 경로에서는 avgBuyPrice 가 이미 0 으로 동기화돼 있으므로 주문 시점 평단을 쓴다.
        val basisPrice = if (state.avgBuyPrice > 0) state.avgBuyPrice else state.pendingSellAvgPrice ?: 0.0
        val pnl = TradePnl.netPercent(currentPrice, basisPrice, tradingProperties.roundTripFeeRate)
        return TradeRecord(
            userId = userId,
            ticker = ticker,
            side = TradeSide.SELL,
            price = currentPrice,
            volume = volume,
            totalAmount = currentPrice * volume,
            pnlPercent = pnl,
            pnlAmount = TradePnl.amount(pnl, basisPrice, volume),
            // 청산은 진입 전략의 성과로 귀속한다. 매도 시점의 활성 전략을 쓰면 설정을 바꾼 뒤의 청산이
            // 엉뚱한 전략 몫으로 잡힌다. markSold 가 clearEntryMeta 로 지우기 전이라 값이 살아 있다.
            // 적립 단 매도는 편입된 스윙 포지션이어도 적립 몫이다 — 그 규칙으로 팔았다.
            strategy = if (reason == SellReason.ACCUMULATE_STEP) AccumulateLadder.STRATEGY_NAME else state.entryStrategy,
            // 매도의 totalAmount 는 이 매도의 대금(가격×수량)이라 추정 기준이 맞다 — 매수와 달리 스냅샷이 아니다.
            fee = FeeBasis.Estimate,
            reason = reason?.name,
            // markSold 이전 호출이라 pendingSellUuid 가 살아있음 — 재시작 reconcile 중복 기록을 막는 dedup 키.
            exchangeOrderId = state.pendingSellUuid,
        )
    }

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

    /** 주문 체결 폴링. state 가 done/cancel 이면 즉시 반환, 아니면 최대 FILL_POLL_ATTEMPTS 회 폴링. */
    private suspend fun awaitFill(uuid: String): Order? {
        if (uuid.isBlank()) return null
        var last: Order? = null
        repeat(FILL_POLL_ATTEMPTS) {
            last = upbitClient.getOrder(uuid)
            when (last?.state) {
                "done", "cancel" -> return last
            }
            delay(FILL_POLL_DELAY_MS)
        }
        return last
    }

    fun checkTakeProfit(state: TradingState, currentPrice: Double): Boolean {
        if (!state.position) return false
        return state.pnlPercent(currentPrice) >= tradingProperties.takeProfitPct
    }

    fun checkStopLoss(state: TradingState, currentPrice: Double): Boolean {
        if (!state.position) return false
        return state.pnlPercent(currentPrice) <= -tradingProperties.maxLossPct
    }

    fun checkTrailingStop(state: TradingState, currentPrice: Double): Boolean {
        if (!state.position) return false
        // Update peak price
        state.updatePeakPrice(currentPrice)
        return ExitGates.isTrailingStopTriggered(
            pnlPct = state.pnlPercent(currentPrice),
            peakPnlPct = state.pnlPercent(state.peakPrice),
            dropFromPeakPct = state.dropFromPeakPercent(currentPrice),
            trailingStopPct = tradingProperties.trailingStopPct,
            trailingArmPct = tradingProperties.trailingArmPct,
        )
    }

    private suspend fun getKrwBalance(): Double = findAccount("KRW")?.balanceDouble() ?: 0.0

    private fun calculateInvestAmount(krwBalance: Double): Double {
        // 잔액의 investRatio 비율만 투자하되 maxInvestAmount 로 상한.
        return minOf(krwBalance * tradingProperties.investRatio, tradingProperties.maxInvestAmount)
    }
}
