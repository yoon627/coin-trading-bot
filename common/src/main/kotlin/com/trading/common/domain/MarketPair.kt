package com.trading.common.domain

object MarketPair {

    /** 거래소 마켓 코드를 내부 표기("BTC/KRW")로 바꾼다. 이미 내부 표기면 그대로 둔다. */
    fun normalize(exchange: Exchange, rawSymbol: String): String = when (exchange) {
        Exchange.UPBIT -> fromUpbitFormat(rawSymbol)
        // KIS 시세는 수집하지 않아 store 에 내부 표기가 없다.
        Exchange.KIS_PAPER, Exchange.KIS -> rawSymbol
    }

    /** KIS 종목의 내부 티커 접두어. Upbit 마켓 코드 집합에서 빼 두 집합이 겹치지 않는다(trading_states CHECK 와 같은 경계). */
    const val KRX_PREFIX = "KRX-"

    /** KRX 종목코드. 2024년 신규 발급분부터 영문이 섞이고 7자리 코드(ETN 등)도 있다. */
    val KRX_CODE = Regex("[0-9A-Z]{6,7}")

    // Upbit 에는 KRW-T 같은 1글자 자산이 있다.
    private val UPBIT_CODE = Regex("[A-Z]{2,10}-[A-Z0-9]{1,20}")

    /** 거래소별 내부 마켓 코드 형식. 대문자로 정규화한 값을 받는다. 배포 preflight 의 MARKET_FORMAT 이 Upbit 규칙의 사본이다. */
    fun isValid(exchange: Exchange, code: String): Boolean = when (exchange) {
        Exchange.UPBIT -> UPBIT_CODE.matches(code) && !code.startsWith(KRX_PREFIX)
        Exchange.KIS_PAPER, Exchange.KIS -> code.startsWith(KRX_PREFIX) && KRX_CODE.matches(code.removePrefix(KRX_PREFIX))
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
