package com.trading.bot.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.entity.TradingStateEntity
import com.trading.common.domain.Exchange
import java.time.Instant
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * (userId, exchange, ticker) 별 거래 상태를 저장하고 재시작 때 복원한다.
 * Upbit 는 거래소 축이 생기기 전의 조회 키(user, ticker)를 그대로 쓰고 결과를 거래소로 거른다.
 */
@Service
class TradingStateService(
    private val tradingStateRepository: TradingStateRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun upsert(userId: Long, state: TradingState) {
        val existing = if (state.exchange == Exchange.UPBIT) {
            tradingStateRepository.findByUserIdAndTicker(userId, state.ticker).awaitSingleOrNull()?.also {
                // 다른 거래소 행의 id 로 저장하면 그 행을 Upbit 상태로 덮어쓴다.
                check(it.exchange == state.exchange.name) {
                    "trading_state 저장 거부: user $userId 의 ${state.ticker} 행은 ${it.exchange} 거래소 행이다"
                }
            }
        } else {
            tradingStateRepository.findByUserIdAndExchangeAndTicker(userId, state.exchange.name, state.ticker).awaitSingleOrNull()
        }
        tradingStateRepository.save(state.toEntity(userId, existing?.id)).awaitSingle()
    }

    /**
     * [exchange] 의 행만 돌려준다. 손상된 필드는 그 필드만 버린다. 행을 통째로 건너뛰면 pending 주문 uuid 까지 사라져
     * 미체결 주문을 놓치고 재매수로 이중 주문이 난다. 거래소 값을 모르는 행만은 어느 엔진의 행도 아니라서 싣지 않는다.
     */
    suspend fun loadStates(userId: Long, exchange: Exchange = Exchange.UPBIT): Map<String, TradingState> {
        val rows = if (exchange == Exchange.UPBIT) {
            tradingStateRepository.findByUserId(userId).collectList().awaitSingle().filter { it.exchange == exchange.name }
        } else {
            tradingStateRepository.findByUserIdAndExchange(userId, exchange.name).collectList().awaitSingle()
        }
        val result = LinkedHashMap<String, TradingState>()
        for (row in rows) {
            row.toDomain()?.let { result[row.ticker] = it }
        }
        return result
    }

    suspend fun loadState(userId: Long, ticker: String, exchange: Exchange = Exchange.UPBIT): TradingState? {
        val row = if (exchange == Exchange.UPBIT) {
            tradingStateRepository.findByUserIdAndTicker(userId, ticker).awaitSingleOrNull()?.takeIf { it.exchange == exchange.name }
        } else {
            tradingStateRepository.findByUserIdAndExchangeAndTicker(userId, exchange.name, ticker).awaitSingleOrNull()
        }
        return row?.toDomain()
    }

    private fun TradingState.toEntity(userId: Long, id: Long?): TradingStateEntity =
        TradingStateEntity(
            id = id,
            userId = userId,
            exchange = exchange.name,
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
            reviewReason = reviewReason,
            updatedAt = Instant.now(),
        )

    /**
     * 보유 수량과 평균 단가는 저장하지 않고 거래소 잔고에서 다시 맞춘다. 거래소는 조회 조건이 아니라 행의 값을 싣는다.
     * 조회가 엉뚱한 행을 돌려줘도 엔진·매니저의 거래소 검사가 그 행을 알아보게 하려는 것이다. 모르는 거래소 행은 싣지 않는다.
     */
    private fun TradingStateEntity.toDomain(): TradingState? {
        val rowExchange = runCatching { Exchange.valueOf(exchange) }.getOrElse {
            log.error("trading_state 복원: user={} ticker={} 의 exchange='{}' 를 해석 못 해 그 행을 쓰지 않는다", userId, ticker, exchange)
            return null
        }
        return TradingState(
            ticker = ticker,
            exchange = rowExchange,
            reviewReason = reviewReason,
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
    }

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
