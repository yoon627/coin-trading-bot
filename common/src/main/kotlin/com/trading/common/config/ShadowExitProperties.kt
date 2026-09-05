package com.trading.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 후보 청산 파라미터의 **그림자 관측**. 기본 off — 켜도 라이브 매매는 바뀌지 않는다(계산·기록 전용).
 *
 * 목적은 수익 판정이 아니라 **모델 검증**이다: 백테가 트레일링 체결가로 쓰는 임계선
 * `peak × (1 − trailingStopPct/100)` 이 실제 10초 tick 에서 얼마나 낙관인지를 실물로 잰다.
 * 수익 우위 판정은 현재 거래 빈도로 약 4.7년이 걸리므로 이 관측의 목적이 아니다
 * (wiki `query/trailing-arm-finding-2026-09`).
 *
 * 기본값은 그 페이지가 사전고정으로 통과시킨 변형 A 다.
 */
@ConfigurationProperties(prefix = "trading.shadow-exit")
data class ShadowExitProperties(
    val enabled: Boolean = false,
    val trailingStopPct: Double = 1.5,
    val trailingArmPct: Double = 0.0,
) {
    init {
        // 라이브 게이트와 같은 범위 계약. 0 이하면 트레일링이 즉시 발동하거나 영영 안 한다.
        require(trailingStopPct > 0 && trailingStopPct < 100) {
            "trading.shadow-exit.trailing-stop-pct must be in (0, 100), got $trailingStopPct"
        }
        require(trailingArmPct >= 0) {
            "trading.shadow-exit.trailing-arm-pct must be >= 0, got $trailingArmPct"
        }
    }
}
