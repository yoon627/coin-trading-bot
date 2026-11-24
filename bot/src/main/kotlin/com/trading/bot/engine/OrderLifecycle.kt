package com.trading.bot.engine

import com.trading.bot.domain.TradeSide
import com.trading.common.domain.Exchange

/**
 * 체결이 아닌 주문 생애(대기·접수·체결 없는 끝)를 orders 에 남긴다. 최선이다 — 실패하거나 늦어도 매매 흐름을 바꾸지 않는다.
 * 체결은 [OrderLedger.recordFill] 이 같은 줄을 FILLED 로 바꾼다. 상태는 앞으로만 간다(끝난 줄을 되돌리지 않는다).
 */
interface OrderLifecycle {
    /** 매수를 보내기 전, 상태 저장이 끝난 뒤. 매도는 손절 앞에 DB 를 기다리지 않게 쓰지 않는다. */
    suspend fun pending(order: LifecycleOrder)

    /** 거래소가 받았다([LifecycleOrder.exchangeOrderId]). 대기 줄이 있으면 주문번호를 잇고, 없으면 넣는다. */
    suspend fun open(order: LifecycleOrder)

    /** 체결 없이 끝났다. 대기·접수 줄이면 바꾸고, 줄이 없으면 끝난 줄로 넣는다. 이미 끝났거나 체결된 줄은 그대로 둔다. */
    suspend fun closed(order: LifecycleOrder, outcome: Outcome, exchangeState: String? = null, note: String? = null)

    enum class Outcome { CANCELLED, REJECTED, NOT_PLACED }

    companion object {
        /** 생애를 남기지 않는다(시험). */
        val NONE: OrderLifecycle = object : OrderLifecycle {
            override suspend fun pending(order: LifecycleOrder) = Unit
            override suspend fun open(order: LifecycleOrder) = Unit
            override suspend fun closed(order: LifecycleOrder, outcome: Outcome, exchangeState: String?, note: String?) = Unit
        }
    }
}

/** 생애 줄 하나에 남길 주문 값. 식별자(ctb-)와 주문번호 중 아는 것으로 줄을 찾는다. */
data class LifecycleOrder(
    val userId: Long,
    val exchange: Exchange,
    val market: String,
    val side: TradeSide,
    val clientOrderId: String?,
    val exchangeOrderId: String? = null,
    val decisionPrice: Double? = null,
    val requestedFunds: Double? = null,
    val requestedVolume: Double? = null,
)
