package com.trading.bot.persistence

import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.TradingState
import com.trading.bot.domain.UserTradingParams
import com.trading.bot.persistence.entity.UserTradingParamsEntity
import com.trading.common.domain.Exchange
import java.time.Clock
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator

/** 사용자 매매 값([UserTradingParams])을 읽고, 저장할 때 보유 포지션의 청산 스냅샷을 함께 바꾼다. */
@Service
class UserTradingParamsService internal constructor(
    private val repository: UserTradingParamsRepository,
    private val tradingStateService: TradingStateService,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) {
    @Autowired
    constructor(
        repository: UserTradingParamsRepository,
        tradingStateService: TradingStateService,
        transactionalOperator: TransactionalOperator,
    ) : this(repository, tradingStateService, transactionalOperator, Clock.systemUTC())

    /** 행이 없으면 [UserTradingParams.NONE](모두 env). 읽기 실패는 그대로 던진다 — env 로 내려가면 사용자가 낮춘 한도가 풀린다. */
    suspend fun find(userId: Long, exchange: Exchange): UserTradingParams =
        repository.findByUserIdAndExchange(userId, exchange.name).awaitSingleOrNull()?.toDomain() ?: UserTradingParams.NONE

    /**
     * 값 행과 [targets] 의 청산 스냅샷([snapshot])을 한 트랜잭션에 저장한다. 어느 포지션을 바꿀지는 호출부가 정한다(미리보기와 같은 규칙).
     * 커밋되면 그 상태들에 새 스냅샷과 행 버전을 싣는다 — 이 상태로 띄운 엔진의 다음 저장이 버전 충돌하지 않게. 커밋되지 않으면
     * 상태를 원래대로 둔다. 바꾼 상태의 티커를 돌려준다.
     */
    suspend fun saveAndRewrite(
        userId: Long,
        exchange: Exchange,
        params: UserTradingParams,
        targets: Collection<TradingState>,
        snapshot: ExitParamsSnapshot,
    ): List<String> {
        val before = targets.associateWith { it.exitParams }
        val versions = try {
            transactionalOperator.transactional(
                mono {
                    val existing = repository.findByUserIdAndExchange(userId, exchange.name).awaitSingleOrNull()
                    val row = existing ?: UserTradingParamsEntity(userId = userId, exchange = exchange.name)
                    repository.save(row.with(params, clock.instant())).awaitSingle()
                    targets.associateWith { state ->
                        state.exitParams = snapshot
                        tradingStateService.upsert(userId, state)
                    }
                },
            ).awaitSingle()
        } catch (e: Throwable) {
            before.forEach { (state, original) -> state.exitParams = original }
            throw e
        }
        versions.forEach { (state, version) -> state.rowVersion = version }
        return targets.map { it.ticker }
    }
}
