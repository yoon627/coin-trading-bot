package com.trading.bot.domain

import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MarketPairTest {

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource(
        "UPBIT, KRW-BTC, true",
        // Upbit 에는 1글자 자산이 있다.
        "UPBIT, KRW-T, true",
        "UPBIT, KRW-1INCH, true",
        "UPBIT, USDT-BTC, true",
        "UPBIT, KR-B, true",
        "UPBIT, K-BTC, false",
        "UPBIT, KRW_BTC, false",
        "UPBIT, KRW-BTC-X, false",
        "UPBIT, krw-btc, false",
        "UPBIT, KRW-, false",
        // KRX- 는 KIS 티커 네임스페이스라 Upbit 집합과 겹치지 않는다(trading_states CHECK 와 같은 경계).
        "UPBIT, KRX-005930, false",
        "KIS_PAPER, KRX-005930, true",
        "KIS, KRX-0000J0, true",
        "KIS, KRX-5930, false",
        "KIS, KRW-BTC, false",
    )
    fun `마켓 코드 형식은 거래소마다 정해진 집합이다`(exchange: Exchange, code: String, valid: Boolean) {
        assertThat(MarketPair.isValid(exchange, code)).isEqualTo(valid)
    }
}
