package com.trading.bot.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.entity.ManualInterventionEntity
import java.time.Clock
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator

/** 사람이 바꾼 상태를 그 기록과 한 트랜잭션으로 저장한다. 기록 없이 상태만 바뀌지 않게 한다. */
@Service
class ManualInterventionService(
    private val repository: ManualInterventionRepository,
    private val tradingStateService: TradingStateService,
    private val transactionalOperator: TransactionalOperator,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * [state] 를 저장하고 [action] 한 행을 남긴다. detail 에는 [detail] 과 바꾸기 전 저장 행 전체(`before`)를 담는다.
     * 실패하면 둘 다 커밋되지 않고 예외를 던진다.
     */
    suspend fun saveWithRecord(userId: Long, state: TradingState, action: String, detail: Map<String, Any?>) {
        // 직렬화를 트랜잭션 밖에서 끝내 트랜잭션 안의 실패 경로를 저장 두 번으로 줄인다.
        val before = tradingStateService.findRow(userId, state)
        val row = ManualInterventionEntity(
            userId = userId,
            exchange = state.exchange.name,
            ticker = state.ticker,
            action = action,
            detail = objectMapper.writeValueAsString(detail + ("before" to before)),
            createdAt = clock.instant(),
        )
        transactionalOperator.transactional(
            mono {
                tradingStateService.upsert(userId, state)
                repository.save(row).awaitSingle()
            }
        ).awaitSingle()
    }
}
