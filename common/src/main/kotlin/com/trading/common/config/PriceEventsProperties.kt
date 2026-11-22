package com.trading.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 체결 이벤트 스위치. 끄면 시세 수집이 업비트 `trade` 를 구독하지 않고 엔진도 체결을 받지 않아 바퀴만 판단한다 — 문제가 생겼을 때 env 로 끄는 비상용이다. */
@ConfigurationProperties(prefix = "trading.price-events")
data class PriceEventsProperties(
    val enabled: Boolean = true,
)
