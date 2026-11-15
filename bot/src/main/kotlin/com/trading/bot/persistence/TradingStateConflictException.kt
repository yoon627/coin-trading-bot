package com.trading.bot.persistence

/**
 * 메모리의 상태가 마지막으로 읽거나 저장한 뒤 다른 쓰기가 그 행을 바꿨다(#365). 행은 그대로 두고 저장하지 않는다.
 * 메모리가 DB 보다 뒤처졌으므로 같은 상태로 다시 저장해도 계속 실패한다 — 봇을 정지했다가 다시 시작해 DB 에서 다시 읽는다.
 */
class TradingStateConflictException(ticker: String, detail: String, cause: Throwable? = null) :
    RuntimeException("trading_state 저장 충돌 $ticker: $detail", cause)
