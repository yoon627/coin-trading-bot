package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import java.time.LocalDateTime
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [M1ReplayEngine.replayPolicy] 가 [BacktestEngine] 과 **같은 경계 정책**을 도는지 실 fixture 로 가둔다.
 *
 * 두 구현이 갈리면 컴파일도 테스트도 통과하면서 조용히 다른 정책을 재게 된다 — `IntrabarExitModel` 이
 * 존재하는 이유와 같은 함정이고, 조건부·부분은 상태를 들고 있어 그 위험이 더 크다.
 *
 * 검사 방법: 일봉을 **그대로 일중 시계열로 넘긴다**. 봉 하나가 하루이므로 replay 의 경계 집합과 엔진의
 * `holdDays >= limit` 이 정확히 같은 봉을 가리켜야 하고, 그러면 두 구현의 청산이 봉·사유·금액까지 일치해야 한다.
 */
class HoldLimitPolicyReplayEquivalenceTest {

    private val props = TradingProperties()

    /** 일봉 fixture 는 `candle_date_time_utc` 가 없다(일봉 정규화가 kst 만 남긴다) — 경계 09:00 KST = 같은 날 00:00 UTC. */
    private fun withUtc(daily: List<Candle>): List<Candle> = daily.map {
        it.copy(candleDateTimeUtc = it.candleDateTimeKst.substring(0, 10) + "T00:00:00")
    }

    private fun base() = StrategySearchGrid.baselinePoint().toConfig()

    @Test
    fun `replayPolicy reproduces the engine exit for every boundary policy`() = runBlocking {
        val policies = listOf<Pair<String, BacktestConfig>>(
            "전량 h1 (현행)" to base(),
            "조건부 — 손실이면 넘김" to base().copy(holdLimitOnlyWhenProfitable = true),
            "부분 50% + 익일 잔여" to base().copy(holdLimitSellFraction = 0.5, holdLimitRemainderBoundaryDays = 1),
            "연장 3일" to base().copy(maxHoldDays = 3),
            "폐지 — 가격 게이트만" to base().copy(maxHoldDays = 365),
        )
        var compared = 0
        for (market in listOf("KRW-BTC", "KRW-XRP", "KRW-DOGE", "KRW-ETH", "KRW-SOL")) {
            val chronological = withUtc(YearlyFixtures.load(market).reversed())
            for ((label, config) in policies) {
                val engine = BacktestEngine(listOf(YearlyStrategyComparison.ALL_STRATEGIES.first { it.name == "combined" }), props)
                val m = SwingMetrics.measure(engine, "combined", market, chronological, BacktestEngine.MIN_CANDLES, config)
                val bars = chronological.drop(BacktestEngine.MIN_CANDLES)
                for (t in m.trades) {
                    // 구간 끝 강제 청산은 replay 에 대응이 없다(엔진 바깥의 규약이다).
                    if (t.reason == "END") continue
                    val entryUtc = LocalDateTime.parse(bars[t.buyIndex].candleDateTimeUtc)
                    val limit = ExitGatesLimit(config.maxHoldDays)
                    val boundaries = bars.drop(t.buyIndex + limit).map { LocalDateTime.parse(it.candleDateTimeUtc) }.toSet()
                    val r = M1ReplayEngine.replayPolicy(
                        t.buyPrice, entryUtc, boundaries, bars.drop(t.buyIndex), config,
                    )
                    val exit = requireNotNull(r.exit) { "$market/$label: 진입 $entryUtc 의 replay 청산이 없다" }
                    assertEquals(t.reason, exit.reason, "$market/$label 진입 $entryUtc 사유")
                    assertTrue(abs(t.sellPrice - exit.sellPrice) < 1e-6) { "$market/$label 청산가 ${t.sellPrice} vs ${exit.sellPrice}" }
                    assertTrue(abs(t.pnlPercent - exit.netPnlPct) < 1e-9) { "$market/$label pnl ${t.pnlPercent} vs ${exit.netPnlPct}" }
                    compared++
                }
            }
        }
        // 0건이면 "전부 통과" 가 아니라 "아무것도 안 쟀다" 다.
        assertTrue(compared > 200) { "비교 건수 $compared — 표본이 너무 적어 등가성을 주장할 수 없다" }
        println("[equivalence] 정책 ${policies.size}종 × 마켓 5 = 거래 ${compared}건 일치")
    }

    private fun ExitGatesLimit(maxHoldDays: Int) = com.trading.common.strategy.ExitGates.effectiveMaxHoldDays(maxHoldDays)
}
