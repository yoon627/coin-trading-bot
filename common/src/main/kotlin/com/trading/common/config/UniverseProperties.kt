package com.trading.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 알트 스윙 유니버스 자동 선정. 기본 off — 켜면 사용자 티커 목록 대신 Upbit 24h 거래대금 상위 [altCount]개를
 * 스윙 대상으로 쓴다(적립 티커·투자유의·페그 자산 제외). 사용자 목록(`bot_state.tickers`)은 건드리지 않는다.
 */
@ConfigurationProperties(prefix = "trading.universe")
data class UniverseProperties(
    val auto: Boolean = false,
    val altCount: Int = 8,
) {
    init {
        // 활성 티커 총수 상한 20(API 입력 검증과 동일) 안에서 적립 4종 + 보유 잔류분의 자리를 남긴다.
        require(altCount in 1..MAX_ALT_COUNT) { "trading.universe.alt-count must be in 1..$MAX_ALT_COUNT, got $altCount" }
    }

    companion object {
        const val MAX_ALT_COUNT = 16
    }
}
