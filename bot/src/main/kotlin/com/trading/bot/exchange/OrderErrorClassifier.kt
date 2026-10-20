package com.trading.bot.exchange

/** 주문 요청이 예외로 끝났을 때 그 주문의 처지. */
enum class OrderFailure {
    /** 거래소가 주문을 만들지 않았음이 확정이다. */
    REJECTED,

    /** 최소주문 금액 미달로 거절됐다. 거절이 확정이고, 매도라면 그 수량은 지금 팔 수 없다. */
    REJECTED_BELOW_MIN_ORDER,

    /** 접수 여부를 모른다. pending 을 두고 identifier 로 확정해야 한다. */
    UNKNOWN_OUTCOME,
}

fun interface OrderErrorClassifier {
    fun classify(error: Throwable): OrderFailure
}
