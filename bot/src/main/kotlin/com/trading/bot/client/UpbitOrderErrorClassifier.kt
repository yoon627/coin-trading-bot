package com.trading.bot.client

import com.trading.bot.exchange.OrderErrorClassifier
import com.trading.bot.exchange.OrderFailure

object UpbitOrderErrorClassifier : OrderErrorClassifier {
    override fun classify(error: Throwable): OrderFailure = when {
        error.provesOrderNotSent() -> OrderFailure.NOT_SENT
        !error.provesOrderNotPlaced() -> OrderFailure.UNKNOWN_OUTCOME
        error.isRejectedAsBelowMinimumOrder() -> OrderFailure.REJECTED_BELOW_MIN_ORDER
        else -> OrderFailure.REJECTED
    }
}
