package com.trading.bot.domain

/** 기록용 손익 공식. 청산 게이트는 수수료 미차감(gross)으로 판정하고 기록만 net 이다. */
object TradePnl {

    /**
     * 왕복 수수료를 뺀 손익률(%). 평단이나 현재가를 모르면 null 이다.
     * 0 을 돌려주면 수수료만큼의 가짜 손실이 기록된다.
     */
    fun netPercent(currentPrice: Double, basisPrice: Double, roundTripFeeRate: Double): Double? =
        if (basisPrice > 0 && currentPrice > 0) {
            ((currentPrice - basisPrice) / basisPrice) * 100.0 - roundTripFeeRate * 100
        } else {
            null
        }

    /**
     * 실현 손익(원). [netPercent] 와 같은 net 기준이다.
     * 보유 전량이 아니라 매도 수량을 곱해야 부분 매도에서 과대 계상되지 않는다.
     */
    fun amount(pnlPercent: Double?, basisPrice: Double, volume: Double): Double? =
        if (pnlPercent != null && basisPrice > 0 && volume > 0) {
            pnlPercent / 100.0 * basisPrice * volume
        } else {
            null
        }

    /**
     * 설정 요율로 추정한 편도 수수료(원). 실측 `paid_fee` 가 없는 매도에만 쓴다.
     * [totalAmount] 는 그 체결의 대금이어야 한다. 원금 기준인 [amount] 와 기준이 달라 `fee` 와 `pnl_amount` 는 함께 더하지 않는다.
     */
    fun estimatedFee(totalAmount: Double, roundTripFeeRate: Double): Double =
        totalAmount * roundTripFeeRate / 2.0
}
