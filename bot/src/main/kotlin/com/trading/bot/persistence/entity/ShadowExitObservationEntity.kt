package com.trading.bot.persistence.entity

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * 그림자 청산 관측 1건 — 포지션 하나에서 후보 파라미터의 트레일링이 **처음 발동한** 시점의 기록.
 *
 * [modeledExitPrice] 는 백테가 체결됐다고 보는 값이고 [observedTickPrice] 는 실제로 그 게이트를 발동시킨
 * tick 가격이다. 트레일링은 `drop ≥ trail` 에서 발동하므로 `observed ≤ modeled` 이고, 그 차이가
 * **모델 과대추정폭**이다 — 이 스레드가 무너뜨린 청산 모델의 잔여 오차를 실물로 재는 유일한 양이다.
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
    val liveExitReason: String? = null,
    val liveExitAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
)
