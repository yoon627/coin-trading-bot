package com.trading.bot.kis.client

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class KisCashOrderRequestTest {

    @Test
    fun `주식 6자리·ETN 7자리 코드를 받고 형식·수량·가격 구분이 틀리면 만들지 않는다`() {
        assertDoesNotThrow { KisCashOrderRequest("005930", KisSide.BUY, KisOrderType.MARKET, qty = 1) }
        assertDoesNotThrow { KisCashOrderRequest("Q500001", KisSide.SELL, KisOrderType.LIMIT, qty = 2, limitPrice = 10_000) }

        listOf(
            { KisCashOrderRequest("05930", KisSide.BUY, KisOrderType.MARKET, qty = 1) },
            { KisCashOrderRequest("q500001", KisSide.BUY, KisOrderType.MARKET, qty = 1) },
            { KisCashOrderRequest("KRX-005930", KisSide.BUY, KisOrderType.MARKET, qty = 1) },
            { KisCashOrderRequest("005930", KisSide.BUY, KisOrderType.MARKET, qty = 0) },
            { KisCashOrderRequest("005930", KisSide.BUY, KisOrderType.MARKET, qty = 1, limitPrice = 70_000) },
            { KisCashOrderRequest("005930", KisSide.BUY, KisOrderType.LIMIT, qty = 1) },
        ).forEach { assertThrows<IllegalArgumentException> { it() } }
    }
}
