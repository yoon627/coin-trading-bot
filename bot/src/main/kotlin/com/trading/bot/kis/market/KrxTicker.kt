package com.trading.bot.kis.market

/** 내부 티커 "KRX-<종목코드>" 와 KIS 종목코드를 오간다. 형식이 틀리면 [IllegalArgumentException] 이다. */
object KrxTicker {
    const val PREFIX = "KRX-"

    // 2024년 신규 발급분부터 영문이 섞이고 7자리 코드(ETN 등)도 받는다. KIS 주문 요청의 종목코드 형식과 같게 둔다.
    private val CODE = Regex("[0-9A-Z]{6,7}")

    fun fromCode(code: String): String {
        require(CODE.matches(code)) { "KRX 종목코드는 영문 대문자·숫자 6~7자여야 한다: $code" }
        return PREFIX + code
    }

    fun toCode(ticker: String): String {
        require(ticker.startsWith(PREFIX)) { "KRX 티커는 $PREFIX 로 시작해야 한다: $ticker" }
        val code = ticker.removePrefix(PREFIX)
        require(CODE.matches(code)) { "KRX 종목코드는 영문 대문자·숫자 6~7자여야 한다: $ticker" }
        return code
    }
}
