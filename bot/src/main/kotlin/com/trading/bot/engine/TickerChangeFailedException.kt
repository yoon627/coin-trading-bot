package com.trading.bot.engine

/**
 * 도는 봇의 감시 목록을 바꾸지 못했다(옛 엔진이 옛 목록으로 돌아감, 또는 종료가 끼어들어 저장만 함). 일시 장애라 503 이다.
 * 키·웹훅 저장의 [RuntimeReloadFailedException] 과 문구가 다르다 — [message] 가 실제 결과를 말한다.
 */
class TickerChangeFailedException(
    val userId: Long,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
