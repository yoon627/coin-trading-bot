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
 * 후보 청산 파라미터를 **라이브와 나란히** 평가해 기록만 한다 — 주문도, 상태 전이도 하지 않는다.
 *
 * 왜 이 형태인가: 변형 A 는 **청산 게이트만** 바꾸므로 진입이 라이브와 동일하다. 따라서 같은 포지션에서
 * 두 청산을 짝지어 볼 수 있고, [[strategy-evolution-expectations]] 가 폐기한 "별도 티커 실돈 파일럿" 의
 * 결함(다른 마켓에 배정하면 성과가 마켓 효과가 된다)이 구조적으로 발생하지 않는다.
 *
 * 무엇을 재는가: **모델 과대추정폭** 하나다. 백테는 트레일링 체결가를 `peak × (1 − trail/100)` 이라는
 * 임계선으로 잡는데, 실제로 그 게이트를 발동시키는 tick 가격은 그 이하다. 그 차이가 이 스레드가
 * 무너뜨린 청산 모델의 잔여 오차이며, 승격 전에 실물로 확인해야 하는 유일한 양이다.
 * **수익 우위 판정용이 아니다** — 그건 현재 거래 빈도로 약 4.7년이 걸린다(`TrailingShadowPowerTest`).
 *
 * 안전: [ExitGates.isTrailingStopTriggered] 는 순수 함수이고 peak 갱신은 라이브가 이미 한다(설정 무관).
 * 이 클래스는 라이브 판정 **뒤에** 불리며 모든 예외를 삼킨다 — 관측이 매매를 막으면 안 된다.
 */
class ShadowExitObserver(
    private val repository: ShadowExitObservationRepository,
    private val userId: Long,
    private val trailingStopPct: Double,
    private val trailingArmPct: Double,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 포지션당 첫 발동만 기록한다 — 이후는 두 팔이 갈라져 짝지은 비교가 아니다. */
    private data class Fired(val entryPrice: Double, val peakPrice: Double, val tickPrice: Double, val at: Instant)

    private val fired = ConcurrentHashMap<String, Fired>()

    /**
     * 라이브 tick 마다 호출. 후보 파라미터의 트레일링이 처음 발동하는 시점을 잡는다.
     *
     * [state] 를 **읽기만** 한다 — `updatePeakPrice` 는 라이브 경로가 이미 부른 뒤이므로 여기서 또 부르면
     * 같은 tick 을 두 번 반영하지는 않지만(멱등) 소유권이 흐려진다.
     */
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
     * 라이브가 실제로 청산했을 때 호출. 그 포지션에서 후보가 발동한 적이 있으면 관측 1건을 남기고,
     * 없으면 아무것도 남기지 않는다(두 팔이 같은 청산을 낸 경우라 정보가 0이다).
     */
    suspend fun onLiveExit(ticker: String, exitPrice: Double, reason: String) {
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
                    liveExitReason = reason,
                    liveExitAt = clock.instant(),
                ),
            ).awaitFirstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[shadow-exit] {} 관측 저장 실패(무시): {}", ticker, e.message)
        }
    }

    /** 포지션이 관측 없이 사라진 경우(재동기화·수동 청산) 상태를 흘리지 않는다. */
    fun forget(ticker: String) {
        fired.remove(ticker)
    }

    /** 백테가 체결됐다고 보는 가격. `IntrabarExitModel` 의 `trailStopPrice` 와 같은 식이다. */
    private fun modeledPrice(peak: Double) = peak * (1 - trailingStopPct / 100.0)
}
