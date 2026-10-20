package com.trading.common.domain

object MarketPair {

    /** 거래소 마켓 코드를 내부 표기("BTC/KRW")로 바꾼다. 이미 내부 표기면 그대로 둔다. */
    fun normalize(exchange: Exchange, rawSymbol: String): String = when (exchange) {
        Exchange.UPBIT -> fromUpbitFormat(rawSymbol)
        // KIS 시세는 수집하지 않아 store 에 내부 표기가 없다.
        Exchange.KIS_PAPER, Exchange.KIS -> rawSymbol
    }

    fun toUpbitFormat(normalized: String): String {
        val (base, quote) = normalized.split("/")
        return "$quote-$base"
    }

    private fun fromUpbitFormat(symbol: String): String {
        val parts = symbol.split("-")
        if (parts.size != 2) return symbol
        return "${parts[1]}/${parts[0]}"
    }
}
