package com.trading.research.domain

import com.trading.common.domain.Exchange

data class Asset(val exchange: Exchange, val market: String) {
    override fun toString(): String = "${exchange.name}:$market"

    companion object {
        fun parse(raw: String): Asset {
            val parts = raw.split(":", limit = 2)
            require(parts.size == 2) { "Asset must be EXCHANGE:MARKET, got '$raw'" }
            return Asset(Exchange.valueOf(parts[0].uppercase()), parts[1])
        }
    }
}
