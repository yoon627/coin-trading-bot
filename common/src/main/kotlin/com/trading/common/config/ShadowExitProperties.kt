package com.trading.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 후보 청산 파라미터의 **그림자 관측**. 기본 off — 켜도 라이브 매매는 바뀌지 않는다(계산·기록 전용).
 *
 * 목적은 수익 판정이 아니라 **모델 검증**이다: 트레일링 체결가를 임계선 `peak × (1 − trailingStopPct/100)` 으로
 * 잡는 모델이 실제 10초 tick 에서 얼마나 낙관인지를 실물로 잰다.
 * 수익 우위 판정은 현재 거래 빈도로 약 4.7년이 걸리므로 이 관측의 목적이 아니다.
 *
 * 기본값(트레일링 1.5·arm 0)은 사전고정한 후보(변형 A)다.
 */
@ConfigurationProperties(prefix = "trading.shadow-exit")
data class ShadowExitProperties(
    val enabled: Boolean = false,
    val trailingStopPct: Double = 1.5,
    val trailingArmPct: Double = 0.0,
) {
    init {
        // 구간은 ExitParamRanges 가 정한다. 라이브는 구간 밖이어도 알림만 내지만 그림자는 관측 전용이라 기동을 막는다 —
        // 배포 preflight 가 같은 표로 먼저 거르므로 여기 걸리는 건 서버 .env 를 손으로 고친 경우다.
        ExitParamRanges.SHADOW.forEach { entry ->
            val violation = entry.violation(this)
            require(violation == null) { "$violation" }
        }
    }
}
