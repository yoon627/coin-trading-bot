package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 사람이 상태를 바꾼 기록 한 건. [detail] 은 [action] 별 JSON 이다. */
@Table("manual_interventions")
data class ManualInterventionEntity(
    @Id val id: Long? = null,
    val userId: Long,
    val exchange: String,
    val ticker: String,
    val action: String,
    val detail: String,
    val createdAt: Instant,
) {
    companion object {
        const val PENDING_CLEAR = "PENDING_CLEAR"
        const val HALT_CLEAR = "HALT_CLEAR"
    }
}
