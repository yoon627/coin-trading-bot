package com.trading.bot.engine

import com.trading.common.domain.Ohlc
import com.trading.common.strategy.ExitGates
import kotlin.math.max

data class ExitDecision(val reason: String, val sellPrice: Double)

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
    fun evaluate(
        bar: Ohlc,
        buyPrice: Double,
        armPeak: Double,
        atHoldLimit: Boolean,
        config: BacktestConfig,
        chartExitSignal: Boolean,
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
        return when {
            ExitGates.isTrailingStopTriggered(pnlAtTrailStop, armPeakPnl, dropFromArmPeakAtLow, config.trailingStopPct, config.trailingArmPct) ->
                ExitDecision("TRAILING_STOP", trailStopPrice)
            pnlAtLow <= -config.maxLossPct ->
                ExitDecision("STOP_LOSS", buyPrice * (1 - config.maxLossPct / 100.0))
            pnlAtHigh >= config.takeProfitPct ->
                ExitDecision("TAKE_PROFIT", buyPrice * (1 + config.takeProfitPct / 100.0))
            config.chartExitEnabled && !atHoldLimit && chartExitSignal ->
                ExitDecision("CHART_EXIT", bar.close)
            atHoldLimit ->
                ExitDecision("TIME_EXIT", bar.open)
            else -> null
        }
    }

    /** 이 봉까지 반영한 새 peak — 다음 봉 [evaluate] 의 armPeak 로 사용. */
    fun updatedPeak(prevPeak: Double, bar: Ohlc, atHoldLimit: Boolean): Double =
        max(prevPeak, if (atHoldLimit) bar.open else bar.high)
}
