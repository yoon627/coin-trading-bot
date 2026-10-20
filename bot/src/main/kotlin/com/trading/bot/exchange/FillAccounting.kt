package com.trading.bot.exchange

import com.trading.bot.domain.Order
import com.trading.bot.domain.TradingState

/** 매수 확정 뒤의 포지션 전체 수량과 평단. */
data class BuyFill(val volume: Double, val avgPrice: Double)

/** 매도 확정 뒤 남은 보유 수량과, 평단을 잃었을 때 되살릴 값. */
data class SellRemainder(val remaining: Double, val recoveredAvg: Double)

/** uuid 는 있는데 주문을 읽지 못했을 때 매수 체결을 판정한 결과. */
sealed interface BuyRecovery {
    /** [fill] 은 확정 뒤 포지션 전체다. */
    data class Filled(val fill: BuyFill) : BuyRecovery

    /** 확인했고 이 주문의 체결분이 없다. */
    data object NoChange : BuyRecovery

    data class LookupFailed(val message: String?) : BuyRecovery

    /** 주문 없이는 판정하지 않는 방식이다. pending 을 두고 장애로 센다. */
    data object Unsupported : BuyRecovery
}

/** uuid 는 있는데 주문을 읽지 못했을 때 매도 체결을 판정한 결과. */
sealed interface SellRecovery {
    /** 보유가 모두 사라져 청산으로 본다. */
    data object Cleared : SellRecovery

    data class StillHeld(val total: Double) : SellRecovery

    data class LookupFailed(val message: String?) : SellRecovery

    /** 주문 없이는 판정하지 않는 방식이다. pending 을 둔다. */
    data object Unsupported : SellRecovery
}

/**
 * 체결된 주문을 장부 수량·평단으로 옮기는 방법. 실잔고를 다시 읽을지, 주문의 체결 내역만 믿을지가 거래소마다 다르다.
 * sell() 이 주문 직후 done 을 받으면 free 전량이 팔린 것이라 잔량 0 으로 확정하고 [sellRemainder] 를 거치지 않는다.
 */
interface FillAccounting {
    /** [state] 는 아직 pending 매수를 들고 있다. [executedVolume] 은 이 주문의 체결 수량이다. */
    suspend fun buyFill(ticker: String, state: TradingState, order: Order, executedVolume: Double, currentPrice: Double): BuyFill

    /** done·cancel 로 끝난 매도. [state] 는 아직 pending 매도를 들고 있다. */
    suspend fun sellRemainder(ticker: String, state: TradingState, order: Order, executedVolume: Double): SellRemainder

    /** getOrder 가 실패한 매수. 조회 실패는 예외 대신 [BuyRecovery.LookupFailed] 로 돌려준다. */
    suspend fun recoverBuyWithoutOrder(ticker: String, state: TradingState, currentPrice: Double): BuyRecovery

    /** getOrder 가 실패한 매도. 조회 실패는 예외 대신 [SellRecovery.LookupFailed] 로 돌려준다. */
    suspend fun recoverSellWithoutOrder(ticker: String, state: TradingState): SellRecovery
}
