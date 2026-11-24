package com.trading.bot.engine

import com.trading.bot.domain.FeeKind
import com.trading.bot.domain.FillSource
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.persistence.OrderRowRepository
import com.trading.bot.persistence.entity.OrderRowEntity
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 봇이 보낸 주문을 `orders` 에 한 줄씩 남긴다. 체결 기록은 이 줄뿐이고, 거래 기록 화면·왕복 손익·매일 대조가 FILLED 줄을 읽는다. */
@Service
class OrderLedger(
    private val repository: OrderRowRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 체결 기록의 결과. */
    sealed interface FillRecord {
        /** 줄을 넣었거나 그 주문의 생애 줄을 FILLED 로 바꿨다. */
        data class Recorded(val orderRowId: Long) : FillRecord

        /** 같은 주문의 FILLED 줄이 이미 있다(재시작 뒤 같은 주문을 다시 확정할 때). */
        data object AlreadyRecorded : FillRecord
    }

    /**
     * 체결 한 건을 orders 에 남긴다. 그 주문의 줄(생애 줄)이 있으면 FILLED 로 바꾸고, 없으면 넣는다. [TradeExecutionService.saveAudit] 가
     * 체결 기록 트랜잭션 안에서만 부른다. SQL 오류는 잡지 않는다 — Postgres 는 문장 하나가 실패하면 트랜잭션 전체를 중단한다.
     * 넣거나 바꾸지 못하면(예상 밖의 충돌) 예외로 되돌린다 — 체결 기록은 이 줄뿐이다.
     */
    suspend fun recordFill(record: TradeRecord, fee: Double?, feeKind: FeeKind): FillRecord {
        // 매수의 price 는 체결 뒤 장부 평단이라 판단 가격이 아니다.
        val decisionPrice = record.price.takeIf { record.side == TradeSide.SELL }
        // 잔고로 확정한 매도의 volume 은 주문 시점 수량이다. 이 주문이 체결한 수량(filledVolume)은 모른다.
        val requestedVolume = record.volume.takeIf { record.side == TradeSide.SELL && record.fillSource == FillSource.BALANCE }
        val (row, clientOrderIdTaken) = rowOf(record)
        if (row != null) {
            if (row.status == FILLED) return FillRecord.AlreadyRecorded
            if (row.status !in LIVE) {
                // 엔진은 끝난 주문을 다시 확정하지 않는다. 생기면 그 주문의 장부가 이미 어긋났다.
                log.error(
                    "a fill arrived for an order recorded as {} — recording it as filled; check the bot book: " +
                        "userId={}, exchange={}, market={}, side={}, orderId={}",
                    row.status, record.userId, record.exchange, record.ticker, record.side, record.exchangeOrderId,
                )
            }
            val id = repository.fillRow(
                id = checkNotNull(row.id),
                exchangeOrderId = record.exchangeOrderId,
                decisionPrice = decisionPrice,
                requestedVolume = requestedVolume,
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
                recordCreatedAt = record.createdAt,
            ).awaitSingleOrNull()
            return FillRecord.Recorded(checkNotNull(id) { notWritten(record) })
        }
        // 식별자는 같은데 주문번호가 다른 줄이 있다 — 다른 주문이라 덮지도 건너뛰지도 않는다. 되돌리면 그 종목이 확정에서 멈춰
        // 손절이 돌지 않으므로 그 식별자 없이 남긴다.
        if (clientOrderIdTaken) {
            log.error(
                "orders already has a row with this client order id for another order number — recording the fill without it: " +
                    "userId={}, exchange={}, market={}, side={}, orderId={}, clientOrderId={}",
                record.userId, record.exchange, record.ticker, record.side, record.exchangeOrderId, record.clientOrderId,
            )
        }
        val clientOrderId = record.clientOrderId.takeUnless { clientOrderIdTaken }
        val id = repository.insertFilled(
            userId = record.userId,
            exchange = record.exchange.name,
            market = record.ticker,
            side = record.side.name,
            clientOrderId = clientOrderId,
            exchangeOrderId = record.exchangeOrderId,
            decisionPrice = decisionPrice,
            requestedVolume = requestedVolume,
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
            recordCreatedAt = record.createdAt,
        ).awaitSingleOrNull()
        return FillRecord.Recorded(checkNotNull(id) { notWritten(record) })
    }

    /** 이 체결의 주문 줄과, 그 식별자를 다른 주문(주문번호가 다른 줄)이 이미 쓰는지. */
    private data class Lookup(val row: OrderRowEntity?, val clientOrderIdTaken: Boolean)

    /**
     * 이 체결의 주문 줄: 같은 사용자·거래소의 주문번호, 없으면 식별자(ctb- 는 주문마다 새로 만든다). 식별자로 찾은 줄은 주문번호가
     * 비었거나 같을 때만 이 주문이다.
     */
    private suspend fun rowOf(record: TradeRecord): Lookup {
        record.exchangeOrderId?.let { orderId ->
            repository.findByExchangeOrderId(record.userId, record.exchange.name, orderId).awaitSingleOrNull()?.let { return Lookup(it, false) }
        }
        val clientOrderId = record.clientOrderId ?: return Lookup(null, false)
        val byClient = repository.findByClientOrderId(record.userId, record.exchange.name, clientOrderId).awaitSingleOrNull()
            ?: return Lookup(null, false)
        return if (byClient.exchangeOrderId == null || byClient.exchangeOrderId == record.exchangeOrderId) Lookup(byClient, false) else Lookup(null, true)
    }

    private fun notWritten(record: TradeRecord) =
        "orders row not written — a row with the same order key appeared: userId=${record.userId}, exchange=${record.exchange}, " +
            "market=${record.ticker}, side=${record.side}, orderId=${record.exchangeOrderId}"

    private companion object {
        const val FILLED = "FILLED"

        // 아직 끝나지 않은 주문의 상태. 체결이 이 줄을 바꾸는 것이 보통의 길이다.
        val LIVE = setOf("PENDING", "OPEN")
    }
}
