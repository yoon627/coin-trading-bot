package com.trading.research.execution

import com.trading.research.domain.OrderRequest

class OrderBook {
    private val pending = mutableListOf<OrderRequest>()

    fun submit(order: OrderRequest) {
        pending.add(order)
    }

    fun submitAll(orders: List<OrderRequest>) {
        pending.addAll(orders)
    }

    fun drain(): List<OrderRequest> {
        val out = pending.toList()
        pending.clear()
        return out
    }
}
