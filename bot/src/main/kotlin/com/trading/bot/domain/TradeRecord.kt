package com.trading.bot.domain

import com.trading.common.domain.Exchange
import java.time.LocalDateTime

/** 체결 수수료를 sink 가 어떻게 다룰지. "모른다"를 0.0 으로 쓰면 실제 수수료 0 과 구분되지 않아 타입으로 나눈다. */
sealed interface FeeBasis {
    /** sink 가 `totalAmount` 에 요율을 곱해 추정한다. `totalAmount` 가 실제 체결 대금인 경로에서만 쓴다. */
    data object Estimate : FeeBasis

    /** 거래소가 청구한 실제 수수료(`paid_fee`). */
    data class Measured(val amount: Double) : FeeBasis

    /** 수수료를 알 수 없다. 추정하지 않고 0(미기록)으로 남긴다. */
    data object Unrecorded : FeeBasis
}

/**
 * 체결 이벤트. `trade_records`·`trade_executions` 와 Discord 알림이 함께 쓴다.
 * 매도 시점 평단은 청산과 함께 사라지고 엔진 매수의 `totalAmount` 는 포지션 전체 원가라, 손익과 수수료는 sink 가 유도하지 않고 여기 싣는다.
 * 손익·전략·수수료·주문 대금은 생성부가 빠뜨리지 않도록 기본값을 두지 않는다.
 */
data class TradeRecord(
    val ticker: String,
    val side: TradeSide,
    val price: Double,
    val volume: Double,
    val totalAmount: Double,
    val pnlPercent: Double?,
    // 실현 손익(원). pnlPercent 와 같은 net 기준이고 BUY 는 null 이다.
    val pnlAmount: Double?,
    val strategy: String?,
    val fee: FeeBasis,
    /**
     * 이 주문이 실제로 체결한 대금(수수료 미포함). [totalAmount] 는 엔진 매수에선 포지션 전체 원가, 매도에선 판단가 × 수량이라
     * 이번 주문 금액이 아니다. terminal 주문 응답에서만 값이 있다.
     */
    val orderAmount: Double?,
    val reason: String? = null,
    // 재시작 후 reconcile 이 같은 체결을 두 번 기록하지 않게 하는 키다.
    val exchangeOrderId: String? = null,
    val userId: Long = 0,
    /** 실제 체결 단가. [price] 는 판단 시점 tick 가격이라 둘의 차이가 슬리피지다. 장부·손익은 [price] 로 계산한다. */
    val executedVwap: Double? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val exchange: Exchange = Exchange.UPBIT,
    /** [FeeBasis.Estimate] 일 때 체결 대금에 곱하는 편도 요율. null 이면 sink 가 설정의 왕복 요율 절반을 쓴다. */
    val estimatedFeeRate: Double? = null,
)

enum class TradeSide {
    BUY, SELL
}

enum class SellReason {
    TAKE_PROFIT,
    TRAILING_STOP,
    STOP_LOSS,
    DAILY_RESET,
    MANUAL,
}
