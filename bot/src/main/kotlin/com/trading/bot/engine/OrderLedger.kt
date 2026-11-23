package com.trading.bot.engine

import com.trading.bot.domain.FeeKind
import com.trading.bot.domain.FillSource
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.persistence.OrderRowRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 봇이 보낸 주문을 `orders` 에 한 줄씩 남긴다. 지금은 체결만 남긴다. 체결 기록은 이 줄뿐이고, 거래 기록 화면·왕복 손익·매일 대조가 이 줄을 읽는다. */
@Service
class OrderLedger(
    private val repository: OrderRowRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이 체결이 이미 기록됐는가 — 같은 사용자·거래소·거래소 주문번호의 FILLED 줄이 있으면 그렇다(재시작 뒤 같은 주문을 다시 확정할 때).
     * 같은 주문번호의 FILLED 아닌 줄은 지금 생기지 않는다. 생겼다면 그 줄을 덮을 규칙이 없으므로 예외로 체결 기록을 되돌린다
     * (주문 생애를 남기게 되면 이 자리는 그 줄을 FILLED 로 바꾸는 UPDATE 가 된다).
     */
    suspend fun alreadyRecorded(record: TradeRecord): Boolean {
        val orderId = record.exchangeOrderId ?: return false
        val status = repository.findStatusByExchangeOrderId(record.userId, record.exchange.name, orderId).awaitSingleOrNull()
            ?: return false
        check(status == FILLED) {
            "orders already has a $status row for this order — refusing to record the fill over it: " +
                "userId=${record.userId}, exchange=${record.exchange}, market=${record.ticker}, orderId=$orderId"
        }
        return true
    }

    /**
     * 체결 한 건을 FILLED 줄로 남기고 그 id 를 돌려준다. [TradeExecutionService.saveAudit] 가 [alreadyRecorded] 뒤에, 체결 기록 트랜잭션
     * 안에서만 부른다. SQL 오류는 잡지 않는다 — Postgres 는 문장 하나가 실패하면 트랜잭션 전체를 중단하므로 잡아도 뒤의 쓰기가 실패한다.
     * 같은 client_order_id 의 줄이 이미 있으면 그 식별자 없이 남기고 ERROR 를 낸다 — 체결 기록을 되돌리면 그 종목이 확정에서 멈춰
     * 손절이 돌지 않는다. 그래도 넣지 못하면(예상 밖의 충돌) 예외로 되돌린다 — 체결 기록은 이 줄뿐이다.
     */
    suspend fun recordFill(record: TradeRecord, fee: Double?, feeKind: FeeKind): Long {
        val clientOrderId = record.clientOrderId?.takeUnless { taken ->
            repository.existsByClientOrderId(record.userId, record.exchange.name, taken).awaitSingle().also { exists ->
                if (exists) {
                    log.error(
                        "orders already has a row with this client order id — recording the fill without it: " +
                            "userId={}, exchange={}, market={}, side={}, orderId={}, clientOrderId={}",
                        record.userId, record.exchange, record.ticker, record.side, record.exchangeOrderId, taken,
                    )
                }
            }
        }
        val id = repository.insertFilled(
            userId = record.userId,
            exchange = record.exchange.name,
            market = record.ticker,
            side = record.side.name,
            clientOrderId = clientOrderId,
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
            recordCreatedAt = record.createdAt,
        ).awaitSingleOrNull()
        return checkNotNull(id) {
            "orders row not written — a row with the same order key appeared: userId=${record.userId}, exchange=${record.exchange}, " +
                "market=${record.ticker}, side=${record.side}, orderId=${record.exchangeOrderId}"
        }
    }

    private companion object {
        const val FILLED = "FILLED"
    }
}
