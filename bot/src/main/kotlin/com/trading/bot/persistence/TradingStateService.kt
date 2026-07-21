package com.trading.bot.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.entity.TradingStateEntity
import java.time.LocalDateTime
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * per-(userId, ticker) 거래 상태의 durable 저장·복원. 상태 전이 시점마다 upsert 하고, 재시작 시 loadStates 로 복원한다.
 * 도메인 TradingState ↔ TradingStateEntity 매핑과 exit_params JSON(Jackson) 직렬화를 담당한다.
 */
@Service
class TradingStateService(
    private val tradingStateRepository: TradingStateRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 상태 전이 시점마다 호출 — per-(userId, ticker) upsert(있으면 갱신, 없으면 삽입). */
    suspend fun upsert(userId: Long, state: TradingState) {
        val existingId = tradingStateRepository.findByUserIdAndTicker(userId, state.ticker)
            .awaitSingleOrNull()?.id
        tradingStateRepository.save(state.toEntity(userId, existingId)).awaitSingle()
    }

    /**
     * 재시작 복원 — user 의 모든 ticker row 를 도메인으로 매핑. 개별 row decode 실패(손상 JSON·알 수 없는 enum 등)는
     * 해당 ticker 만 건너뛰어 빈 상태로 syncPosition 재구축되게 격리한다 — 한 row 손상이 유저 전체 복원을 막지 않게.
     */
    suspend fun loadStates(userId: Long): Map<String, TradingState> {
        val rows = tradingStateRepository.findByUserId(userId).collectList().awaitSingle()
        val result = LinkedHashMap<String, TradingState>()
        for (row in rows) {
            val state = try {
                row.toDomain()
            } catch (e: Exception) {
                log.warn("trading_state 복원 실패 — ticker={} 격리(빈 상태로 재구축): {}", row.ticker, e.message)
                continue
            }
            result[state.ticker] = state
        }
        return result
    }

    private fun TradingState.toEntity(userId: Long, id: Long?): TradingStateEntity =
        TradingStateEntity(
            id = id,
            userId = userId,
            ticker = ticker,
            pendingBuyUuid = pendingBuyUuid,
            pendingBuyStrategy = pendingBuyStrategy,
            pendingSellUuid = pendingSellUuid,
            pendingSellReason = pendingSellReason?.name,
            entryStrategy = entryStrategy,
            buyDate = buyDate,
            boughtToday = boughtToday,
            peakPrice = peakPrice,
            exitParamsJson = exitParams?.let { objectMapper.writeValueAsString(it) },
            halted = halted,
            haltReason = haltReason,
            reconcileFailureCount = reconcileFailureCount,
            updatedAt = LocalDateTime.now(),
        )

    // position/avgBuyPrice/holdVolume 은 durable 에 없다 — syncPosition 이 거래소 잔고에서 복원한다.
    private fun TradingStateEntity.toDomain(): TradingState =
        TradingState(
            ticker = ticker,
            peakPrice = peakPrice,
            buyDate = buyDate,
            boughtToday = boughtToday,
            entryStrategy = entryStrategy,
            pendingBuyUuid = pendingBuyUuid,
            pendingBuyStrategy = pendingBuyStrategy,
            pendingSellUuid = pendingSellUuid,
            pendingSellReason = pendingSellReason?.let { SellReason.valueOf(it) },
            halted = halted,
            haltReason = haltReason,
            reconcileFailureCount = reconcileFailureCount,
            exitParams = exitParamsJson?.let { objectMapper.readValue(it, ExitParamsSnapshot::class.java) },
        )
}
