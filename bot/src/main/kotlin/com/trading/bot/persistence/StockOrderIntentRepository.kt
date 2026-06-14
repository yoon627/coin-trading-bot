package com.trading.bot.persistence

import com.trading.bot.persistence.entity.StockOrderIntentEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface StockOrderIntentRepository : R2dbcRepository<StockOrderIntentEntity, Long> {

    fun findByClientRef(clientRef: String): Mono<StockOrderIntentEntity>

    @Query("SELECT * FROM stock_order_intent WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit")
    fun findByUserId(userId: Long, limit: Int): Flux<StockOrderIntentEntity>

    /** (user, exchange, account, symbol) 의 활성(비terminal) 주문 — 신규주문 사전 가드용. */
    @Query(
        """SELECT * FROM stock_order_intent
           WHERE user_id = :userId AND exchange = :exchange AND account_no = :accountNo
                 AND symbol = :symbol AND status IN (:statuses) LIMIT 1""",
    )
    fun findActiveByKey(
        userId: Long,
        exchange: String,
        accountNo: String,
        symbol: String,
        statuses: Collection<String>,
    ): Mono<StockOrderIntentEntity>

    /** reconcile 대상(비terminal) — 오래된 것부터. */
    @Query(
        "SELECT * FROM stock_order_intent WHERE status IN (:statuses) ORDER BY updated_at ASC LIMIT :limit",
    )
    fun findActive(statuses: Collection<String>, limit: Int): Flux<StockOrderIntentEntity>

    /**
     * 조건부 상태 전이 — affected rows 로 경합 검증(낙관락 대용 — @Version 미사용 코드베이스).
     * 반환 0 = 이미 다른 경로가 전이시킴 → 호출부는 멱등하게 skip.
     */
    @Query(
        """UPDATE stock_order_intent
           SET status = :newStatus, odno = COALESCE(:odno, odno), org_no = COALESCE(:orgNo, org_no),
               executed_qty = :executedQty, fail_reason = :failReason, updated_at = NOW()
           WHERE id = :id AND status = :expected""",
    )
    fun transition(
        id: Long,
        expected: String,
        newStatus: String,
        odno: String?,
        orgNo: String?,
        executedQty: Long,
        failReason: String?,
    ): Mono<Long>

    /** 체결 audit 1회 기록 클레임 — 반환 1 = 이번 호출이 audit 책임을 획득(같은 tx 에서 trade_execution 기록). */
    @Query(
        "UPDATE stock_order_intent SET audit_recorded = true, updated_at = NOW() WHERE id = :id AND audit_recorded = false",
    )
    fun claimAudit(id: Long): Mono<Long>
}
