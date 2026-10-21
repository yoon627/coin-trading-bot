package com.trading.bot.kis.client

import java.math.BigDecimal
import java.time.LocalDate

/** 현재가 조회 결과. 장 시작 전에는 당일 시가·고가·저가가 0 일 수 있다. */
data class KisQuote(
    val code: String,
    val price: Long,
    val open: Long,
    val high: Long,
    val low: Long,
)

data class KisDailyCandle(
    val date: LocalDate,
    val open: Long,
    val high: Long,
    val low: Long,
    val close: Long,
    val volume: Long,
)

/** 잔고 한 종목. 당일 전량 매도한 종목도 보유수량 0 으로 온다. */
data class KisHolding(
    val code: String,
    val name: String?,
    val holdingQty: Long,
    // 매도 주문에 쓸 수 있는 수량. 매도 주문이 걸려 있으면 보유수량보다 작다.
    val orderableQty: Long,
    val avgPrice: BigDecimal,
)

data class KisAccountSummary(
    val depositTotal: Long,
    // 익일정산금액(D+1).
    val nextDaySettlement: Long,
    // 가수도정산금액(D+2).
    val d2Settlement: Long,
    val totalEvaluation: Long,
)

data class KisBalance(
    val holdings: List<KisHolding>,
    val summary: KisAccountSummary,
    val pages: Int,
)

/** 매수가능조회 결과. 미수를 쓰지 않으므로 [noReceivableBuyQty] 가 매수 수량의 상한이다. */
data class KisBuyable(
    val orderableCash: Long,
    val noReceivableBuyAmount: Long,
    val noReceivableBuyQty: Long,
    val maxBuyAmount: Long,
    val maxBuyQty: Long,
)

enum class KisSide(internal val code: String) {
    // 주문 체결 조회의 sll_buy_dvsn_cd.
    SELL("01"),
    BUY("02"),
}

enum class KisOrderType(internal val ordDvsn: String) {
    LIMIT("00"),
    MARKET("01"),
}

data class KisCashOrderRequest(
    val code: String,
    val side: KisSide,
    val type: KisOrderType,
    val qty: Long,
    // 지정가만 쓴다. 시장가는 null.
    val limitPrice: Long? = null,
) {
    init {
        require(CODE.matches(code)) { "종목코드는 6자리(ETN 은 7자리) 영문 대문자·숫자여야 한다: $code" }
        require(qty > 0) { "주문 수량은 1 이상이어야 한다: $qty" }
        when (type) {
            KisOrderType.LIMIT -> require(limitPrice != null && limitPrice > 0) { "지정가 주문에는 0 보다 큰 가격이 필요하다" }
            KisOrderType.MARKET -> require(limitPrice == null) { "시장가 주문에는 가격을 넣지 않는다" }
        }
    }

    private companion object {
        // 공식 샘플: 종목코드 6자리, ETN 은 7자리. 영문이 섞인 코드도 막지 않고 KIS 가 판정하게 둔다.
        val CODE = Regex("[0-9A-Z]{6,7}")
    }
}

/** 주문 접수 응답. KIS 는 client order id 를 받지 않아 ODNO 가 유일한 식별자다. */
data class KisOrderAck(
    val odno: String,
    val orgNo: String?,
    // HHmmss (KST).
    val orderTime: String?,
)

/** 일별 주문·체결 조회 한 행. 정정·취소 주문은 [originalOdno] 가 원주문을 가리킨다. */
data class KisOrderRow(
    val orderDate: LocalDate,
    val odno: String,
    val originalOdno: String?,
    val side: KisSide,
    val code: String,
    val orderQty: Long,
    val orderPrice: BigDecimal?,
    // HHmmss (KST).
    val orderTime: String?,
    val filledQty: Long,
    val avgPrice: BigDecimal,
    val filledAmount: Long?,
    val remainingQty: Long,
    val rejectedQty: Long?,
    val cancelConfirmedQty: Long?,
    val cancelled: Boolean,
    val orderDivisionCode: String?,
)

data class KisHolidayRow(
    val date: LocalDate,
    val weekdayCode: String?,
    val businessDay: Boolean,
    val tradingDay: Boolean,
    // 주문을 넣을 수 있는 날인지는 이 값으로 본다.
    val openDay: Boolean,
    val settlementDay: Boolean,
)
