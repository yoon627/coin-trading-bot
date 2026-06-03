package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties
import kotlin.math.floor
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

class PositionManager(
    private val upbitClient: UpbitClient,
    private val tradingProperties: TradingProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MIN_ORDER_AMOUNT_KRW = 5000.0
        private const val FILL_POLL_ATTEMPTS = 10
        private const val FILL_POLL_DELAY_MS = 300L
    }

    suspend fun syncPosition(ticker: String, state: TradingState) {
        try {
            val accounts = upbitClient.getAccounts()
            val currency = ticker.substringAfter("-")
            val account = accounts.find { it.currency == currency }
            if (account != null && account.balanceDouble() > 0) {
                state.position = true
                state.avgBuyPrice = account.avgBuyPriceDouble()
                state.holdVolume = account.balanceDouble()
                log.info("Synced existing position for {}: price={}, volume={}", ticker, state.avgBuyPrice, state.holdVolume)
            }
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            log.error("Failed to place buy order {}: {}", ticker, e.message, e)
            return null
        }

        // H8: 주문 접수 성공 → uuid 를 pending 으로 보존. 이후 체결 확인이 예외로 실패해도 uuid 를 잃지 않고
        // 다음 tick reconcilePendingBuy 가 이어받아 position 복구/미체결 확정 → 중복매수(2배 포지션) 방지.
        state.pendingBuyUuid = order.uuid
        state.pendingBuyStrategy = strategyName
        return try {
            val filled = awaitFill(order.uuid)
            applyFillOutcome(ticker, state, currentPrice, filled)
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
        } catch (e: Exception) {
            log.warn("reconcile getOrder failed for {} ({}): falling back to balance", ticker, e.message)
            return recoverFromBalance(ticker, state, currentPrice)
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
                val account = upbitClient.getAccounts().find { it.currency == ticker.substringAfter("-") }
                completeBuy(ticker, state, currentPrice, executed, account)
            }
            filled?.state == "wait" -> null // 아직 진행중 — pending 유지, 다음 tick 재시도
            else -> {
                // cancel+0 등 미체결 — 주문 무산, pending 해소
                log.warn("Pending buy unfilled for {}: state={} — order abandoned", ticker, filled?.state)
                state.pendingBuyUuid = null
                state.pendingBuyStrategy = null
                null
            }
        }
    }

    /**
     * getOrder 장애 시 거래소 실잔고로 체결 여부 추정 복원. 잔고 있으면 확정, 없으면 pending 유지(다음 tick).
     * 전제: 1 ticker = 1 position, pending 생존 중 position=false(이전 봇 보유분 없음)이므로 해당 통화 잔고는
     * 이 주문 체결분이다. dust/수동매수 혼입 보정은 범위 밖(M3·수동매매 동기화 별도).
     */
    private suspend fun recoverFromBalance(ticker: String, state: TradingState, currentPrice: Double): TradeRecord? {
        return try {
            val account = upbitClient.getAccounts().find { it.currency == ticker.substringAfter("-") }
            val balance = account?.balanceDouble() ?: 0.0
            if (balance > 0.0) {
                completeBuy(ticker, state, currentPrice, balance, account)
            } else {
                log.warn("reconcile pending kept for {}: order unknown and no balance", ticker)
                null
            }
        } catch (e: Exception) {
            log.warn("reconcile balance recovery failed for {} ({}) — pending kept", ticker, e.message)
            null
        }
    }

    /** 실잔고/평단으로 markBought + TradeRecord. account==null/잔고0 이면 executedVolume·currentPrice fallback. */
    private fun completeBuy(
        ticker: String,
        state: TradingState,
        currentPrice: Double,
        executedVolume: Double,
        account: Account?,
    ): TradeRecord {
        // pending 은 buy 에서 항상 strategy 와 함께 set 되므로 정상흐름상 non-null. null 은 그대로 두어
        // entryStrategy=null → resolveExitStrategy 가 조용히 fallback(빈 문자열 "" 은 WARN 스팸 유발).
        val strategy = state.pendingBuyStrategy
        val volume = account?.balanceDouble()?.takeIf { it > 0.0 } ?: executedVolume
        val fillPrice = account?.avgBuyPriceDouble()?.takeIf { it > 0.0 } ?: currentPrice
        val totalAmount = fillPrice * volume
        state.markBought(fillPrice, volume, strategy) // markBought 내부에서 pendingBuy* clear
        log.info("BUY {} filled: volume={}, avgPrice={}, amount={}", ticker, volume, fillPrice, totalAmount)
        return TradeRecord(
            ticker = ticker,
            side = TradeSide.BUY,
            price = fillPrice,
            volume = volume,
            totalAmount = totalAmount,
            strategy = strategy,
        )
    }

    suspend fun sell(ticker: String, state: TradingState, currentPrice: Double, reason: SellReason): TradeRecord? {
        if (!state.position) return null

        try {
            // 매도 수량은 state.holdVolume(조작 가능)이 아니라 거래소 실잔고를 사용.
            val currency = ticker.substringAfter("-")
            val account = upbitClient.getAccounts().find { it.currency == currency }
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
                return null
            }

            // Upbit market sell: ord_type=market, volume=실보유수량(거래소 원본 문자열)
            val order = upbitClient.placeOrder(
                OrderRequest(
                    market = ticker,
                    side = "ask",
                    ordType = "market",
                    volume = account!!.balance,
                )
            )

            // 체결 확정 전엔 포지션 유지 (다음 tick 재시도). done 일 때만 청산 기록.
            val filled = awaitFill(order.uuid)
            if (filled?.state != "done") {
                log.warn("Sell not confirmed for {}: state={} — keeping position for retry", ticker, filled?.state)
                return null
            }

            val pnl = state.pnlPercent(currentPrice)
            val totalAmount = currentPrice * sellable
            log.info("SELL {} filled: price={}, volume={}, pnl={}%, reason={}", ticker, currentPrice, sellable, "%.2f".format(pnl), reason)

            val record = TradeRecord(
                ticker = ticker,
                side = TradeSide.SELL,
                price = currentPrice,
                volume = sellable,
                totalAmount = totalAmount,
                pnlPercent = pnl,
                reason = reason.name,
            )
            state.markSold()
            return record
        } catch (e: Exception) {
            log.error("Failed to sell {}: {}", ticker, e.message, e)
            return null
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
        val pnl = state.pnlPercent(currentPrice)
        // Only activate trailing stop if in profit
        if (pnl <= 0) return false
        val dropFromPeak = state.dropFromPeakPercent(currentPrice)
        return dropFromPeak >= tradingProperties.trailingStopPct
    }

    private suspend fun getKrwBalance(): Double {
        val accounts = upbitClient.getAccounts()
        val krw = accounts.find { it.currency == "KRW" }
        return krw?.balanceDouble() ?: 0.0
    }

    private fun calculateInvestAmount(krwBalance: Double): Double {
        // 잔액의 investRatio 비율만 투자하되 maxInvestAmount 로 상한.
        return minOf(krwBalance * tradingProperties.investRatio, tradingProperties.maxInvestAmount)
    }
}
