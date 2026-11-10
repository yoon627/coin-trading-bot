package com.trading.bot.engine

import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.BuyFill
import com.trading.bot.exchange.BuyRecovery
import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.exchange.FillAccounting
import com.trading.bot.exchange.SellRecovery
import com.trading.bot.exchange.SellRemainder
import kotlinx.coroutines.CancellationException

/**
 * 매수는 주문 때 봇 장부에 이번 체결을 더하고, 매도 잔량은 주문 수량과 실잔고로 정한다.
 * [sellRemainder] 와 장부를 잃은 [buyFill] 은 잔고 조회 예외를 호출부로 올린다.
 */
class BalanceFillAccounting(private val client: ExchangeClient) : FillAccounting {
    // 주문 때 장부를 알면 수량은 잔고를 읽지 않는다. 체결량은 주문 값이라 체결 직후 잔고가 늦게 반영돼도 빠지지 않는다.
    override suspend fun buyFill(ticker: String, state: TradingState, order: Order, executedVolume: Double, currentPrice: Double): BuyFill {
        val price = order.filledVwap() ?: soleFillPrice(state, executedVolume) { findAccountOrNull(ticker) } ?: currentPrice
        return bookBuyFill(state, executedVolume, price) ?: balanceBuyFill(state, findAccount(ticker), executedVolume, currentPrice)
    }

    /** 잔여는 우리 주문이 못 판 몫까지만 센다. 취소된 잔량이 free 로 늦게 돌아오거나 locked 가 출금 대기일 수 있다. */
    override suspend fun sellRemainder(ticker: String, state: TradingState, order: Order, executedVolume: Double): SellRemainder {
        val account = findAccount(ticker)
        val unfilled = (ourSellLockCeiling(state) - executedVolume).coerceAtLeast(0.0)
        // 주문 수량 중 안 팔린 몫만 봇 것이다. 그보다 많은 잔고는 소유자가 산 코인이다.
        val ours = account?.let { minOf(heldVolume(it, unfilled), (state.pendingSellVolume ?: Double.POSITIVE_INFINITY) - executedVolume) } ?: 0.0
        return SellRemainder(
            remaining = if (ours > VOLUME_EPSILON) ours else 0.0,
            recoveredAvg = account?.avgBuyPriceDouble() ?: 0.0,
        )
    }

    /** 주문 전 보유량을 넘는 free 증분만 이 주문의 체결로 본다. 같은 시각의 수동 매수는 구분하지 못한다. */
    override suspend fun recoverBuyWithoutOrder(ticker: String, state: TradingState, currentPrice: Double): BuyRecovery {
        val account = try {
            findAccount(ticker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BuyRecovery.LookupFailed(e.message)
        }
        val executed = (account?.balanceDouble() ?: 0.0) - (state.pendingBuyPriorVolume ?: 0.0)
        if (executed <= VOLUME_EPSILON) return BuyRecovery.NoChange
        val price = soleFillPrice(state, executed) { account } ?: currentPrice
        return BuyRecovery.Filled(bookBuyFill(state, executed, price) ?: balanceBuyFill(state, account, executed, currentPrice))
    }

    /**
     * locked 가 0 이고 free 가 주문 전 free 에서 주문 수량만큼 줄었으면 청산으로 본다. 남은 free 는 소유자 코인이다.
     * locked 가 남아 있으면 우리 주문이 아직 잠근 것일 수 있어 확정하지 않는다. 주문 수량을 모르는 옛 기록은
     * free·locked 가 모두 0 일 때만 확정한다. 같은 시각의 수동 매도는 구분하지 못한다.
     */
    override suspend fun recoverSellWithoutOrder(ticker: String, state: TradingState): SellRecovery {
        val account = try {
            findAccount(ticker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SellRecovery.LookupFailed(e.message)
        }
        val total = account?.totalBalance() ?: 0.0
        val prior = state.pendingSellPriorVolume
        val volume = state.pendingSellVolume
        val ownerFree = if (prior != null && volume != null) (prior - volume).coerceAtLeast(0.0) else 0.0
        val locked = account?.lockedDouble() ?: 0.0
        val free = account?.balanceDouble() ?: 0.0
        return if (locked <= VOLUME_EPSILON && free <= ownerFree + VOLUME_EPSILON) SellRecovery.Cleared else SellRecovery.StillHeld(total)
    }

    private suspend fun findAccount(ticker: String): Account? =
        client.getAccounts().find { it.currency == ticker.substringAfter("-") }

    // 평단은 덤이라 잔고를 못 읽어도 체결을 확정한다.
    private suspend fun findAccountOrNull(ticker: String): Account? =
        try {
            findAccount(ticker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /**
     * 체결 내역이 없을 때의 평단. 주문 전에 아무것도 없었고 계좌가 이번 체결을 이미 담고 있으면 계좌 평단이 곧 이번 체결가다
     * — 아직 안 담았으면 예전 포지션의 평단이 남아 있을 수 있다. 장부를 모르거나 소유자 코인이 섞였으면 null — 호출부는 그때
     * 가격을 쓴다(재확정 tick 이라 체결가와 어긋날 수 있다).
     */
    private suspend fun soleFillPrice(state: TradingState, executedVolume: Double, account: suspend () -> Account?): Double? {
        val book = state.pendingBuyBookVolume ?: return null
        if ((state.pendingBuyPriorVolume ?: 0.0) > VOLUME_EPSILON || book > VOLUME_EPSILON) return null
        val held = account() ?: return null
        if (held.balanceDouble() < executedVolume - VOLUME_EPSILON) return null
        return held.avgBuyPriceDouble().takeIf { it > 0.0 }
    }
}

/**
 * 주문 때 봇 장부에 이번 체결을 더한다. 평단은 장부 평단과 [fillPrice] 의 가중평균이다 — 계좌 평단에는 소유자 코인이 섞인다.
 * 주문 때 장부를 모르면(재시작으로 잃음) null 이고 호출부는 [balanceBuyFill] 로 정한다.
 */
internal fun bookBuyFill(state: TradingState, executedVolume: Double, fillPrice: Double): BuyFill? {
    val book = state.pendingBuyBookVolume ?: return null
    val bookAvg = state.pendingBuyBookAvg ?: 0.0
    val volume = book + executedVolume
    val avg = if (book > 0.0 && bookAvg > 0.0) (bookAvg * book + fillPrice * executedVolume) / volume else fillPrice
    return BuyFill(volume, avg)
}

/**
 * 실잔고로 매수 뒤 포지션을 정한다. 주문 때 장부를 잃은 재시작 뒤에만 쓴다 — 기동 동기화가 잔고 전체를 장부로 잡았으므로 맞춰 둔다. 매수 직후라 우리 매도 주문이 없으므로 free 만 센다.
 * 주문 전 보유량에 체결분을 더한 값보다 작게 잡지 않는다 — 체결 직후 잔고가 아직 주문 전 값이면 장부가 체결분을
 * 빠뜨리고, 장부가 손절 수량을 정하므로 손절이 줄거나 빠진다. 체결분만 쓰면 dust 흡수 때 기존 dust 가 장부에서 빠진다.
 * 평단도 같은 이유로 가중평균을 쓴다.
 */
internal fun balanceBuyFill(state: TradingState, account: Account?, executedVolume: Double, currentPrice: Double): BuyFill {
    val priorVolume = state.pendingBuyPriorVolume ?: 0.0
    val volume = maxOf(account?.let { heldVolume(it, 0.0) } ?: 0.0, executedVolume + priorVolume)
    val fillPrice = account?.avgBuyPriceDouble()?.takeIf { it > 0.0 }
        ?: if (priorVolume > 0.0 && state.avgBuyPrice > 0.0 && volume > 0.0) {
            (state.avgBuyPrice * priorVolume + currentPrice * executedVolume) / volume
        } else {
            currentPrice
        }
    return BuyFill(volume, fillPrice)
}
