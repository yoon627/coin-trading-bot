package com.trading.research.domain

data class OrderRequest(
    val asset: Asset,
    val side: OrderSide,
    val sizing: SizingRule,
    val tag: String = "",
)
