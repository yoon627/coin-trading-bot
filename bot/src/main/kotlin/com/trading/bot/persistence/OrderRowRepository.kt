package com.trading.bot.persistence

import com.trading.bot.persistence.entity.OrderRowEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Mono

interface OrderRowRepository : R2dbcRepository<OrderRowEntity, Long> {

    /**
     * 체결된 주문 한 줄을 넣고 id 를 돌려준다. 체결 기록 트랜잭션 안에서 불리므로 고유 키 충돌로 실패하지 않게 대상 없는
     * `ON CONFLICT DO NOTHING` 을 쓴다(모든 부분 고유 인덱스를 덮는다). 같은 키 줄이 이미 있으면 아무것도 넣지 않고 비어 끝난다.
     */
    @Query(
        """
        INSERT INTO orders (
            user_id, exchange, market, side, client_order_id, exchange_order_id, status, decision_price,
            filled_volume, filled_funds, avg_fill_price, fee, fee_basis, position_volume_after, book_avg_price,
            pnl_percent, pnl_amount, reason, strategy, fill_source, trade_execution_id, trade_record_id, completed_at
        ) VALUES (
            :userId, :exchange, :market, :side, :clientOrderId, :exchangeOrderId, 'FILLED', :decisionPrice,
            :filledVolume, :filledFunds, :avgFillPrice, :fee, :feeBasis, :positionVolumeAfter, :bookAvgPrice,
            :pnlPercent, :pnlAmount, :reason, :strategy, :fillSource, :tradeExecutionId, :tradeRecordId, now()
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
        tradeExecutionId: Long,
        tradeRecordId: Long?,
    ): Mono<Long>
}
