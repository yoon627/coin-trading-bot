package com.trading.common.domain

import java.time.Instant

/** 체결 한 건. [sequentialId] 는 늘기만 하고 연속이 아니어서(실측) 빠진 체결은 알 수 없고 중복만 가린다. */
data class NormalizedTrade(
    val exchange: Exchange,
    val market: String,
    val price: Double,
    val volume: Double,
    val tradeTime: Instant,
    val sequentialId: Long,
)
