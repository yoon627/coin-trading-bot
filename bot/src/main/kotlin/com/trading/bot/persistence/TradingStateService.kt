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
     * 재시작 복원 — user 의 모든 ticker row 를 도메인으로 매핑. 손상 필드는 **필드 단위로만** 버린다:
     * row 를 통째로 건너뛰면 pending 주문 uuid 까지 사라져 미체결 주문이 orphan 이 되고 재매수로 이중 주문이 난다(#20).
     */
    suspend fun loadStates(userId: Long): Map<String, TradingState> {
        val rows = tradingStateRepository.findByUserId(userId).collectList().awaitSingle()
        val result = LinkedHashMap<String, TradingState>()
        for (row in rows) {
            result[row.ticker] = row.toDomain()
        }
        return result
    }

    /** 단일 ticker 복원 — 런타임 티커 교체(자동 유니버스)가 durable 상태를 잃지 않게. */
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
            rungsFilled = rungsFilled,
            lastActionPrice = lastActionPrice,
            flatPeak = flatPeak,
            pendingBuyTriggerPrice = pendingBuyTriggerPrice,
            pendingSellTriggerPrice = pendingSellTriggerPrice,
            updatedAt = Instant.now(),
        )

    // position/avgBuyPrice/holdVolume 은 durable 에 없다 — syncPosition 이 거래소 잔고에서 복원한다.
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
            rungsFilled = rungsFilled,
            lastActionPrice = lastActionPrice,
            flatPeak = flatPeak,
            pendingBuyTriggerPrice = pendingBuyTriggerPrice,
            pendingSellTriggerPrice = pendingSellTriggerPrice,
        )

    // 손상 필드는 그 필드만 버린다 — pending uuid·halt 는 어떤 경우에도 복원돼야 한다(위 loadStates 주석).
    private fun decodeSellReason(ticker: String, raw: String): SellReason? =
        runCatching { SellReason.valueOf(raw) }.getOrElse {
            // 사유를 모른 채 매도 기록을 남기면 감사 왜곡 — MANUAL 로 표기해 사람이 구분할 수 있게 한다.
            log.warn("trading_state 복원: ticker={} 의 pending_sell_reason='{}' 을 해석 못 해 MANUAL 로 대체", ticker, raw)
            SellReason.MANUAL
        }

    private fun decodeExitParams(ticker: String, raw: String): ExitParamsSnapshot? =
        runCatching { objectMapper.readValue(raw, ExitParamsSnapshot::class.java) }.getOrElse {
            log.warn("trading_state 복원: ticker={} 의 exit_params JSON 손상 — 스냅샷만 버린다: {}", ticker, it.message)
            null
        }
}
