package com.trading.bot.domain

/**
 * 접수된 주문의 체결을 확인한 결과. 매수 경로는 아직 체결을 조회하지 않아 결과에 실리지 않는다(응답에 `fill` 키 없음).
 * NOT_FILLED 는 terminal 인데 체결이 0 인 확정된 사실이고, UNCONFIRMED 는 모른다(wait 잔존·조회 실패·파싱 실패·대금 미상).
 */
enum class FillOutcome { CONFIRMED, NOT_FILLED, UNCONFIRMED }
