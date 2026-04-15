package com.trading.common.event

import com.trading.common.domain.Exchange
import java.time.Instant

data class MarketDataEvent(
    val eventType: EventType,
    val exchange: Exchange,
    val market: String,
    val timestamp: Instant = Instant.now(),
    val payload: String,
)

enum class EventType {
    TICKER,
    CANDLE,
    ORDER_BOOK,
    TRADE,
}
