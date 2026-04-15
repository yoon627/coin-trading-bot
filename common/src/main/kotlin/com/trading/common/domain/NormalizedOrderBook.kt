package com.trading.common.domain

import java.time.Instant

data class NormalizedOrderBook(
    val exchange: Exchange,
    val market: String,
    val bids: List<OrderBookLevel>,
    val asks: List<OrderBookLevel>,
    val timestamp: Instant = Instant.now(),
) {
    val spread: Double
        get() = if (asks.isNotEmpty() && bids.isNotEmpty()) asks[0].price - bids[0].price else 0.0

    val spreadPercent: Double
        get() = if (asks.isNotEmpty() && asks[0].price > 0) spread / asks[0].price * 100 else 0.0

    val midPrice: Double
        get() = if (asks.isNotEmpty() && bids.isNotEmpty()) (asks[0].price + bids[0].price) / 2.0 else 0.0

    val bidDepth: Double
        get() = bids.sumOf { it.price * it.volume }

    val askDepth: Double
        get() = asks.sumOf { it.price * it.volume }

    val imbalanceRatio: Double
        get() = if (askDepth > 0) bidDepth / (bidDepth + askDepth) else 0.5
}

data class OrderBookLevel(
    val price: Double,
    val volume: Double,
)
