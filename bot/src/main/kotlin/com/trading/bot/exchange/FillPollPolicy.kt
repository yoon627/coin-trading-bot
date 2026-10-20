package com.trading.bot.exchange

/** 주문 직후 체결을 기다리는 조회 횟수와 간격. */
data class FillPollPolicy(val attempts: Int, val delayMs: Long) {
    init {
        require(attempts >= 1) { "attempts must be >= 1: $attempts" }
        require(delayMs >= 0) { "delayMs must be >= 0: $delayMs" }
    }

    companion object {
        val UPBIT = FillPollPolicy(attempts = 10, delayMs = 300)
    }
}
