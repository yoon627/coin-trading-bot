package com.trading.bot.engine

import com.trading.bot.domain.FeeKind
import com.trading.bot.domain.FillSource
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.persistence.OrderRowRepository
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 봇이 보낸 주문을 `orders` 에 한 줄씩 남긴다. 지금은 체결만 남기고 읽는 곳은 없다. */
@Service
class OrderLedger(
    private val repository: OrderRowRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 체결 한 건을 FILLED 줄로 남기고 그 id 를 돌려준다. [TradeExecutionService.saveAudit] 가 체결 기록 트랜잭션 안에서만 부른다.
     * SQL 오류는 잡지 않는다 — Postgres 는 문장 하나가 실패하면 트랜잭션 전체를 중단하므로 잡아도 뒤의 쓰기가 실패한다.
     * 같은 주문 키의 줄이 이미 있어 넣지 못하면 ERROR 를 남기고 null 을 돌려준다. 체결 기록의 원본이 trade_executions 인 동안은
     * 이 때문에 체결을 막지 않는다.
     */
    suspend fun recordFill(record: TradeRecord, fee: Double?, feeKind: FeeKind, tradeExecutionId: Long, tradeRecordId: Long?): Long? {
        val id = repository.insertFilled(
            userId = record.userId,
            exchange = record.exchange.name,
            market = record.ticker,
            side = record.side.name,
            clientOrderId = record.clientOrderId,
            exchangeOrderId = record.exchangeOrderId,
            // 매수의 price 는 체결 뒤 장부 평단이라 판단 가격이 아니다.
            decisionPrice = record.price.takeIf { record.side == TradeSide.SELL },
            // 잔고로 확정한 매도의 volume 은 주문 시점 수량이다. 이 주문이 체결한 수량(filledVolume)은 모른다.
            requestedVolume = record.volume.takeIf { record.side == TradeSide.SELL && record.fillSource == FillSource.BALANCE },
            filledVolume = record.orderVolume,
            filledFunds = record.orderAmount,
            avgFillPrice = record.executedVwap,
            fee = fee,
            feeBasis = feeKind.name,
            positionVolumeAfter = record.positionVolumeAfter,
            bookAvgPrice = record.bookAvgPrice,
            pnlPercent = record.pnlPercent,
            pnlAmount = record.pnlAmount,
            reason = record.reason,
            strategy = record.strategy,
            fillSource = record.fillSource.name,
            tradeExecutionId = tradeExecutionId,
            tradeRecordId = tradeRecordId,
            // 화면 기록(trade_records.created_at)과 같은 값·같은 반올림으로 남긴다.
            recordCreatedAt = record.createdAt,
        ).awaitSingleOrNull()
        if (id == null) {
            log.error(
                "orders row not written — a row with the same order key exists: userId={}, market={}, side={}, orderId={}, clientOrderId={}",
                record.userId, record.ticker, record.side, record.exchangeOrderId, record.clientOrderId,
            )
        }
        return id
    }
}
