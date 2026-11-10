package com.trading.bot.notification

import com.trading.bot.persistence.entity.UserEntity

/** 체결 알림에 싣는 원화 잔고. 거래소 키 복호화와 클라이언트 생성은 엔진 쪽 구현이 맡는다. 모르면 null. */
fun interface TradeBalanceLookup {
    suspend fun krwBalance(user: UserEntity, exchange: String): Double?
}
