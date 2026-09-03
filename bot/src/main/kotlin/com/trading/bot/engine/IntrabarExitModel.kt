package com.trading.bot.engine

import com.trading.common.domain.Ohlc
import com.trading.common.strategy.ExitGates
import kotlin.math.max

data class ExitDecision(val reason: String, val sellPrice: Double)

/**
 * 이 포지션의 청산 임계가. **한 곳에서만 만든다** — 임계를 부르는 쪽마다 계산하면 D1 백테와 M1 replay 가
 * 서로 다른 정책으로 갈라지면서 컴파일도 테스트도 통과한다(이 object 가 존재하는 이유).
 *
 * `atrStop`/`atrTakeProfit` 이 null 이면 기존 퍼센트 게이트를 그대로 쓴다 — 퍼센트 경로의 부동소수 비교를
 * 가격 비교로 바꾸지 않는 이유는 경계에서 결과가 달라져 기존 골든(BacktestLegacyGoldenTest)이 흔들리기 때문이다.
 */
data class ExitLevels(
    val atrStopPrice: Double? = null,
    val atrTakeProfitPrice: Double? = null,
    val partialTakeProfitPrice: Double? = null,
    val partialFraction: Double? = null,
)

/**
 * 백테/replay 공용 intrabar 청산 판정(#33). D1 백테(봉당 1회)와 M1 replay(보유구간 봉마다 순차)가
 * 동일 게이트식을 쓰게 해 편향 측정의 정합성을 코드 레벨에서 보장한다.
 * 라이브는 intrabar 근사를 쓰지 않으므로(10초 tick) 이 자산은 백테/replay 도메인 전용.
 */
object IntrabarExitModel {
    /**
     * 단일 봉(OHLC)+진입상태 → 청산 판정. 반환 null = 이 봉에서 청산 없음.
     *
     * 봉 종가만 보면 봉 내 고저 도달을 놓쳐 가격 게이트가 낙관 편향이므로 SL/트레일링은 low, TP 는 high 로 판정.
     * 무슬리피지·무갭 가정(체결가=게이트 임계선).
     *
     * @param armPeak 이 봉 high 반영 '전' 고점. 트레일링 arm 판정 전용 — 같은 봉 신고점(high)과 저점(low)이
     *   함께 나올 때 이 봉 high 로 arm 하면 SL 손실을 팬텀 트레일링 이익으로 오기록하는 낙관 편향(#33 역행)을 막는다.
     * @param atHoldLimit 한도봉 여부(caller 판단: D1=holdDays>=limit, M1=시각>=limitInstant). true 면
     *   high=low=open 으로 눌러 한도봉 open-only 특례를 재현(라이브 09:00 리셋 시 intraday 미노출).
     * @param chartExitSignal caller 가 suspend shouldSell 을 미리 평가해 주입(이 함수의 순수성 유지).
     */
    /**
     * 진입가와 config(+진입 시점 ATR)로 이 포지션의 임계가를 만든다.
     *
     * ATR 모드는 `atrStopMultiplier` 가 있을 때만이고, 그때 [entryAtr] 은 **양수여야 한다** —
     * ATR 이 0(무변동 구간)이면 손절선이 진입가와 같아져 진입 즉시 청산되는 사고가 난다. 호출부가 그런 봉에서는
     * 아예 진입하지 않도록 막고, 여기서는 계약으로 확인한다.
     */
    fun exitLevels(buyPrice: Double, config: BacktestConfig, entryAtr: Double? = null): ExitLevels {
        val stopDistance = config.atrStopMultiplier?.let { multiplier ->
            require(entryAtr != null && entryAtr > 0) { "ATR exit levels need a positive entry ATR, was $entryAtr" }
            multiplier * entryAtr
        }
        return ExitLevels(
            atrStopPrice = stopDistance?.let { buyPrice - it },
            atrTakeProfitPrice = stopDistance?.let { d -> config.atrTakeProfitR?.let { buyPrice + it * d } },
            partialTakeProfitPrice = config.partialTakeProfitPct?.let { buyPrice * (1 + it / 100.0) },
            partialFraction = config.partialTakeProfitFraction,
        )
    }

    /**
     * 부분 익절이 이 봉에서 체결되는가. **전량 청산(트레일링·손절)이 먼저 판정된 뒤에만** 물어야 한다 —
     * 같은 봉에서 high 가 부분 익절선을, low 가 손절선을 함께 건드렸을 때 부분 익절을 먼저 인정하면 pnl 이
     * 직접 부풀려진다(기존 계약이 순서 불명 시 손절 우선인 것과 같은 이유로 보수 쪽을 택한다).
     */
    fun partialTakeProfitFires(bar: Ohlc, atHoldLimit: Boolean, levels: ExitLevels): Boolean {
        val target = levels.partialTakeProfitPrice ?: return false
        val high = if (atHoldLimit) bar.open else bar.high
        return high >= target
    }

    fun evaluate(
        bar: Ohlc,
        buyPrice: Double,
        armPeak: Double,
        atHoldLimit: Boolean,
        config: BacktestConfig,
        chartExitSignal: Boolean,
        levels: ExitLevels = ExitLevels(),
    ): ExitDecision? {
        val high = if (atHoldLimit) bar.open else bar.high
        val low = if (atHoldLimit) bar.open else bar.low
        val pnlAtLow = ((low - buyPrice) / buyPrice) * 100.0
        val pnlAtHigh = ((high - buyPrice) / buyPrice) * 100.0
        val armPeakPnl = ((armPeak - buyPrice) / buyPrice) * 100.0
        val dropFromArmPeakAtLow = ((armPeak - low) / armPeak) * 100.0

        // 트레일링 체결선 기준 pnl — 라이브가 트레일링을 거는 순간(현재가≈트레일링선)의 pnl 과 일치시킨다.
        val trailStopPrice = armPeak * (1 - config.trailingStopPct / 100.0)
        val pnlAtTrailStop = ((trailStopPrice - buyPrice) / buyPrice) * 100.0

        // 하강 경로에서 라이브가 먼저 닿는 순서: 트레일링선(>진입가>SL선) → SL. SL↔TP 는 봉 내 순서 불명이라 SL 우선.
        // CHART_EXIT 는 종가 신호(한도봉 제외 — 09:00 리셋 시점엔 그 봉 종가 미형성). TIME_EXIT 은 한도봉 open.
        // ATR 모드면 임계가로, 아니면 기존 퍼센트 비교 그대로(부동소수 경계가 달라져 골든이 흔들리지 않게).
        val stopHit = levels.atrStopPrice?.let { low <= it } ?: (pnlAtLow <= -config.maxLossPct)
        val stopPrice = levels.atrStopPrice ?: buyPrice * (1 - config.maxLossPct / 100.0)
        val takeProfitHit = levels.atrTakeProfitPrice?.let { high >= it } ?: (pnlAtHigh >= config.takeProfitPct)
        val takeProfitPrice = levels.atrTakeProfitPrice ?: buyPrice * (1 + config.takeProfitPct / 100.0)

        return when {
            ExitGates.isTrailingStopTriggered(pnlAtTrailStop, armPeakPnl, dropFromArmPeakAtLow, config.trailingStopPct, config.trailingArmPct) ->
                ExitDecision("TRAILING_STOP", trailStopPrice)
            stopHit ->
                ExitDecision("STOP_LOSS", stopPrice)
            takeProfitHit ->
                ExitDecision("TAKE_PROFIT", takeProfitPrice)
            config.chartExitEnabled && !atHoldLimit && chartExitSignal ->
                ExitDecision("CHART_EXIT", bar.close)
            atHoldLimit ->
                ExitDecision("TIME_EXIT", bar.open)
            else -> null
        }
    }

    /**
     * 보유상한이 실제로 발동하는가. [BacktestConfig.holdLimitOnlyWhenProfitable] 이면 경계 시점 가격
     * (`bar.open` = 라이브 KST 09:00)이 진입가 이상일 때만 발동한다(#128 2안 "리셋 대상 한정").
     *
     * D1 백테와 M1 replay 가 각자 구현하면 **같은 config 로 서로 다른 정책이 돌아** 측정이 조용히
     * 어긋난다 — 이 repo 가 이미 겪은 함정이라 판정식을 여기 하나로 둔다([[exit-gates]]).
     * 게이트는 gross 로 본다(수수료는 기록에서만 차감).
     */
    fun holdLimitFires(bar: Ohlc, buyPrice: Double, atHoldLimit: Boolean, config: BacktestConfig): Boolean =
        atHoldLimit && (!config.holdLimitOnlyWhenProfitable || bar.open >= buyPrice)

    /** 이 봉까지 반영한 새 peak — 다음 봉 [evaluate] 의 armPeak 로 사용. */
    fun updatedPeak(prevPeak: Double, bar: Ohlc, atHoldLimit: Boolean): Double =
        max(prevPeak, if (atHoldLimit) bar.open else bar.high)
}
