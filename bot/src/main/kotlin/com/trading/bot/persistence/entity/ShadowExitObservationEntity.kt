package com.trading.bot.persistence.entity

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * 포지션 하나에서 후보 파라미터의 트레일링이 처음 발동한 시점의 기록.
 * [modeledExitPrice] 는 트레일링 임계선, [observedTickPrice] 는 실제로 발동시킨 tick 가격이고 그 차이가 모델의 과대추정폭이다.
 */
@Table("shadow_exit_observation")
data class ShadowExitObservationEntity(
    @Id val id: Long? = null,
    val userId: Long,
    val ticker: String,
    val trailingStopPct: Double,
    val trailingArmPct: Double,
    val entryPrice: Double,
    val peakPrice: Double,
    val modeledExitPrice: Double,
    val observedTickPrice: Double,
    val firedAt: Instant,
    val liveExitPrice: Double? = null,
    // 실제 체결 단가. 판단 시점 가격인 liveExitPrice 와의 차이가 슬리피지이고, 모르면 추정하지 않고 null 로 둔다.
    val liveExitVwap: Double? = null,
    val liveExitReason: String? = null,
    val liveExitAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
)
