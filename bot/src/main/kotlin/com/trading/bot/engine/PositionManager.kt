package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.ExitGates
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MIN_ORDER_AMOUNT_KRW = 5000.0
        private const val FILL_POLL_ATTEMPTS = 10
        private const val FILL_POLL_DELAY_MS = 300L
    }

    /** 거래소 계좌 목록에서 해당 통화 계좌 조회 (#21 — getAccounts().find 중복 헬퍼화). */
    private suspend fun findAccount(currency: String): Account? =
        upbitClient.getAccounts().find { it.currency == currency }

    suspend fun syncPosition(ticker: String, state: TradingState) {
        try {
            val account = findAccount(ticker.substringAfter("-"))
            if (account != null && account.balanceDouble() > 0) {
                state.position = true
                state.avgBuyPrice = account.avgBuyPriceDouble()
                state.holdVolume = account.balanceDouble()
                log.info("Synced existing position for {}: price={}, volume={}", ticker, state.avgBuyPrice, state.holdVolume)
            }
            // 조회 성공(보유 유무 무관) → 동기화 완료, 매수 차단 해소.
            state.unsynced = false
        } catch (e: CancellationException) {
            throw e // 취소는 전파(unsynced 로 삼키지 않는다).
        } catch (e: Exception) {
            // 동기화 실패 → position 상태 불확실. unsynced 로 표시해 buy() 가 신규 진입을 막고 다음 tick 재시도(processTicker).
            state.unsynced = true
            log.warn("Failed to sync position for {}: {}", ticker, e.message)
        }
    }

    suspend fun buy(ticker: String, state: TradingState, currentPrice: Double, strategyName: String): TradeRecord? {
        // 재매수 가드: 이미 보유 중이거나, 미해소 매수 주문(pending)이 있으면 신규 매수 금지.
        if (state.position) {
            log.debug("Skip buy for {}: already holding position", ticker)
            return null
        }
        if (state.pendingBuyUuid != null) {
            log.debug("Skip buy for {}: pending order {} awaiting reconcile", ticker, state.pendingBuyUuid)
            return null
        }
        if (state.unsynced) {
            // 거래소 동기화 실패로 보유 여부가 불확실 — 신규 매수 시 이미 보유분과 이중 포지션 위험. skip(processTicker 가 재시도).
            log.debug("Skip buy for {}: position not synced with exchange — avoiding double entry", ticker)
            return null
        }
        if (state.pendingPersistFailed) {
            // 직전 pending durable 기록이 실패 — 지금 매수하면 크래시 시 pending 유실로 복구 불가. 재기록 성공 전까지 진입 차단.
            log.warn("Skip buy for {}: pending persistence unhealthy — avoiding unrecoverable entry", ticker)
            return null
        }
        if (state.halted) {
            // #19: reconcile 무한 실패로 halt — 신규 진입만 막는다. 매도·reconcile·잔고 동기화는 계속 돌아야
            // 이미 잡힌 포지션이 청산되지 못한 채 갇히지 않는다(수동 해제 전까지).
            log.warn("Skip buy for {}: halted ({})", ticker, state.haltReason)
            return null
        }

        // placeOrder 까지: 실패하면 주문이 나가지 않았으므로 그대로 종료(pending 없음 → 다음 tick 정상 재매수).
        val order = try {
            val krwAccount = getKrwBalance()
            val investAmount = calculateInvestAmount(krwAccount)
            if (investAmount < MIN_ORDER_AMOUNT_KRW) {
                log.debug("Insufficient funds for {}: investAmount={}", ticker, investAmount)
                return null
            }
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
                // (여기서 세면 미체결 주문 하나로 멀쩡한 ticker 가 매수 정지된다.)
                BalanceRecovery.NoBalance -> null
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
            executed > 0.0 -> {
                // 부분체결(cancel/wait) 포함 — 실제 코인을 받았으므로 매수 확정. 실수량/평단은 실잔고로 재확인.
                val account = findAccount(ticker.substringAfter("-"))
                completeBuy(ticker, state, currentPrice, executed, account)
            }
            filled?.state == "wait" -> null // 아직 진행중 — pending 유지, 다음 tick 재시도
            else -> {
                // cancel+0 등 미체결 — 주문 무산, pending 해소
                log.warn("Pending buy unfilled for {}: state={} — order abandoned", ticker, filled?.state)
                state.pendingBuyUuid = null
                state.pendingBuyStrategy = null
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
            if (balance > 0.0) {
                BalanceRecovery.Filled(completeBuy(ticker, state, currentPrice, balance, account))
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

    /** 실잔고/평단으로 markBought + TradeRecord. account==null/잔고0 이면 executedVolume·currentPrice fallback. */
    private suspend fun completeBuy(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        executedVolume: Double,
        account: Account?,
    ): TradeRecord {
        // pending 은 buy 에서 항상 strategy 와 함께 set 되므로 정상흐름상 non-null. null 은 그대로 두어
        // entryStrategy=null → resolveExitStrategy 가 조용히 fallback(빈 문자열 "" 은 WARN 스팸 유발).
        val strategy = state.pendingBuyStrategy
        val orderUuid = state.pendingBuyUuid // markBought 가 clear 하기 전에 캡처 — 멱등 dedup 키.
        val volume = account?.balanceDouble()?.takeIf { it > 0.0 } ?: executedVolume
        val fillPrice = account?.avgBuyPriceDouble()?.takeIf { it > 0.0 } ?: currentPrice
        val totalAmount = fillPrice * volume
        // 진입 시점 청산 파라미터 스냅샷(재시작 복원 시엔 기존 값 유지). 체결 확정 = reconcile 진전이므로 실패 카운터 해소.
        state.exitParams = state.exitParams ?: snapshotExitParams()
        state.reconcileFailureCount = 0
        // replace=true: 거래소 실잔고를 절대값으로 반영해 syncPosition 복원분과 이중계상되지 않게(#20). markBought 가 pendingBuy* clear.
        state.markBought(fillPrice, volume, strategy, replace = true)
        persist(state)
        log.info("BUY {} filled: volume={}, avgPrice={}, amount={}", ticker, volume, fillPrice, totalAmount)
        return TradeRecord(
            ticker = ticker,
            side = TradeSide.BUY,
            price = fillPrice,
            volume = volume,
            totalAmount = totalAmount,
            strategy = strategy,
            exchangeOrderId = orderUuid,
        )
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
    private suspend fun persist(state: TradingState) {
        try {
            tradingStateService.upsert(userId, state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("trading_state persist failed for {} — retry on next transition: {}", state.ticker, e.message)
        }
    }

    /** pending durable 기록 — 실패 시 pendingPersistFailed 게이트로 신규 진입을 차단(#20 크래시 윈도우 방어). */
    private suspend fun persistPending(state: TradingState) {
        try {
            tradingStateService.upsert(userId, state)
            state.pendingPersistFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.pendingPersistFailed = true
            log.error("pending persist failed for {} — blocking new entries (retry next tick): {}", state.ticker, e.message, e)
        }
    }

    /** engine 이 halt 수동 해제 등 도메인-외 상태 변경 후 durable 반영에 쓰는 진입점(best-effort). */
    internal suspend fun persistState(state: TradingState) = persist(state)

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
        // 매도 pending 전이(clearPendingSell/markSold/부분갱신) durable 반영. wait(무전이)도 upsert 무해.
        persist(state)
        return result
    }

    suspend fun sell(ticker: String, state: TradingState, currentPrice: Double, reason: SellReason): TradeRecord? {
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
            persist(state)
            return null
        }

        // Upbit market sell: ord_type=market, volume=실보유수량(거래소 원본 문자열)
        val order = try {
            upbitClient.placeOrder(
                OrderRequest(
                    market = ticker,
                    side = "ask",
                    ordType = "market",
                    volume = account!!.balance,
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
        return try {
            // 매수판과 동일 — 주문 접수 후 체결확인·상태반영은 취소돼도 원자 완주해야 청산 기록이 유실되지 않는다.
            withContext(NonCancellable) {
                // 매도 pending 을 durable 로 먼저 기록(취소·크래시가 placeOrder 와 기록 사이를 끊지 못하게).
                persistPending(state)
                val filled = awaitFill(order.uuid)
                val record = if (filled?.state == "done") {
                    // 즉시 전량 체결 — 주문량(sellable)으로 기록. done 은 upbit 시장가 매도의 정상 종결.
                    completeSellRecord(ticker, state, currentPrice, sellable, reason)
                } else {
                    // 미확정(wait/cancel) 또는 부분체결(cancel+executed>0) — pending 유지, 다음 tick reconcilePendingSell.
                    log.warn("Sell not confirmed for {}: state={} — pending kept for reconcile", ticker, filled?.state)
                    null
                }
                persist(state) // markSold(done) 또는 pending 유지 상태 durable 반영
                record
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
                // terminal(done 전량 또는 cancel 부분) + 체결분. 미체결 잔량은 취소돼 locked 가 free 로 돌아왔으므로 실잔고가 정확.
                val record = buildSellRecord(ticker, state, currentPrice, executed)
                val remaining = findAccount(ticker.substringAfter("-"))?.balanceDouble() ?: 0.0
                if (remaining > 0.0) {
                    // 부분 체결 — 잔여 실잔고로 갱신, avgBuyPrice 유지. pending 해소(잔여분은 다음 tick 재매도).
                    state.holdVolume = remaining
                    state.clearPendingSell()
                    log.info("SELL {} partial via reconcile: executed={}, remaining={} — position kept", ticker, executed, remaining)
                } else {
                    log.info("SELL {} filled via reconcile: volume={}, reason={}", ticker, executed, state.pendingSellReason)
                    state.markSold()
                }
                record
            }
            else -> {
                // cancel+0 미체결 — 매도 무산, pending 해소. 코인 그대로이므로 position 유지(다음 tick 재매도).
                log.warn("Pending sell unfilled for {}: state={} — order abandoned, position kept", ticker, filled?.state)
                state.clearPendingSell()
                null
            }
        }
    }

    /**
     * getOrder 장애 시 실잔고로 매도 체결 여부 추정 복원. free+locked 합(totalBalance)이 0 = 청산됨(markSold 이전
     * holdVolume 으로 기록), 남음 = 미체결/진행중(pending 유지, 다음 tick). free 만 보면 미체결 잔량이 locked 로 묶인
     * 진행중 주문을 청산으로 오판한다(codex P2). 전제: 1 ticker = 1 position, pending 생존 중 잔고 변화는 이 매도 결과.
     */
    private suspend fun recoverSellFromBalance(ticker: String, state: TradingState, currentPrice: Double): TradeRecord? {
        return try {
            val total = findAccount(ticker.substringAfter("-"))?.totalBalance() ?: 0.0
            if (total <= 0.0) {
                val record = buildSellRecord(ticker, state, currentPrice, state.holdVolume)
                log.info("SELL {} recovered from zero balance (getOrder down): volume={}", ticker, state.holdVolume)
                state.markSold()
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
    private fun completeSellRecord(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        soldVolume: Double,
        reason: SellReason,
    ): TradeRecord {
        val record = buildSellRecord(ticker, state, currentPrice, soldVolume, reason)
        log.info(
            "SELL {} filled: price={}, volume={}, net pnl={}%, reason={}",
            ticker, currentPrice, soldVolume, record.pnlPercent?.let { "%.2f".format(it) } ?: "-", reason,
        )
        state.markSold()
        return record
    }

    /**
     * 매도 TradeRecord 생성 — 기록용 pnl 은 왕복수수료 차감(net, 백테스트 feeRate×2 와 통일). 청산 게이트는 gross 유지.
     * 평단 미상(외부 입금분 syncPosition 복원 등)이면 pnl null — 0%−fee 의 가짜 손실(−0.1%) 기록 방지.
     * markSold 이전에 호출해야 avgBuyPrice 가 살아있어 pnl 복원 가능. reason 미지정 시 state.pendingSellReason 사용.
     */
    private fun buildSellRecord(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        volume: Double,
        reason: SellReason? = state.pendingSellReason,
    ): TradeRecord {
        val pnl = if (state.avgBuyPrice > 0) {
            state.pnlPercent(currentPrice) - tradingProperties.roundTripFeeRate * 100
        } else {
            null
        }
        return TradeRecord(
            ticker = ticker,
            side = TradeSide.SELL,
            price = currentPrice,
            volume = volume,
            totalAmount = currentPrice * volume,
            pnlPercent = pnl,
            reason = reason?.name,
            // markSold 이전 호출이라 pendingSellUuid 가 살아있음 — 재시작 reconcile 중복 기록을 막는 dedup 키.
            exchangeOrderId = state.pendingSellUuid,
        )
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
