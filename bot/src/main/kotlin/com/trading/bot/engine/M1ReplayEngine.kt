package com.trading.bot.engine

import com.trading.common.domain.Candle
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class M1Exit(val reason: String, val sellPrice: Double, val exitUtc: String, val minutesHeld: Int)

data class M1ReplayResult(val exit: M1Exit?, val barsSeen: Int, val reachedLimit: Boolean)

/**
 * M1(1분봉)을 tick 프록시로 삼아 한 트레이드의 청산을 replay 한다(#33 편향 실측용).
 * D1 백테와 [IntrabarExitModel] 을 공유하되, 청산 게이트를 보유구간 M1 봉마다 순차 평가해
 * "먼저 닿는 게이트"를 실제 도달 순서대로 잡는다 — D1 의 worst-case 가정이 사라지는 지점이 편향의 본체.
 * 진입가·진입시각은 D1 과 동일하게 고정(진입 변수를 격리하고 청산만 비교).
 *
 * ⚠️ chartExit 미지원: [replayExit] 은 chartExitSignal=false 로 고정 평가한다(지표 신호는 D1 종가 확정이라
 * M1 tick 프록시에 대응이 없음). 따라서 `chartExitEnabled=true` config 로 쓰면 D1 이 CHART_EXIT 낸 트레이드가
 * M1 에선 절대 CHART_EXIT 이 안 나 조용히 불일치로 잡힌다 — chartExit off config(라이브 기본)에서만 정합.
 */
object M1ReplayEngine {
    /**
     * @param m1BarsAsc 보유구간 M1 봉(candle_date_time_utc 오름차순). newest-first 응답은 caller 가 reversed.
     * @param limitInstant TIME_EXIT 경계(UTC) = 진입일 00:00Z + effectiveMaxHoldDays 일 (= KST 09:00 리셋).
     * @return exit=null 이면 데이터가 limitInstant 까지 못 미친 것(집계 제외 대상).
     */
    fun replayExit(
        entryPrice: Double,
        entryUtc: LocalDateTime,
        limitInstant: LocalDateTime,
        m1BarsAsc: List<Candle>,
        config: BacktestConfig,
    ): M1ReplayResult {
        var peak = entryPrice
        var seen = 0
        for (b in m1BarsAsc) {
            val t = LocalDateTime.parse(b.candleDateTimeUtc)
            if (t.isBefore(entryUtc)) continue
            seen++
            val atHoldLimit = !t.isBefore(limitInstant) // t >= limitInstant → 한도봉(라이브 09:00 리셋 등가)
            val armPeak = peak
            peak = IntrabarExitModel.updatedPeak(peak, b, atHoldLimit)
            val decision = IntrabarExitModel.evaluate(b, entryPrice, armPeak, atHoldLimit, config, chartExitSignal = false)
            if (decision != null) {
                val held = ChronoUnit.MINUTES.between(entryUtc, t).toInt()
                return M1ReplayResult(M1Exit(decision.reason, decision.sellPrice, b.candleDateTimeUtc, held), seen, atHoldLimit)
            }
        }
        return M1ReplayResult(exit = null, barsSeen = seen, reachedLimit = false)
    }
}
