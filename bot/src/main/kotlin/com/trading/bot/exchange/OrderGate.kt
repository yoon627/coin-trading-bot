package com.trading.bot.exchange

import com.trading.bot.domain.TradeSide
import java.time.Instant

/** 주문을 보내도 되는 때인지. pending·시도 기록을 만들기 전에 묻는다. */
interface OrderGate {
    fun mayPlace(ticker: String, side: TradeSide, now: Instant): Boolean

    /** 거래소가 주문을 확정적으로 거절했다. */
    fun onDefinitiveRejection(ticker: String, side: TradeSide, now: Instant)

    companion object {
        /** 늘 허용하고 거절에 반응하지 않는다. */
        val OPEN: OrderGate = object : OrderGate {
            override fun mayPlace(ticker: String, side: TradeSide, now: Instant): Boolean = true

            override fun onDefinitiveRejection(ticker: String, side: TradeSide, now: Instant) = Unit
        }
    }
}
