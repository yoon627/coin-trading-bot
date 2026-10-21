package com.trading.bot.kis.market

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KrxTickerTest {

    @Test
    fun `종목코드와 KRX- 티커를 오가고 영문이 섞인 코드와 7자리 코드도 받는다`() {
        listOf("005930", "0001A0", "Q500001").forEach { code ->
            assertThat(KrxTicker.fromCode(code)).isEqualTo("KRX-$code")
            assertThat(KrxTicker.toCode("KRX-$code")).isEqualTo(code)
        }
    }

    @Test
    fun `형식이 아니면 IllegalArgumentException 이다`() {
        listOf("05930", "00593000", "005930 ", "00593a", "").forEach { code ->
            assertThatThrownBy { KrxTicker.fromCode(code) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        listOf("005930", "KRW-BTC", "krx-005930", "KRX-", "KRX-05930", "KRX-00593a").forEach { ticker ->
            assertThatThrownBy { KrxTicker.toCode(ticker) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
