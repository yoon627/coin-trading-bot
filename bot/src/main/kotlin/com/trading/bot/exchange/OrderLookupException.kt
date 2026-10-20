package com.trading.bot.exchange

/** identifier 조회가 "있다/없다" 대신 돌려주는 판정. 그 밖의 예외는 조회 실패로 센다. */
sealed class OrderLookupException(message: String) : RuntimeException(message) {
    /** 거래소에 아직 보이지 않는다. 장애가 아니므로 halt 카운터에 세지 않고 다음 tick 에 다시 본다. */
    class NotYetVisible(message: String = "order not yet visible") : OrderLookupException(message)

    /** 자동으로 확정할 수 없어 사람이 봐야 한다. */
    class NeedsReview(val reason: String) : OrderLookupException(reason)
}
