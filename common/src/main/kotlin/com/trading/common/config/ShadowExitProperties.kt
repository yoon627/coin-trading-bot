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
 *
 * 구간은 [ExitParamRanges.SHADOW] 가 정한다. 구간 밖이어도 기동은 막지 않는다 — 관측 전용 설정 때문에 거래 앱과
 * 보유 포지션의 청산 평가가 멈추면 안 된다. 관측기를 만들지 않고 기동·시작 보고가 ERROR 로 알린다(#271).
 */
@ConfigurationProperties(prefix = "trading.shadow-exit")
data class ShadowExitProperties(
    val enabled: Boolean = false,
    val trailingStopPct: Double = 1.5,
    val trailingArmPct: Double = 0.0,
)
