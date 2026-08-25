package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.ExitGates
import com.trading.common.strategy.Indicators
import com.trading.common.strategy.TradingStrategy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// 디폴트는 라이브(TradingProperties)와 정합 — 직접 생성(스윕 등) 베이스라인이 라이브를 대표하도록 (#27).
// parity 는 BacktestEngineTest 의 `config defaults match live trading defaults` 가 가드한다.
/**
 * TIME_EXIT(라이브 `DAILY_RESET`) 직후 재진입을 어떻게 모델링할지.
 *
 * 라이브는 09:00 리셋 매도 후 ~10초 뒤 재매수가 가능한데(`boughtToday` 가 같은 경계에서 풀린다),
 * 기존 백테는 청산 봉에서 진입 평가를 아예 하지 않아 **2봉 강제 공백**이 생긴다(#128).
 */
enum class ReentryMode {
    /** 기존 동작 — 청산 봉 `i` → 신호 `i+1` → 체결 `i+2`. 기본값(계약 보존). */
    LEGACY_NEXT_BAR,

    /** TIME_EXIT 한정 same-bar 재진입 — 청산 봉 `D` 의 `open` 에 재진입(신호는 `D-1` 종가까지). */
    LIVE_SAME_BAR,
}

data class BacktestConfig(
    val takeProfitPct: Double = 5.0,
    val maxLossPct: Double = 5.0,
    val kValue: Double = 0.5,
    val feeRate: Double = 0.0005,
    val trailingStopPct: Double = 2.0,
    val trailingArmPct: Double = 3.0,
    val maxHoldDays: Int = 1,
    val useMarketFilter: Boolean = false,
    val chartExitEnabled: Boolean = false,
    /**
     * true 면 보유상한 청산을 **수익 중일 때만** 낸다 — 손실이면 그대로 들고 간다(#128 2안 "리셋 대상 한정").
     * 판정 기준가는 상한이 걸리는 시각의 가격 = `bar.open`(= 라이브 KST 09:00). 게이트는 gross 로 본다
     * (수수료는 기록에서만 차감 — [[exit-gates]] 규약).
     */
    val holdLimitOnlyWhenProfitable: Boolean = false,
    /**
     * 기본값을 라이브(0공백)와 다르게 두는 이유 — `M1ReplayBiasTest`·`ParameterSweepTest`·
     * `KneeStrategyComparisonTest` 가 `BacktestConfig()` 를 상속하므로, 기본값을 바꾸면 그 측정들의
     * 모집단 자체가 달라진다. `/backtest` public 계약도 조용히 바뀐다. 전환은 라이브 변경을 결정하는
     * 후속 작업에서 함께 판단한다(#128 plan Decision 6).
     */
    val reentryMode: ReentryMode = ReentryMode.LEGACY_NEXT_BAR,
    /** [ReentryMode.LIVE_SAME_BAR] 에서 재진입을 몇 봉 막을지. 0 = 청산 봉 즉시 재진입. */
    val reentryCooldownBars: Int = 0,
) {
    init {
        // 음수면 reentryDueAt < i 가 되어 조용히 cooldown=1 처럼 동작하고, 거대값이면 남은 전 구간 진입이
        // 에러 없이 차단된다. 둘 다 결과가 그럴듯해 보여 측정이 조용히 망가지므로 생성 시점에 막는다.
        require(reentryCooldownBars >= 0) { "reentryCooldownBars must be >= 0, was $reentryCooldownBars" }
        require(reentryMode == ReentryMode.LIVE_SAME_BAR || reentryCooldownBars == 0) {
            "reentryCooldownBars has no effect in $reentryMode — set LIVE_SAME_BAR or leave it 0"
        }
    }
}

data class BacktestTrade(
    val buyIndex: Int,
    val sellIndex: Int,
    val buyPrice: Double,
    val sellPrice: Double,
    val pnlPercent: Double,
    val holdDays: Int,
    val reason: String,
)

data class BacktestResult(
    val strategyName: String,
    val ticker: String,
    val totalTrades: Int,
    val winTrades: Int,
    val lossTrades: Int,
    val winRate: Double,
    val totalReturnPct: Double,
    val avgReturnPct: Double,
    val maxDrawdownPct: Double,
    val sharpeRatio: Double,
    val profitFactor: Double,
    val buyAndHoldPct: Double,
    val avgHoldDays: Double,
    val trades: List<BacktestTrade>,
)

class BacktestEngine(
    private val strategies: List<TradingStrategy>,
    private val tradingProperties: TradingProperties,
) {
    companion object {
        private const val MIN_CANDLES = 50
        private const val INITIAL_BALANCE = 1_000_000.0
        private const val MAX_PROFIT_FACTOR = 999.0
    }

    suspend fun run(
        strategyName: String,
        candles: List<Candle>,
        ticker: String,
        config: BacktestConfig = BacktestConfig(),
    ): BacktestResult? {
        val strategy = strategies.find { it.name == strategyName } ?: return null
        val chronological = candles.reversed()
        // 루프가 MIN_CANDLES 부터 시작하므로 정확히 MIN_CANDLES 개면 시뮬레이션이 한 번도 돌지 않고,
        // buildResult 가 chronological[MIN_CANDLES] 를 읽어 터진다. 신호를 낼 수 있는 최소는 +1 이다.
        if (chronological.size <= MIN_CANDLES) return null

        val simulation = simulateTrades(strategy, chronological, config)
        return buildResult(strategyName, ticker, chronological, simulation, config)
    }

    private suspend fun simulateTrades(
        strategy: TradingStrategy,
        chronological: List<Candle>,
        config: BacktestConfig,
    ): SimulationState {
        val state = SimulationState()
        // 신호(shouldBuy/shouldSell)는 config 의 신호 파라미터를 반영해야 진입 파라미터 백테 비교가 유효(#31).
        // 전략이 신호에서 읽는 config 필드는 kValue 뿐이라, 라이브 baseline 에 kValue 만 덮어 신호 판단에 넘긴다.
        val signalProps = tradingProperties.copy(kValue = config.kValue)

        // LIVE_SAME_BAR 재진입 예약 — TIME_EXIT 이 난 봉 + 쿨다운. -1 = 예약 없음.
        // "봉당 진입 1회"(라이브 boughtToday 등가)는 별도 플래그가 아니라 구조가 보장한다 — 한 반복은
        // 재진입 경로나 통상 경로 중 하나에서만 체결하고, 체결하면 곧바로 다음 봉으로 넘어간다.
        var reentryDueAt = -1

        for (i in MIN_CANDLES until chronological.size) {
            val currentPrice = chronological[i].tradePrice
            // 신호는 봉 i 종가까지의 정보로만 판단(look-ahead 방지). 매수/매도(chartExit) 공용 window.
            val window = chronological.subList(max(0, i - (MIN_CANDLES - 1)), i + 1).reversed()

            if (state.position) {
                val reason = processExit(state, strategy, i, chronological[i], window, config, signalProps)
                // 라이브는 09:00 리셋 매도와 동시에 boughtToday 가 풀려 곧바로 재매수가 가능하다.
                // 가격게이트 청산은 제외 — 청산가가 실제 체결가가 아니라 게이트 임계가이고, 봉의 high/low 를
                // 본 뒤 같은 봉에 사는 셈이라 look-ahead 다(#128 plan Decision 4).
                if (config.reentryMode == ReentryMode.LIVE_SAME_BAR && reason == "TIME_EXIT") {
                    reentryDueAt = i + config.reentryCooldownBars
                }
                // 청산이 난 봉에서는 기존 규약상 진입 평가를 하지 않는다. 예약된 재진입이 바로 이 봉일 때만 이어간다.
                if (state.position || reentryDueAt != i) continue
            }

            if (reentryDueAt >= 0) {
                if (i < reentryDueAt) continue // 쿨다운 구간 — 기존 진입 규약도 멈춘다(지연 효과 격리)
                reentryDueAt = -1
                // 봉 i 시가에 체결하므로 신호는 봉 i-1 종가까지만 본다 — 공용 window 는 봉 i 를 포함해 쓸 수 없다.
                val signalWindow = chronological.subList(max(0, i - MIN_CANDLES), i).reversed()
                val entered = processEntry(
                    state, strategy, i, chronological[i - 1].tradePrice, chronological[i].openingPrice,
                    signalWindow, config, signalProps,
                )
                if (entered) {
                    // 재진입 포지션도 이 봉의 intrabar 게이트를 받는다 — 빠뜨리면 churn 포지션만 손절·익절
                    // 보호가 사라져 편향된다. 진입 신호·체결가는 이 봉 high/low 확정 전에 정해졌으므로
                    // look-ahead 가 아니다(#128 plan Decision 5).
                    processExit(state, strategy, i, chronological[i], window, config, signalProps)
                    continue
                }
                // 재진입 신호가 없었으면 이 봉의 통상 진입 기회(신호=봉 i 종가, 체결=봉 i+1 시가)는 살아 있다.
                // 여기서 continue 하면 legacy 가 갖는 그 기회를 쿨다운 팔만 잃어, 측정이 정책 차이가 아니라
                // 구현 아티팩트를 재게 된다 — BacktestReentryEquivalenceTest 가 cooldown=2 == legacy 로 가둔다.
            }

            // 체결은 다음 봉(i+1) 시가로.
            val fillIndex = i + 1
            if (fillIndex >= chronological.size) continue
            val fillPrice = chronological[fillIndex].openingPrice
            processEntry(state, strategy, fillIndex, currentPrice, fillPrice, window, config, signalProps)
        }

        closeOpenPosition(state, chronological, config)
        return state
    }

    /** @return 청산 사유. 청산이 없었으면 null — 호출부가 TIME_EXIT 재진입 예약 여부를 가른다. */
    private suspend fun processExit(
        state: SimulationState,
        strategy: TradingStrategy,
        index: Int,
        bar: Candle,
        window: List<Candle>,
        config: BacktestConfig,
        signalProps: TradingProperties,
    ): String? {
        val holdDays = index - state.buyIndex
        val buyPrice = state.buyPrice
        // 상한이 걸려도 손실이면 청산하지 않는 정책(#128 2안). atHoldLimit=false 가 되면 이 봉은 일반 보유
        // 구간처럼 평가된다 — IntrabarExitModel 이 open 으로 눌리지 않고 high/low 를 그대로 본다.
        val atHoldLimit = holdDays >= ExitGates.effectiveMaxHoldDays(config.maxHoldDays) &&
            (!config.holdLimitOnlyWhenProfitable || bar.openingPrice >= buyPrice)

        // 청산 판정은 IntrabarExitModel 로 위임 — D1 백테와 M1 replay 가 동일 게이트식을 공유(편향 정합).
        // armPeak 은 이 봉 high 반영 전 peak(트레일링 arm 팬텀 방지), peak 갱신은 다음 봉 판정용.
        val armPeak = state.peakPrice
        state.peakPrice = IntrabarExitModel.updatedPeak(state.peakPrice, bar, atHoldLimit)
        val chartExitSignal = config.chartExitEnabled && !atHoldLimit &&
            strategy.shouldSell(window, bar.tradePrice, signalProps)
        val (reason, sellPrice) = IntrabarExitModel
            .evaluate(bar, buyPrice, armPeak, atHoldLimit, config, chartExitSignal)
            ?.let { it.reason to it.sellPrice } ?: return null

        val netPnl = ((sellPrice - buyPrice) / buyPrice) * 100.0 - (config.feeRate * 2 * 100)
        state.balance *= (1 + netPnl / 100.0)
        state.trades.add(BacktestTrade(state.buyIndex, index, buyPrice, sellPrice, netPnl, holdDays, reason))
        state.returns.add(netPnl)
        state.peakBalance = max(state.peakBalance, state.balance)
        state.maxDrawdown = max(state.maxDrawdown, (state.peakBalance - state.balance) / state.peakBalance * 100)
        state.position = false
        return reason
    }

    private suspend fun processEntry(
        state: SimulationState,
        strategy: TradingStrategy,
        fillIndex: Int,
        signalPrice: Double,
        fillPrice: Double,
        window: List<Candle>,
        config: BacktestConfig,
        signalProps: TradingProperties,
    ): Boolean {
        if (fillPrice <= 0) return false
        if (config.useMarketFilter) {
            val ma50 = Indicators.calculateMa(window, min(MIN_CANDLES, window.size))
            if (ma50 > 0 && signalPrice < ma50) return false
        }

        // 신호는 window 최신 봉 종가(signalPrice)로 판단, 체결가는 fillPrice.
        // 기존 규약은 신호 봉 i → 체결 i+1 시가, LIVE_SAME_BAR 재진입은 신호 봉 i-1 → 체결 i 시가다.
        if (!strategy.shouldBuy(window, signalPrice, signalProps)) return false
        state.buyPrice = fillPrice
        state.peakPrice = fillPrice
        state.buyIndex = fillIndex
        state.position = true
        return true
    }

    private fun closeOpenPosition(state: SimulationState, chronological: List<Candle>, config: BacktestConfig) {
        if (!state.position) return
        val lastPrice = chronological.last().tradePrice
        val pnl = ((lastPrice - state.buyPrice) / state.buyPrice) * 100.0 - (config.feeRate * 2 * 100)
        state.balance *= (1 + pnl / 100.0)
        state.trades.add(BacktestTrade(state.buyIndex, chronological.size - 1, state.buyPrice, lastPrice, pnl, chronological.size - 1 - state.buyIndex, "END"))
        state.returns.add(pnl)
        // processExit 와 같은 갱신 — 빠뜨리면 END 로 끝난 손실이 낙폭에 반영되지 않아 MDD 가 과소평가된다.
        state.peakBalance = max(state.peakBalance, state.balance)
        state.maxDrawdown = max(state.maxDrawdown, (state.peakBalance - state.balance) / state.peakBalance * 100)
    }

    private fun buildResult(
        strategyName: String,
        ticker: String,
        chronological: List<Candle>,
        state: SimulationState,
        config: BacktestConfig,
    ): BacktestResult {
        val firstPrice = chronological[MIN_CANDLES].tradePrice
        val lastPrice = chronological.last().tradePrice
        val buyAndHold = ((lastPrice - firstPrice) / firstPrice) * 100.0

        val winTrades = state.trades.count { it.pnlPercent > 0 }
        val lossTrades = state.trades.count { it.pnlPercent <= 0 }
        val totalReturn = ((state.balance - INITIAL_BALANCE) / INITIAL_BALANCE) * 100.0

        val grossProfit = state.returns.filter { it > 0 }.sum()
        val grossLoss = state.returns.filter { it < 0 }.map { -it }.sum()
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) MAX_PROFIT_FACTOR else 0.0

        val avgReturn = if (state.returns.isNotEmpty()) state.returns.average() else 0.0
        val stdDev = if (state.returns.size > 1) {
            val mean = state.returns.average()
            sqrt(state.returns.map { (it - mean) * (it - mean) }.average())
        } else 0.0
        val sharpe = if (stdDev > 0) avgReturn / stdDev else 0.0
        val avgHold = if (state.trades.isNotEmpty()) state.trades.map { it.holdDays }.average() else 0.0

        return BacktestResult(
            strategyName = strategyName,
            ticker = ticker,
            totalTrades = state.trades.size,
            winTrades = winTrades,
            lossTrades = lossTrades,
            winRate = if (state.trades.isNotEmpty()) winTrades.toDouble() / state.trades.size * 100.0 else 0.0,
            totalReturnPct = totalReturn,
            avgReturnPct = avgReturn,
            maxDrawdownPct = state.maxDrawdown,
            sharpeRatio = sharpe,
            profitFactor = min(profitFactor, MAX_PROFIT_FACTOR),
            buyAndHoldPct = buyAndHold,
            avgHoldDays = avgHold,
            trades = state.trades,
        )
    }

    suspend fun compareAll(
        candles: List<Candle>,
        ticker: String,
        config: BacktestConfig = BacktestConfig(),
    ): List<BacktestResult> {
        return strategies.mapNotNull { strategy ->
            run(strategy.name, candles, ticker, config)
        }
    }

    private class SimulationState(
        var balance: Double = INITIAL_BALANCE,
        var peakBalance: Double = INITIAL_BALANCE,
        var maxDrawdown: Double = 0.0,
        var position: Boolean = false,
        var buyPrice: Double = 0.0,
        var peakPrice: Double = 0.0,
        var buyIndex: Int = 0,
        val trades: MutableList<BacktestTrade> = mutableListOf(),
        val returns: MutableList<Double> = mutableListOf(),
    )
}
