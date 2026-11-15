package com.trading.bot.exchange

/** 주문 요청이 예외로 끝났을 때 그 주문의 처지. */
enum class OrderFailure {
    /** 거래소가 주문을 만들지 않았음이 확정이다. */
    REJECTED,

    /** 최소주문 금액 미달로 거절됐다. 거절이 확정이고, 매도라면 그 수량은 지금 팔 수 없다. */
    REJECTED_BELOW_MIN_ORDER,

    /** 접수 여부를 모른다. pending 을 두고 identifier 로 확정해야 한다. */
    UNKNOWN_OUTCOME,

    /**
     * 일시 장애(응답 시한 초과·연결 끊김·5xx·요청 수 제한)로 접수 여부를 모른다. 거래소가 identifier 중복을 거절하므로
     * 같은 identifier 로 다시 보내도 주문은 하나다.
     */
    UNKNOWN_TRANSIENT,

    /** 그 identifier 의 주문이 이미 있다. 앞서 보낸 요청이 접수됐다는 뜻이라 identifier 로 조회해 이어받는다. */
    DUPLICATE_IDENTIFIER,

    /** 연결이 서기 전에 실패해 요청이 거래소에 닿지 않았다. identifier 가 쓰이지 않아 같은 identifier 로 다시 보내도 주문은 하나다. */
    NOT_SENT,
    ;

    /** 주문이 만들어지지 않았음이 확정일 때만 true 다. 그 밖에는 pending 을 지우면 안 된다. */
    val isRejection: Boolean get() = this == REJECTED || this == REJECTED_BELOW_MIN_ORDER
}

fun interface OrderErrorClassifier {
    fun classify(error: Throwable): OrderFailure
}
