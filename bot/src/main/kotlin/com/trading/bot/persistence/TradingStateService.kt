package com.trading.bot.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.entity.TradingStateEntity
import java.time.Instant
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** (userId, ticker) 별 거래 상태를 저장하고 재시작 때 복원한다. */
@Service
class TradingStateService(
    private val tradingStateRepository: TradingStateRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun upsert(userId: Long, state: TradingState) {
        val existingId = tradingStateRepository.findByUserIdAndTicker(userId, state.ticker)
            .awaitSingleOrNull()?.id
        tradingStateRepository.save(state.toEntity(userId, existingId)).awaitSingle()
    }

    /**
     * 손상된 필드는 그 필드만 버린다. 행을 통째로 건너뛰면 pending 주문 uuid 까지 사라져
     * 미체결 주문을 놓치고 재매수로 이중 주문이 난다.
     */
    suspend fun loadStates(userId: Long): Map<String, TradingState> {
        val rows = tradingStateRepository.findByUserId(userId).collectList().awaitSingle()
        val result = LinkedHashMap<String, TradingState>()
        for (row in rows) {
            result[row.ticker] = row.toDomain()
        }
        return result
    }

    suspend fun loadState(userId: Long, ticker: String): TradingState? =
        tradingStateRepository.findByUserIdAndTicker(userId, ticker).awaitSingleOrNull()?.toDomain()

    private fun TradingState.toEntity(userId: Long, id: Long?): TradingStateEntity =
        TradingStateEntity(
            id = id,
            userId = userId,
            ticker = ticker,
            pendingBuyUuid = pendingBuyUuid,
            pendingBuyStrategy = pendingBuyStrategy,
            pendingSellUuid = pendingSellUuid,
            pendingSellReason = pendingSellReason?.name,
            pendingSellVolume = pendingSellVolume,
            pendingSellAvgPrice = pendingSellAvgPrice,
            pendingSellSince = pendingSellSince,
            pendingSellAlerted = pendingSellAlerted,
            entryStrategy = entryStrategy,
            buyDate = buyDate,
            boughtToday = boughtToday,
            boughtDate = boughtDate,
            peakPrice = peakPrice,
            exitParamsJson = exitParams?.let { objectMapper.writeValueAsString(it) },
            halted = halted,
            haltReason = haltReason,
            reconcileFailureCount = reconcileFailureCount,
            pendingBuyPriorVolume = pendingBuyPriorVolume,
            pendingSellTriggerPrice = pendingSellTriggerPrice,
            pendingSellPriorVolume = pendingSellPriorVolume,
            pendingBuyIdentifier = pendingBuyIdentifier,
            pendingSellIdentifier = pendingSellIdentifier,
            updatedAt = Instant.now(),
        )

    // 보유 수량과 평균 단가는 저장하지 않고 거래소 잔고에서 다시 맞춘다.
    private fun TradingStateEntity.toDomain(): TradingState =
        TradingState(
            ticker = ticker,
            peakPrice = peakPrice,
            buyDate = buyDate,
            boughtToday = boughtToday,
            boughtDate = boughtDate,
            entryStrategy = entryStrategy,
            pendingBuyUuid = pendingBuyUuid,
            pendingBuyStrategy = pendingBuyStrategy,
            pendingSellUuid = pendingSellUuid,
            pendingSellReason = pendingSellReason?.let { decodeSellReason(ticker, it) },
            pendingSellVolume = pendingSellVolume,
            pendingSellAvgPrice = pendingSellAvgPrice,
            pendingSellSince = pendingSellSince,
            pendingSellAlerted = pendingSellAlerted,
            halted = halted,
            haltReason = haltReason,
            reconcileFailureCount = reconcileFailureCount,
            exitParams = exitParamsJson?.let { decodeExitParams(ticker, it) },
            pendingBuyPriorVolume = pendingBuyPriorVolume,
            pendingSellTriggerPrice = pendingSellTriggerPrice,
            pendingSellPriorVolume = pendingSellPriorVolume,
            pendingBuyIdentifier = pendingBuyIdentifier,
            pendingSellIdentifier = pendingSellIdentifier,
        )

    private fun decodeSellReason(ticker: String, raw: String): SellReason? =
        runCatching { SellReason.valueOf(raw) }.getOrElse {
            // 알 수 없는 사유는 MANUAL 로 남겨 사람이 구분할 수 있게 한다.
            log.warn("trading_state 복원: ticker={} 의 pending_sell_reason='{}' 을 해석 못 해 MANUAL 로 대체", ticker, raw)
            SellReason.MANUAL
        }

    private fun decodeExitParams(ticker: String, raw: String): ExitParamsSnapshot? =
        runCatching { objectMapper.readValue(raw, ExitParamsSnapshot::class.java) }.getOrElse {
            log.warn("trading_state 복원: ticker={} 의 exit_params JSON 손상 — 스냅샷만 버린다: {}", ticker, it.message)
            null
        }
}
