package com.trading.common.domain

object MarketPair {

    /**
     * Normalizes exchange-specific market symbols to a unified format: "BTC/KRW", "ETH/USDT"
     */
    fun normalize(exchange: Exchange, rawSymbol: String): String = when (exchange) {
        Exchange.UPBIT -> fromUpbitFormat(rawSymbol)
        Exchange.BINANCE -> fromBinanceFormat(rawSymbol)
        else -> rawSymbol
    }

    fun toUpbitFormat(normalized: String): String {
        val (base, quote) = normalized.split("/")
        return "$quote-$base"
    }

    fun toBinanceFormat(normalized: String): String {
        val (base, quote) = normalized.split("/")
        return "$base$quote"
    }

    private fun fromUpbitFormat(symbol: String): String {
        val parts = symbol.split("-")
        if (parts.size != 2) return symbol
        return "${parts[1]}/${parts[0]}"
    }

    private fun fromBinanceFormat(symbol: String): String {
        val knownQuotes = listOf("USDT", "BUSD", "BTC", "ETH", "BNB", "KRW")
        for (quote in knownQuotes) {
            if (symbol.endsWith(quote) && symbol.length > quote.length) {
                val base = symbol.removeSuffix(quote)
                return "$base/$quote"
            }
        }
        return symbol
    }
}
