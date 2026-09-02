package com.trading.bot.domain

/**
 * 기록용 손익 공식 — 엔진 매도와 수동 매도가 같은 기준을 쓰도록 한 곳에 모은다.
 *
 * 청산 게이트는 gross(수수료 미차감)로 판정하고 기록만 net 이다. 게이트를 net 으로 바꾸면 행동이 달라진다.
 */
object TradePnl {

    /**
     * 왕복 수수료를 차감한 손익률(%). 백테스트의 `feeRate × 2` 와 같은 기준이다.
     *
     * 평단이나 현재가를 모르면 null — 0 을 돌려주면 수수료만큼의 가짜 손실(-0.1%)이 기록에 남는다.
     * 외부 입금분을 `syncPosition` 으로 복원한 포지션에서 실제로 발생한다.
     */
    fun netPercent(currentPrice: Double, basisPrice: Double, roundTripFeeRate: Double): Double? =
        if (basisPrice > 0 && currentPrice > 0) {
            ((currentPrice - basisPrice) / basisPrice) * 100.0 - roundTripFeeRate * 100
        } else {
            null
        }

    /**
     * 실현 손익(원). [netPercent] 와 같은 net 기준이라 두 값을 함께 합산해도 어긋나지 않는다.
     *
     * `basisPrice × volume` 을 원금으로 쓰므로 부분 매도가 여러 행을 남겨도 행별 손익이 맞는다.
     * 매도 수량이 아닌 보유 전량을 곱하면 부분 매도에서 과대 계상된다.
     */
    fun amount(pnlPercent: Double?, basisPrice: Double, volume: Double): Double? =
        if (pnlPercent != null && basisPrice > 0 && volume > 0) {
            pnlPercent / 100.0 * basisPrice * volume
        } else {
            null
        }

    /**
     * 편도 수수료(원) — 왕복 비율의 절반. 설정값 기반 **추정**이다.
     *
     * 실측이 있으면 이걸 쓰지 않는다 — 엔진 매수는 주문 응답의 `paid_fee` 를 그대로 싣는다
     * ([FeeBasis.Measured]).
     *
     * 현재 이 함수를 쓰는 경로는 수동 주문과 **모든 매도**다. 수동 주문은 `placeOrder` 즉시 응답만
     * 있어 실측이 불가능하지만, **엔진 매도는 `awaitFill` 로 주문 응답을 쥐고도 쓰지 않는다** —
     * 매수만큼 급하지 않아 이번 범위에서 뺀 것이지 얻을 수 없어서가 아니다(#133 후속).
     *
     * ⚠️ [totalAmount] 는 **그 체결의 대금**이어야 한다. 포지션 전체 원가를 넘기면 그만큼 부풀려진다 —
     * 엔진 매수가 정확히 그래서 실측으로 갈아탔다(#133).
     *
     * [amount] 가 차감하는 수수료와 기준이 다르다 — 이쪽은 **체결 대금**에, 저쪽은 백테와 맞춘 **원금**에
     * 비율을 곱한다. 두 컬럼을 한 리포트에서 더하면 그만큼 어긋난다(이슈 #148).
     */
    fun estimatedFee(totalAmount: Double, roundTripFeeRate: Double): Double =
        totalAmount * roundTripFeeRate / 2.0
}
