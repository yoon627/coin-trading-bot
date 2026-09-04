package com.trading.bot.engine

import com.fasterxml.jackson.annotation.JsonIgnore
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
     * 기본값을 라이브(0공백)와 다르게 두는 이유 — `M1ReplayBiasTest`·`StrategySearch`·
     * `KneeStrategyComparisonTest` 가 `BacktestConfig()` 를 상속하므로, 기본값을 바꾸면 그 측정들의
     * 모집단 자체가 달라진다. `/backtest` public 계약도 조용히 바뀐다. 전환은 라이브 변경을 결정하는
     * 후속 작업에서 함께 판단한다(#128 plan Decision 6).
     */
    val reentryMode: ReentryMode = ReentryMode.LEGACY_NEXT_BAR,
    /** [ReentryMode.LIVE_SAME_BAR] 에서 재진입을 몇 봉 막을지. 0 = 청산 봉 즉시 재진입. */
    val reentryCooldownBars: Int = 0,
    /**
     * [useMarketFilter] 가 보는 이동평균 기간. 기본 50 = 현행 동작 그대로.
     *
     * 엔진이 전략·필터에 넘기는 window 는 **항상 정확히 [BacktestEngine.MIN_CANDLES] 봉**이라 그보다 긴 기간은
     * `min(period, window.size)` 로 **조용히 절삭**된다. 조용한 절삭은 "기간을 바꿔도 결과가 같다"는 거짓 결론을
     * 리포트에 싣게 하므로 생성 시점에 막는다.
     */
    val marketFilterMaPeriod: Int = BacktestEngine.MIN_CANDLES,
    /**
     * 손절선을 진입 시점 ATR 의 배수로 잡는다(null = 기존 [maxLossPct] 퍼센트 방식).
     * 리서치 전용 노브 — 라이브·`/backtest` 에는 노출하지 않는다.
     */
    val atrStopMultiplier: Double? = null,
    /** 익절선을 손절폭의 R 배로 잡는다(null = 기존 [takeProfitPct]). [atrStopMultiplier] 없이는 쓸 수 없다. */
    val atrTakeProfitR: Double? = null,
    /** 1차 부분 익절선(%). null = 부분 익절 없음. */
    val partialTakeProfitPct: Double? = null,
    /** 1차 익절선에서 청산할 비중(0<f<1). [partialTakeProfitPct] 와 항상 함께 설정한다. */
    val partialTakeProfitFraction: Double? = null,
    /**
     * 보유상한(라이브 09:00 리셋)이 걸릴 때 **전량이 아니라 이 비중만** 매도한다(null = 현행 전량).
     *
     * 잔여는 그 뒤 가격 게이트(손절·트레일링·익절)에만 맡기고 **보유상한을 다시 걸지 않는다** — 매일 조금씩 파는
     * 사다리가 아니라 "첫 경계에서 일부만 실현하고 나머지는 추세에 맡긴다" 정책이다. 사다리는 단일 포지션 엔진으로
     * 표현할 수 없다(거래 하나에 다리가 셋 이상 생긴다).
     */
    val holdLimitSellFraction: Double? = null,
    /**
     * [holdLimitSellFraction] 의 잔여를 **첫 경계로부터 N봉 뒤 경계에서 전량** 청산한다(null = 잔여는 상한 면제).
     *
     * null 로 두면 f 축이 "현행 전량"으로 수렴하지 않는다 — 잔여가 상한을 영영 벗어나 사실상 *폐지 + 조기 일부 실현*
     * 이 되고, 실제로 거래수가 폐지와 정확히 같아진다(실측 42/42 셀). N=1 이면 "오늘 f, 내일 나머지" 2단 사다리라
     * f→1 에서 현행에, f→0 에서 연장 2일에 수렴해 축이 해석 가능해진다.
     */
    val holdLimitRemainderBoundaryDays: Int? = null,
) {
    init {
        // 음수면 reentryDueAt < i 가 되어 조용히 cooldown=1 처럼 동작하고, 거대값이면 `i + cooldown` 이
        // 오버플로해 reentryDueAt 이 음수가 되면서 쿨다운이 통째로 사라진다. 셋 다 결과가 그럴듯해 보여
        // 측정이 조용히 망가지므로 생성 시점에 막는다. 상한 365 는 maxHoldDays 관례와 같다
        // (StrategyController 의 coerceIn(1, 365)).
        require(reentryCooldownBars in 0..MAX_COOLDOWN_BARS) {
            "reentryCooldownBars must be in 0..$MAX_COOLDOWN_BARS, was $reentryCooldownBars"
        }
        require(reentryMode == ReentryMode.LIVE_SAME_BAR || reentryCooldownBars == 0) {
            "reentryCooldownBars has no effect in $reentryMode — set LIVE_SAME_BAR or leave it 0"
        }
        require(marketFilterMaPeriod in 1..BacktestEngine.MIN_CANDLES) {
            "marketFilterMaPeriod must be in 1..${BacktestEngine.MIN_CANDLES} (engine window is fixed at that size), was $marketFilterMaPeriod"
        }
        require(atrStopMultiplier == null || atrStopMultiplier > 0) { "atrStopMultiplier must be positive, was $atrStopMultiplier" }
        require(atrTakeProfitR == null || atrStopMultiplier != null) { "atrTakeProfitR needs atrStopMultiplier — R is measured in stop widths" }
        require(atrTakeProfitR == null || atrTakeProfitR > 0) { "atrTakeProfitR must be positive, was $atrTakeProfitR" }
        require((partialTakeProfitPct == null) == (partialTakeProfitFraction == null)) {
            "partialTakeProfitPct and partialTakeProfitFraction are set together or not at all"
        }
        require(partialTakeProfitPct == null || partialTakeProfitPct > 0) { "partialTakeProfitPct must be positive, was $partialTakeProfitPct" }
        // 부분 익절선이 전량 익절선 이상이면 같은 봉에서 두 다리를 모두 인정해 pnl 이 이중계상된다
        // (가격은 낮은 선을 먼저 통과하므로 실제로는 전량 청산이 먼저다). ATR 모드의 익절선은 진입 ATR 에
        // 달려 있어 여기서 못 보므로 IntrabarExitModel 쪽에서 가격으로 한 번 더 막는다.
        require(partialTakeProfitPct == null || atrStopMultiplier != null || takeProfitPct > partialTakeProfitPct) {
            "partialTakeProfitPct($partialTakeProfitPct) must be below takeProfitPct($takeProfitPct) — otherwise the full exit fires first"
        }
        require(partialTakeProfitFraction == null || partialTakeProfitFraction in PARTIAL_FRACTION_RANGE) {
            "partialTakeProfitFraction must be in $PARTIAL_FRACTION_RANGE (0 or 1 은 부분 익절이 아니다), was $partialTakeProfitFraction"
        }
        require(holdLimitSellFraction == null || holdLimitSellFraction in PARTIAL_FRACTION_RANGE) {
            "holdLimitSellFraction must be in $PARTIAL_FRACTION_RANGE, was $holdLimitSellFraction"
        }
        // 부분 청산이 둘이면 한 거래에 다리가 셋이 되어 가중 합성(2다리)으로 표현할 수 없다.
        require(holdLimitSellFraction == null || partialTakeProfitPct == null) {
            "holdLimitSellFraction and partialTakeProfitPct cannot be combined — one trade would need three legs"
        }
        // 조건부 상한(손실이면 넘김)과 부분 상한(일부만 판다)은 같은 경계에서 서로 다른 정책이다.
        require(holdLimitRemainderBoundaryDays == null || holdLimitSellFraction != null) {
            "holdLimitRemainderBoundaryDays only means something with holdLimitSellFraction"
        }
        require(holdLimitRemainderBoundaryDays == null || holdLimitRemainderBoundaryDays >= 1) {
            "holdLimitRemainderBoundaryDays must be >= 1, was $holdLimitRemainderBoundaryDays"
        }
        require(holdLimitSellFraction == null || !holdLimitOnlyWhenProfitable) {
            "holdLimitSellFraction and holdLimitOnlyWhenProfitable are two different policies for the same boundary"
        }
    }

    companion object {
        /** `i + cooldown` 오버플로 방지 + 현실적 상한. `maxHoldDays` 와 같은 범위를 쓴다. */
        const val MAX_COOLDOWN_BARS = 365

        /** 부분 익절 비중 — 0(안 팜)·1(전량)은 부분 익절이 아니라 다른 정책이라 배제한다. */
        val PARTIAL_FRACTION_RANGE = 0.05..0.95
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
    /**
     * 부분 익절이 있었던 거래면 그 비중(0<f<1). null = 전량 진입·전량 청산.
     *
     * 이 값이 null 이 아니면 [pnlPercent] 는 두 다리의 **가중 합성**이라 `(sellPrice−buyPrice)/buyPrice − 수수료`
     * 와 일치하지 않는다. 그 불변식을 기대하는 소비자(equity 곡선·승률·M1 대조)가 합성 레코드를 구분할 수 있어야 한다.
     *
     * 아래 셋은 리서치 전용이라 `/backtest` 응답에는 싣지 않는다(계약 무변경).
     */
    @get:JsonIgnore
    val partialFraction: Double? = null,
    /** 부분 익절이 체결된 봉 인덱스. 그 전 구간의 투입 비중은 1.0, 이후는 `1 − f` 다. */
    @get:JsonIgnore
    val partialIndex: Int? = null,
    /** 부분 익절 다리의 net pnl%(왕복 수수료 차감). 체결 시점에 확정되므로 그 이후 구간에서는 실현분이다. */
    @get:JsonIgnore
    val partialLegPnlPct: Double? = null,
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
        // internal: 전략의 minCandles 상한을 테스트가 이 값으로 고정한다(리터럴 중복 방지).
        internal const val MIN_CANDLES = 50
        /** ATR 손절·익절이 보는 기간. 리서치 노브라 config 로 빼지 않는다(축을 하나 더 늘릴 근거가 없다). */
        internal const val ATR_PERIOD = 14
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
        // 상한이 걸려도 손실이면 청산하지 않는 정책(#128 2안)은 M1 replay 와 공유해야 하므로
        // 판정식은 IntrabarExitModel 이 소유한다. 억제되면 이 봉은 일반 보유 구간처럼 평가된다 —
        // open 으로 눌리지 않고 high/low 를 그대로 본다.
        val rawAtHoldLimit = holdDays >= ExitGates.effectiveMaxHoldDays(config.maxHoldDays) && !state.holdLimitConsumed
        // 부분 보유상한: 경계 시각(bar.open = 라이브 09:00)에 f 만 실현하고, 잔여는 **그 봉의 전 구간을 정상으로**
        // 겪는다. 여기서 open 으로 눌러버리면 잔여가 그날의 고저를 못 겪어 측정이 낙관/비관 어느 쪽으로든 틀어진다.
        val fraction = config.holdLimitSellFraction
        if (fraction != null && rawAtHoldLimit && !state.partialTaken) {
            state.partialTaken = true
            state.holdLimitConsumed = true
            state.partialIndex = index
            state.partialLegPnl = ((bar.openingPrice - buyPrice) / buyPrice) * 100.0 - (config.feeRate * 2 * 100)
        }
        // 잔여 경계가 설정돼 있으면 첫 경계로부터 N봉 뒤에 전량 청산한다 — 그래야 f 축이 현행(f→1)과
        // 연장(f→0) 사이를 실제로 보간한다. 없으면 잔여는 가격 게이트에만 맡긴다.
        val remainderBoundary = fraction != null && state.partialTaken &&
            config.holdLimitRemainderBoundaryDays?.let { index - state.partialIndex >= it } == true
        val atHoldLimit = if (fraction == null) {
            IntrabarExitModel.holdLimitFires(bar, buyPrice, rawAtHoldLimit, config)
        } else {
            remainderBoundary
        }

        // 청산 판정은 IntrabarExitModel 로 위임 — D1 백테와 M1 replay 가 동일 게이트식을 공유(편향 정합).
        // armPeak 은 이 봉 high 반영 전 peak(트레일링 arm 팬텀 방지), peak 갱신은 다음 봉 판정용.
        val armPeak = state.peakPrice
        state.peakPrice = IntrabarExitModel.updatedPeak(state.peakPrice, bar, atHoldLimit)
        val chartExitSignal = config.chartExitEnabled && !atHoldLimit &&
            strategy.shouldSell(window, bar.tradePrice, signalProps)
        val levels = IntrabarExitModel.exitLevels(buyPrice, config, state.entryAtr)
        val decision = IntrabarExitModel.evaluate(bar, buyPrice, armPeak, atHoldLimit, config, chartExitSignal, levels)

        // 부분 익절은 전량 청산(트레일링·손절)이 없을 때만 이 봉에서 체결된다 — 같은 봉에서 둘 다 닿았으면
        // 손절이 이긴다(순서 불명 시 보수 쪽). 전량 익절과 겹치면 부분이 먼저 체결되고 잔여가 익절선에서 나간다.
        // 전량 청산이 부분 익절선 **이하**에서 나면 가격이 그 선을 먼저 통과한 것이므로 부분은 체결되지 않는다.
        // 손절·트레일링은 물론이고, 익절선이 부분선보다 낮은 조합(ATR 모드에서 가능)도 이 비교가 함께 막는다.
        val fullExitBeforePartial = decision != null && (
            decision.reason == "TRAILING_STOP" || decision.reason == "STOP_LOSS" ||
                (levels.partialTakeProfitPrice != null && decision.sellPrice <= levels.partialTakeProfitPrice)
            )
        if (!state.partialTaken && !fullExitBeforePartial && IntrabarExitModel.partialTakeProfitFires(bar, atHoldLimit, levels)) {
            val partialPrice = levels.partialTakeProfitPrice!!
            state.partialTaken = true
            state.partialIndex = index
            state.partialLegPnl = ((partialPrice - buyPrice) / buyPrice) * 100.0 - (config.feeRate * 2 * 100)
        }

        val (reason, sellPrice) = decision?.let { it.reason to it.sellPrice } ?: return null

        val remainderPnl = ((sellPrice - buyPrice) / buyPrice) * 100.0 - (config.feeRate * 2 * 100)
        val bankedFraction = if (state.partialTaken) (config.partialTakeProfitFraction ?: config.holdLimitSellFraction) else null
        val netPnl = bankedFraction?.let { it * state.partialLegPnl + (1 - it) * remainderPnl } ?: remainderPnl
        state.balance *= (1 + netPnl / 100.0)
        state.trades.add(
            BacktestTrade(
                state.buyIndex, index, buyPrice, sellPrice, netPnl, holdDays, reason,
                bankedFraction, bankedFraction?.let { state.partialIndex }, bankedFraction?.let { state.partialLegPnl },
            ),
        )
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
            val ma = Indicators.calculateMa(window, min(config.marketFilterMaPeriod, window.size))
            if (ma > 0 && signalPrice < ma) return false
        }

        // 신호는 window 최신 봉 종가(signalPrice)로 판단, 체결가는 fillPrice.
        // 기존 규약은 신호 봉 i → 체결 i+1 시가, LIVE_SAME_BAR 재진입은 신호 봉 i-1 → 체결 i 시가다.
        if (!strategy.shouldBuy(window, signalPrice, signalProps)) return false
        // ATR 손절을 쓰는데 ATR 이 0(무변동 구간)이면 손절선이 진입가와 같아져 진입 즉시 청산된다 — 그런 봉은 건너뛴다.
        val entryAtr = if (config.atrStopMultiplier != null) Indicators.calculateAtr(window, ATR_PERIOD) else null
        if (config.atrStopMultiplier != null && (entryAtr == null || entryAtr <= 0.0)) return false
        state.entryAtr = entryAtr
        state.partialTaken = false
        state.holdLimitConsumed = false
        state.partialIndex = -1
        state.partialLegPnl = 0.0
        state.buyPrice = fillPrice
        state.peakPrice = fillPrice
        state.buyIndex = fillIndex
        state.position = true
        return true
    }

    private fun closeOpenPosition(state: SimulationState, chronological: List<Candle>, config: BacktestConfig) {
        if (!state.position) return
        val lastPrice = chronological.last().tradePrice
        val remainderPnl = ((lastPrice - state.buyPrice) / state.buyPrice) * 100.0 - (config.feeRate * 2 * 100)
        // 구간 끝 강제 청산도 부분 익절을 반영한다 — 빠뜨리면 마지막 거래만 합성 규칙이 달라진다.
        val fraction = if (state.partialTaken) (config.partialTakeProfitFraction ?: config.holdLimitSellFraction) else null
        val pnl = fraction?.let { it * state.partialLegPnl + (1 - it) * remainderPnl } ?: remainderPnl
        state.balance *= (1 + pnl / 100.0)
        state.trades.add(
            BacktestTrade(
                state.buyIndex, chronological.size - 1, state.buyPrice, lastPrice, pnl,
                chronological.size - 1 - state.buyIndex, "END",
                fraction, fraction?.let { state.partialIndex }, fraction?.let { state.partialLegPnl },
            ),
        )
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
        /** 진입 시점에 고정한 ATR — 보유 중 재계산하면 추적형 스탑이 되어 트레일링과 축이 겹친다. */
        var entryAtr: Double? = null,
        var partialTaken: Boolean = false,
        /** 부분 보유상한 청산을 이미 소진했는가 — 소진 후에는 잔여에 상한을 다시 걸지 않는다. */
        var holdLimitConsumed: Boolean = false,
        var partialIndex: Int = -1,
        var partialLegPnl: Double = 0.0,
        val trades: MutableList<BacktestTrade> = mutableListOf(),
        val returns: MutableList<Double> = mutableListOf(),
    )
}
