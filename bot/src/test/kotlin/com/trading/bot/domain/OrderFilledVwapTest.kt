package com.trading.bot.domain

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 실체결 단가(VWAP) 파싱 — **실행 슬리피지를 재는 유일한 입력**이다.
 *
 * 이 봇은 시장가로 팔고 거래 기록에는 판단 시점 tick 가격을 쓴다. 그 둘의 차이가 실행 슬리피지이고,
 * 백테에는 아예 없는 항목이다(wiki `query/exit-resolution-verdict-2026-09` 한계).
 *
 * Upbit 개별 주문 조회는 최상위 체결금액 합계를 주지 않고 `trades` 배열만 준다(공식 문서 확인 2026-09-05).
 * 그래서 `Σfunds / Σvolume` 으로 만들며, **얻을 수 없으면 추정하지 않고 null** 이다([Order.feeBasis] 와 같은 규율).
 */
class OrderFilledVwapTest {

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Test
    fun `parses trades from a real-shaped order response and averages by funds`() {
        // 부분 체결이 여러 건인 시장가 매도. 단가가 서로 다르므로 단순평균과 VWAP 가 갈린다.
        val json = """
            {"uuid":"u1","side":"ask","ord_type":"market","state":"done","market":"KRW-BTC",
             "executed_volume":"0.003","trades_count":2,"paid_fee":"25.5",
             "trades":[
               {"market":"KRW-BTC","uuid":"t1","price":"100000000","volume":"0.001","funds":"100000","side":"ask"},
               {"market":"KRW-BTC","uuid":"t2","price":"99000000","volume":"0.002","funds":"198000","side":"ask"}
             ]}
        """.trimIndent()
        val order: Order = mapper.readValue(json)

        assertEquals(2, order.trades.size)
        val vwap = order.filledVwap()!!
        // (100,000 + 198,000) / 0.003 = 99,333,333.33… — 단순평균 99,500,000 과 다르다.
        assertTrue(abs(vwap - 298_000.0 / 0.003) < 1e-6) { "VWAP=$vwap" }
    }

    @Test
    fun `returns null when the response carries no trades -접수 직후 응답이 그렇다`() {
        val order: Order = mapper.readValue(
            """{"uuid":"u1","side":"ask","ord_type":"market","state":"wait","market":"KRW-BTC"}""",
        )
        assertNull(order.filledVwap()) { "체결 내역이 없으면 추정하지 않는다" }
    }

    @Test
    fun `refuses non-finite or non-positive values instead of poisoning the metric`() {
        // toDoubleOrNull 은 "NaN"·"Infinity" 를 정상 파싱한다 — 그대로 두면 평균이 영구히 NaN 이 된다.
        for (bad in listOf("NaN", "Infinity", "-100")) {
            val order: Order = mapper.readValue(
                """{"uuid":"u1","market":"KRW-BTC","trades":[
                     {"market":"KRW-BTC","uuid":"t1","price":"1","volume":"1","funds":"$bad","side":"ask"}]}""",
            )
            assertNull(order.filledVwap()) { "funds=$bad 를 값으로 받아들이면 안 된다" }
        }
        val zeroVolume: Order = mapper.readValue(
            """{"uuid":"u1","market":"KRW-BTC","trades":[
                 {"market":"KRW-BTC","uuid":"t1","price":"1","volume":"0","funds":"10","side":"ask"}]}""",
        )
        assertNull(zeroVolume.filledVwap())
    }
}
