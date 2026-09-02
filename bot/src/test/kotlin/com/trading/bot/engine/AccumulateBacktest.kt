package com.trading.bot.engine

import com.trading.common.domain.Candle
import com.trading.common.strategy.AccumulateLadder
import com.trading.common.strategy.LadderAction
import com.trading.common.strategy.LadderInput
import com.trading.common.strategy.LadderParams
import kotlin.math.max
import kotlin.math.min

/**
 * @param feeRate 편도 수수료(Upbit 0.05%). 매 액션에 반영한다 — 단이 많을수록 수수료가 격자 순위를 바꾼다.
 * @param maxActionsPerBar 봉당 액션 상한. 1 = 보수적(D1 봉당 1회), 크게 두면 라이브 10초 tick 이 한 봉 안에서
 *   여러 단을 진행하는 것을 근사한다. 두 값의 차이가 결과 민감도다.
 */
data class AccumulateBacktestConfig(
    val params: LadderParams = LadderParams(budgetKrw = 100_000.0, maxRungs = 5, stepDownPct = 3.0, stepUpPct = 3.0),
    val feeRate: Double = 0.0005,
    val maxActionsPerBar: Int = 1,
) {
    init {
        require(maxActionsPerBar >= 1) { "maxActionsPerBar must be >= 1, got $maxActionsPerBar" }
    }
}

data class AccumulateBacktestResult(
    val ticker: String,
    /** 종료 시 equity(현금 + 보유×종가) 의 예산 대비 순수익률(%). */
    val netReturnPct: Double,
    val realizedPnlKrw: Double,
    val unrealizedPnlKrw: Double,
    val maxInvestedKrw: Double,
    /** equity 의 peak-to-trough 낙폭을 예산으로 나눈 값(%). */
    val maxDrawdownPct: Double,
    val buys: Int,
    val sells: Int,
    val finalRungs: Int,
    /** 같은 예산을 첫 봉 종가에 전부 사서 마지막 봉 종가까지 들었을 때(왕복 수수료 차감). */
    val buyAndHoldPct: Double,
    /**
     * 기간 평균 투입 비율(0~1). 사다리는 예산 일부만 시장에 노출되므로 B&H(전액 노출)와 수익률을 나란히
     * 놓을 때 이 값을 같이 봐야 한다 — 낙폭이 작은 것이 실력인지 노출이 작아서인지 가른다.
     */
    val avgInvestedFraction: Double,
)

/**
 * 적립 사다리 전용 D1 백테. [BacktestEngine] 은 단일 포지션·청산 게이트 구조라 다단 포지션을 끼울 수 없어
 * 따로 둔다. 판정은 [AccumulateLadder] 를 그대로 호출한다 — 규칙을 여기서 다시 적으면 라이브와 갈라진다.
 * 프로덕션 호출부가 없는 분석 도구라 테스트 소스에 둔다(`PointInTimeUniverse` 와 같은 자리).
 *
 * 봉 처리: 매수는 봉 `low` 가 트리거가에 닿았는가, 매도는 `high` 가 닿았는가로 보고, 체결가는 트리거가
 * (시가가 이미 넘어섰으면 시가 — 갭). 한 봉에서 둘 다 닿으면 매수 우선. 진입 판정의 `flatPeak` 은
 * 직전 봉까지의 값이고 이 봉 `high` 는 판정 뒤에 반영한다(look-ahead 방지).
 *
 * 라이브(`PositionManager`)와 **판정식만** 같고 장부 전이는 다음이 의도적으로 다르다:
 * - 평단은 수수료 포함 투입 KRW 기준(라이브는 거래소 `avg_buy_price`, 수수료 제외) — 예산 게이트 임계가 미세하게 다르다.
 * - 체결은 항상 전량이라 rung 증감의 90% 체결비율 조건이 없다.
 * - 전량 매도 후 `flatPeak` 은 체결가 = 트리거가(라이브도 트리거가 — 같은 값이지만 경로가 다르다).
 */
class AccumulateBacktest {

    /** @param candles 최신순(index 0 = 최신) — fixture·API 응답 그대로. */
    fun run(candles: List<Candle>, ticker: String, config: AccumulateBacktestConfig = AccumulateBacktestConfig()): AccumulateBacktestResult {
        require(candles.size >= 2) { "at least 2 candles required, got ${candles.size}" }
        val chronological = candles.reversed()
        val params = config.params
        val state = SimState(cash = params.budgetKrw)

        var peakEquity = params.budgetKrw
        var maxDrawdownKrw = 0.0

        var investedSum = 0.0
        for (bar in chronological) {
            // 한 봉 안에서는 한 방향만 진행한다 — low 에서 사고 high 에서 파는 왕복은 순서를 알 수 없는 look-ahead 다.
            var direction: Direction? = null
            var actions = 0
            while (actions < config.maxActionsPerBar) {
                val acted = when (direction) {
                    null -> when {
                        tryBuy(state, bar, params, config.feeRate) -> { direction = Direction.BUY; true }
                        trySell(state, bar, params, config.feeRate) -> { direction = Direction.SELL; true }
                        else -> false
                    }
                    Direction.BUY -> tryBuy(state, bar, params, config.feeRate)
                    Direction.SELL -> trySell(state, bar, params, config.feeRate)
                }
                if (!acted) break
                actions++
            }
            if (state.holdVolume <= 0.0) state.flatPeak = max(state.flatPeak, bar.high)

            val equity = state.cash + state.holdVolume * bar.close
            peakEquity = max(peakEquity, equity)
            maxDrawdownKrw = max(maxDrawdownKrw, peakEquity - equity)
            investedSum += params.budgetKrw - state.cash
        }

        val lastClose = chronological.last().close
        val firstClose = chronological.first().close
        val endEquity = state.cash + state.holdVolume * lastClose
        val unrealized = state.holdVolume * lastClose - state.avgBuyPrice * state.holdVolume
        return AccumulateBacktestResult(
            ticker = ticker,
            netReturnPct = (endEquity - params.budgetKrw) / params.budgetKrw * 100.0,
            realizedPnlKrw = state.realizedPnlKrw,
            unrealizedPnlKrw = unrealized,
            maxInvestedKrw = state.maxInvestedKrw,
            maxDrawdownPct = maxDrawdownKrw / params.budgetKrw * 100.0,
            buys = state.buys,
            sells = state.sells,
            finalRungs = state.rungsFilled,
            buyAndHoldPct = (lastClose / firstClose - 1.0) * 100.0 - config.feeRate * 2 * 100.0,
            avgInvestedFraction = investedSum / chronological.size / params.budgetKrw,
        )
    }

    private enum class Direction { BUY, SELL }

    private fun tryBuy(state: SimState, bar: Candle, params: LadderParams, feeRate: Double): Boolean {
        val input = state.toInput(price = bar.low)
        val trigger = AccumulateLadder.buyTriggerPrice(input, params) ?: return false
        if (bar.low > trigger) return false
        val fillPrice = min(bar.open, trigger)
        val action = AccumulateLadder.decide(input.copy(price = fillPrice), params) as? LadderAction.Buy ?: return false
        if (action.amountKrw > state.cash) return false

        val volume = action.amountKrw * (1 - feeRate) / fillPrice
        val newHold = state.holdVolume + volume
        // 평단은 투입 KRW(수수료 포함) 기준 — 예산 게이트가 실제로 나간 돈을 보게.
        state.avgBuyPrice = (state.avgBuyPrice * state.holdVolume + action.amountKrw) / newHold
        state.holdVolume = newHold
        state.cash -= action.amountKrw
        state.rungsFilled++
        state.lastActionPrice = action.triggerPrice
        state.buys++
        state.maxInvestedKrw = max(state.maxInvestedKrw, params.budgetKrw - state.cash)
        return true
    }

    private fun trySell(state: SimState, bar: Candle, params: LadderParams, feeRate: Double): Boolean {
        val input = state.toInput(price = bar.high)
        val trigger = AccumulateLadder.sellTriggerPrice(input, params) ?: return false
        if (bar.high < trigger) return false
        val fillPrice = max(bar.open, trigger)
        val action = AccumulateLadder.decide(input.copy(price = fillPrice), params) as? LadderAction.Sell ?: return false

        val proceeds = action.volume * fillPrice * (1 - feeRate)
        state.realizedPnlKrw += proceeds - state.avgBuyPrice * action.volume
        state.cash += proceeds
        state.sells++
        if (action.isFinal) {
            state.holdVolume = 0.0
            state.avgBuyPrice = 0.0
            state.rungsFilled = 0
            state.flatPeak = fillPrice
        } else {
            state.holdVolume -= action.volume
            state.rungsFilled--
        }
        state.lastActionPrice = action.triggerPrice
        return true
    }

    private class SimState(
        var cash: Double,
        var holdVolume: Double = 0.0,
        var avgBuyPrice: Double = 0.0,
        var rungsFilled: Int = 0,
        var lastActionPrice: Double = 0.0,
        var flatPeak: Double = 0.0,
        var realizedPnlKrw: Double = 0.0,
        var maxInvestedKrw: Double = 0.0,
        var buys: Int = 0,
        var sells: Int = 0,
    ) {
        fun toInput(price: Double) = LadderInput(rungsFilled, lastActionPrice, flatPeak, avgBuyPrice, holdVolume, price)
    }
}
