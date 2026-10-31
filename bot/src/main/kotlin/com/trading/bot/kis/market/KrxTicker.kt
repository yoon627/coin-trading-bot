package com.trading.bot.kis.market

import com.trading.common.domain.MarketPair

/** 내부 티커 "KRX-<종목코드>" 와 KIS 종목코드를 오간다. 형식이 틀리면 [IllegalArgumentException] 이다. */
object KrxTicker {
    const val PREFIX = MarketPair.KRX_PREFIX

    // KIS 주문 요청의 종목코드 형식과 같다.
    private val CODE = MarketPair.KRX_CODE

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
