package com.trading.common.domain

enum class TradeMode {
    /** 차트 기반 스윙 트레이딩 (시간봉/일봉 기준, 보유 기간 수시간~수일) */
    SWING,

    /** 호가창 기반 단타 트레이딩 (틱/분봉 기준, 보유 기간 수초~수분) */
    SCALP,
}
