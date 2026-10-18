package com.trading.bot.engine

import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.ShadowExitObservationRepository
import com.trading.bot.persistence.entity.ShadowExitObservationEntity
import com.trading.common.strategy.ExitGates
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.LoggerFactory

/**
 * 후보 트레일링 파라미터를 라이브 포지션과 나란히 평가해 기록만 한다. 주문도 상태 전이도 하지 않는다.
 * 진입이 라이브와 같으므로 같은 포지션에서 두 청산을 짝지어 비교할 수 있다.
 *
 * 재는 것은 모델 체결가(`peak × (1 − trail/100)`)와 실제로 게이트를 발동시킨 tick 가격의 차이다.
 * 관측이 매매를 막으면 안 되므로 라이브 판정 뒤에 불리고 모든 예외를 삼킨다.
 */
class ShadowExitObserver(
    private val repository: ShadowExitObservationRepository,
    private val userId: Long,
    private val trailingStopPct: Double,
    private val trailingArmPct: Double,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 포지션당 첫 발동만 기록한다. 그 뒤로는 두 청산 경로가 갈라져 짝지은 비교가 되지 않는다. */
    private data class Fired(val entryPrice: Double, val peakPrice: Double, val tickPrice: Double, val at: Instant)

    private val fired = ConcurrentHashMap<String, Fired>()

    /** [state] 는 읽기만 한다. peak 갱신은 라이브 경로가 맡는다. */
    fun onTick(ticker: String, state: TradingState, currentPrice: Double) {
        try {
            if (!state.position || state.avgBuyPrice <= 0 || state.peakPrice <= 0) return
            if (fired.containsKey(ticker)) return
            val triggered = ExitGates.isTrailingStopTriggered(
                pnlPct = state.pnlPercent(currentPrice),
                peakPnlPct = state.pnlPercent(state.peakPrice),
                dropFromPeakPct = state.dropFromPeakPercent(currentPrice),
                trailingStopPct = trailingStopPct,
                trailingArmPct = trailingArmPct,
            )
            if (!triggered) return
            fired[ticker] = Fired(state.avgBuyPrice, state.peakPrice, currentPrice, clock.instant())
            log.info(
                "[shadow-exit] {} 후보 트레일링 발동 — 진입 {} peak {} tick {} (모델 {})",
                ticker, state.avgBuyPrice, state.peakPrice, currentPrice, modeledPrice(state.peakPrice),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("[shadow-exit] {} 관측 실패(무시): {}", ticker, e.message)
        }
    }

    /**
     * 후보가 발동한 적 없는 포지션은 라이브와 같은 청산이라 기록하지 않는다.
     * [executedVwap] 은 얻지 못하면 추정하지 않고 null 로 둔다. reconcile 로 늦게 확정된 청산은 [exitAt] 에 판단 시각을 넘긴다.
     */
    suspend fun onLiveExit(ticker: String, exitPrice: Double, reason: String?, executedVwap: Double? = null, exitAt: Instant? = null) {
        val f = fired.remove(ticker) ?: return
        try {
            repository.save(
                ShadowExitObservationEntity(
                    userId = userId,
                    ticker = ticker,
                    trailingStopPct = trailingStopPct,
                    trailingArmPct = trailingArmPct,
                    entryPrice = f.entryPrice,
                    peakPrice = f.peakPrice,
                    modeledExitPrice = modeledPrice(f.peakPrice),
                    observedTickPrice = f.tickPrice,
                    firedAt = f.at,
                    liveExitPrice = exitPrice,
                    liveExitVwap = executedVwap,
                    liveExitReason = reason,
                    liveExitAt = exitAt ?: clock.instant(),
                ),
            ).awaitFirstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[shadow-exit] {} 관측 저장 실패(무시): {}", ticker, e.message)
        }
    }

    /** 재동기화·수동 청산처럼 라이브 청산 없이 포지션이 사라질 때 부른다. */
    fun forget(ticker: String) {
        fired.remove(ticker)
    }

    private fun modeledPrice(peak: Double) = peak * (1 - trailingStopPct / 100.0)
}
