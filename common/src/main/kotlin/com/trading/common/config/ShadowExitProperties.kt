package com.trading.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 후보 청산 파라미터를 라이브 매매에 영향 없이 계산·기록만 하는 그림자 관측 설정. 기본은 꺼져 있다.
 * 값이 [ExitParamRanges.SHADOW] 밖이면 기동은 막지 않고 관측기만 만들지 않는다.
 */
@ConfigurationProperties(prefix = "trading.shadow-exit")
data class ShadowExitProperties(
    val enabled: Boolean = false,
    val trailingStopPct: Double = 1.5,
    val trailingArmPct: Double = 0.0,
)
