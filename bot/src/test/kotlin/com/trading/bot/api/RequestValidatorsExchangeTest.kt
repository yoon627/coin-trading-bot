package com.trading.bot.api

import com.trading.common.domain.Exchange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class RequestValidatorsExchangeTest {
    private val validators = RequestValidators()

    private fun assertBadRequest(block: () -> Any?) {
        val ex = assertThrows<ResponseStatusException> { block() }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `exchange 가 없거나 비면 UPBIT 이고 enum 이름은 그대로 받는다`() {
        assertEquals(Exchange.UPBIT, validators.parseExchange(null))
        assertEquals(Exchange.UPBIT, validators.parseExchange(""))
        assertEquals(Exchange.UPBIT, validators.parseExchange("UPBIT"))
        assertEquals(Exchange.KIS_PAPER, validators.parseExchange("KIS_PAPER"))
        assertEquals(Exchange.KIS, validators.parseExchange("KIS"))
    }

    @Test
    fun `대소문자·공백이 다르거나 모르는 exchange 는 400 이다`() {
        listOf("upbit", "Upbit", "kis_paper", " UPBIT", "UPBIT ", "KIS-PAPER", "BINANCE", " ").forEach { raw ->
            assertBadRequest { validators.parseExchange(raw) }
        }
    }

    @Test
    fun `KIS 티커는 KRX- 와 영문 대문자·숫자 6~7자다`() {
        listOf(Exchange.KIS_PAPER, Exchange.KIS).forEach { exchange ->
            assertEquals(
                listOf("KRX-005930", "KRX-0059A0", "KRX-5700001"),
                validators.normalizeMarkets(exchange, listOf(" krx-005930 ", "KRX-0059A0", "KRX-5700001", "KRX-005930")),
            )
            listOf("KRW-BTC", "KRX-00593", "KRX-00593000", "KRX-0059-0", "KRX005930", "XKRX-005930").forEach { bad ->
                assertBadRequest { validators.normalizeMarkets(exchange, listOf(bad)) }
            }
            assertBadRequest { validators.normalizeMarkets(exchange, emptyList()) }
            assertBadRequest { validators.normalizeMarkets(exchange, (100000..100020).map { "KRX-$it" }) }
        }
    }

    @Test
    fun `UPBIT 티커 규칙은 기존 함수와 같다`() {
        val valid = listOf(" krw-btc ", "KRW-ETH", "BTC-XRP", "KRW-BTC")
        assertEquals(validators.normalizeMarkets(valid), validators.normalizeMarkets(Exchange.UPBIT, valid))
        assertEquals(listOf("KRW-BTC", "KRW-ETH", "BTC-XRP"), validators.normalizeMarkets(Exchange.UPBIT, valid))
        // KRX- 는 KIS 티커 네임스페이스라 Upbit 집합에서 뺀다.
        listOf(listOf("KRWBTC"), listOf("K-BTC"), listOf("KRX-005930"), emptyList(), (1..21).map { "KRW-C$it" }).forEach { bad ->
            assertBadRequest { validators.normalizeMarkets(bad) }
            assertBadRequest { validators.normalizeMarkets(Exchange.UPBIT, bad) }
        }
    }
}
