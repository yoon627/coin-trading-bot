package com.trading.common.domain

/**
 * OHLC 가격 추상화. 차트 표시(NormalizedCandle)와 매매 판단(Candle)이 동일한 지표 계산을
 * 공유하기 위한 공통 인터페이스. Indicators 가 이 타입에만 의존해 도메인 타입별 중복 구현을 제거한다.
 */
interface Ohlc {
    val open: Double
    val high: Double
    val low: Double
    val close: Double
}
