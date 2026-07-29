package com.trading.bot.persistence

import com.trading.bot.kis.engine.StockPosition
import com.trading.bot.persistence.entity.StockPositionStateEntity
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 주식 포지션 상태의 durable 저장·복원 (#64). 크립토 [TradingStateService] 의 주식판이며 같은 원칙을 따른다:
 * 보유수량·평단은 저장하지 않고(거래소 잔고가 진실), 거래소가 알려주지 않는 값만 담는다.
 */
@Service
class StockPositionStateService(
    private val repository: StockPositionStateRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 상태 전이 시점마다 호출 — per-(userId, exchange, symbol) upsert. */
    suspend fun upsert(userId: Long, pos: StockPosition, exchange: String = EXCHANGE) {
        val existingId = repository.findByUserIdAndExchangeAndSymbol(userId, exchange, pos.symbol)
            .awaitSingleOrNull()?.id
        repository.save(
            StockPositionStateEntity(
                id = existingId,
                userId = userId,
                exchange = exchange,
                symbol = pos.symbol,
                peakPrice = pos.peakPrice,
                boughtDate = pos.boughtDate,
                entryStrategy = pos.entryStrategy,
                updatedAt = Instant.now(),
            ),
        ).awaitSingle()
    }

    /**
     * 재시작 복원. `boughtToday` 는 저장값을 그대로 쓰지 않고 **저장된 거래일과 오늘을 비교해 재계산**한다 —
     * 플래그만 복원하면 어제 매수한 종목이 오늘도 진입 차단되거나, 반대로 오늘 매수분이 재진입으로 뚫린다.
     */
    suspend fun loadInto(userId: Long, positions: Map<String, StockPosition>, today: LocalDate, exchange: String = EXCHANGE) {
        val rows = repository.findByUserIdAndExchange(userId, exchange).collectList().awaitSingle()
        for (row in rows) {
            val pos = positions[row.symbol] ?: continue // watchlist 에서 빠진 종목은 복원 대상이 아니다
            pos.peakPrice = row.peakPrice
            pos.boughtDate = row.boughtDate
            pos.boughtToday = row.boughtDate == today
            pos.entryStrategy = row.entryStrategy
        }
        log.info("Restored {} stock position state(s) for user={}", rows.size, userId)
    }

    private companion object {
        const val EXCHANGE = "KIS"
    }
}
