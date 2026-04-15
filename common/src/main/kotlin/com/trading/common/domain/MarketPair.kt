package com.trading.common.domain

object MarketPair {

    /**
     * Normalizes exchange-specific market symbols to a unified format: "BTC/KRW", "ETH/USDT"
     */
    fun normalize(exchange: Exchange, rawSymbol: String): String = when (exchange) {
        Exchange.UPBIT -> fromUpbitFormat(rawSymbol)
        Exchange.BINANCE -> fromBinanceFormat(rawSymbol)
        Exchange.KIS -> fromKisFormat(rawSymbol)
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

    /**
     * KIS format: "AAPL" (ticker only) or "NAS:AAPL" (exchange:ticker)
     * Normalized: "AAPL/USD"
     */
    fun toKisFormat(normalized: String): String {
        val (base, _) = normalized.split("/")
        return base
    }

    fun toKisExchangeCode(normalized: String): String {
        // Default to NASDAQ; caller can override based on actual exchange
        return "NAS"
    }

    private fun fromKisFormat(symbol: String): String {
        // "NAS:AAPL" → "AAPL/USD", "AAPL" → "AAPL/USD"
        val ticker = if (symbol.contains(":")) symbol.substringAfter(":") else symbol
        return "$ticker/USD"
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
