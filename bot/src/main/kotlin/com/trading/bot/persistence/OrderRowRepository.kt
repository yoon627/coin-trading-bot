package com.trading.bot.persistence

import com.trading.bot.persistence.entity.OrderRowEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import java.time.LocalDateTime
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface OrderRowRepository : R2dbcRepository<OrderRowEntity, Long> {

    /**
     * 거래 기록 화면의 한 쪽: 이 사용자·거래소의 체결 줄을 체결 시각 내림차순(같으면 id)으로. 체결 시각이 없는 줄은 맨 뒤다.
     * NULLS LAST 라 색인 순서와 달라 정렬 한 번이 붙지만, 한 사용자·거래소의 체결 줄 안에서다.
     */
    @Query(
        """
        SELECT * FROM orders WHERE user_id = :userId AND exchange = :exchange AND status = 'FILLED'
        ORDER BY completed_at DESC NULLS LAST, id DESC LIMIT :limit OFFSET :offset
        """,
    )
    fun findFilledPage(userId: Long, exchange: String, limit: Int, offset: Int): Flux<OrderRowEntity>

    @Query("SELECT COUNT(*) FROM orders WHERE user_id = :userId AND exchange = :exchange AND status = 'FILLED'")
    fun countFilled(userId: Long, exchange: String): Mono<Long>

    /** 왕복 손익의 원본: 가장 최근 [limit] 개 체결 줄을 오래된 것부터. 잘리면 가장 오래된 쪽이 빠진다. */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM orders WHERE user_id = :userId AND exchange = :exchange AND status = 'FILLED'
            ORDER BY completed_at DESC NULLS LAST, id DESC LIMIT :limit
        ) recent
        ORDER BY completed_at ASC NULLS FIRST, id ASC
        """,
    )
    fun findFilledRecentAscending(userId: Long, exchange: String, limit: Int): Flux<OrderRowEntity>

    /** [orderIds] 중 이 거래소의 체결 줄이 있는 주문번호. 빈 목록은 넘기지 않는다(`IN ()` 은 SQL 오류다). */
    @Query(
        "SELECT exchange_order_id FROM orders " +
            "WHERE user_id = :userId AND exchange = :exchange AND status = 'FILLED' AND exchange_order_id IN (:orderIds)",
    )
    fun findRecordedOrderIds(userId: Long, exchange: String, orderIds: Collection<String>): Flux<String>

    /** 이 사용자·거래소의 거래소 주문번호를 가진 줄(uq_orders_exchange_order 와 같은 키). 상태는 가리지 않는다. */
    @Query("SELECT * FROM orders WHERE user_id = :userId AND exchange = :exchange AND exchange_order_id = :exchangeOrderId")
    fun findByExchangeOrderId(userId: Long, exchange: String, exchangeOrderId: String): Mono<OrderRowEntity>

    /** 이 사용자·거래소의 [clientOrderId] 를 가진 줄(uq_orders_client_order 와 같은 키). 상태는 가리지 않는다. */
    @Query("SELECT * FROM orders WHERE user_id = :userId AND exchange = :exchange AND client_order_id = :clientOrderId")
    fun findByClientOrderId(userId: Long, exchange: String, clientOrderId: String): Mono<OrderRowEntity>

    /**
     * 생애 줄(대기·접수, 또는 끝난 줄)을 체결로 바꾸고 id 를 돌려준다. 체결이 정하는 칼럼은 [insertFilled] 와 같은 값으로 덮어써
     * 화면 값이 넣은 줄과 같다. 판단 가격·요청 수량은 체결이 값을 줄 때만 바꾼다(매수의 판단 가격은 생애 줄에만 있다 — 화면은 읽지 않는다).
     * 요청 금액·식별자·만든 시각·note 는 남긴다. 주문번호는 비었을 때만 채운다. 이미 FILLED 면 비어 끝난다.
     */
    @Query(
        """
        UPDATE orders SET
            exchange_order_id = COALESCE(exchange_order_id, :exchangeOrderId), status = 'FILLED', decision_price = COALESCE(:decisionPrice, decision_price),
            requested_volume = COALESCE(:requestedVolume, requested_volume), filled_volume = :filledVolume, filled_funds = :filledFunds, avg_fill_price = :avgFillPrice,
            fee = :fee, fee_basis = :feeBasis, position_volume_after = :positionVolumeAfter, book_avg_price = :bookAvgPrice,
            pnl_percent = :pnlPercent, pnl_amount = :pnlAmount, reason = :reason, strategy = :strategy, fill_source = :fillSource,
            completed_at = CAST(:recordCreatedAt AS timestamp), updated_at = now()
        WHERE id = :id AND status <> 'FILLED'
        RETURNING id
        """,
    )
    fun fillRow(
        id: Long,
        exchangeOrderId: String?,
        decisionPrice: Double?,
        requestedVolume: Double?,
        filledVolume: Double?,
        filledFunds: Double?,
        avgFillPrice: Double?,
        fee: Double?,
        feeBasis: String,
        positionVolumeAfter: Double?,
        bookAvgPrice: Double?,
        pnlPercent: Double?,
        pnlAmount: Double?,
        reason: String?,
        strategy: String?,
        fillSource: String,
        recordCreatedAt: LocalDateTime,
    ): Mono<Long>

    /**
     * 체결된 주문 한 줄을 넣고 id 를 돌려준다. 체결 기록 트랜잭션 안에서 불리므로 고유 키 충돌이 트랜잭션을 중단하지 않게 대상 없는
     * `ON CONFLICT DO NOTHING` 을 쓴다(모든 부분 고유 인덱스를 덮는다). 같은 키 줄이 이미 있으면 아무것도 넣지 않고 비어 끝난다.
     * 체결 시각은 TradeRecord.createdAt 을 timestamp 로 받아 세션 시간대(= JVM 시간대)로 바꾼다 — 옛 trade_records.created_at 과 같은 값이다.
     */
    @Query(
        """
        INSERT INTO orders (
            user_id, exchange, market, side, client_order_id, exchange_order_id, status, decision_price, requested_volume,
            filled_volume, filled_funds, avg_fill_price, fee, fee_basis, position_volume_after, book_avg_price,
            pnl_percent, pnl_amount, reason, strategy, fill_source, completed_at
        ) VALUES (
            :userId, :exchange, :market, :side, :clientOrderId, :exchangeOrderId, 'FILLED', :decisionPrice, :requestedVolume,
            :filledVolume, :filledFunds, :avgFillPrice, :fee, :feeBasis, :positionVolumeAfter, :bookAvgPrice,
            :pnlPercent, :pnlAmount, :reason, :strategy, :fillSource, CAST(:recordCreatedAt AS timestamp)
        )
        ON CONFLICT DO NOTHING
        RETURNING id
        """,
    )
    fun insertFilled(
        userId: Long,
        exchange: String,
        market: String,
        side: String,
        clientOrderId: String?,
        exchangeOrderId: String?,
        decisionPrice: Double?,
        requestedVolume: Double?,
        filledVolume: Double?,
        filledFunds: Double?,
        avgFillPrice: Double?,
        fee: Double?,
        feeBasis: String,
        positionVolumeAfter: Double?,
        bookAvgPrice: Double?,
        pnlPercent: Double?,
        pnlAmount: Double?,
        reason: String?,
        strategy: String?,
        fillSource: String,
        recordCreatedAt: LocalDateTime,
    ): Mono<Long>
}
