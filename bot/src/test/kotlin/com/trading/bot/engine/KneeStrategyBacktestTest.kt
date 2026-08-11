package com.trading.bot.engine

import com.trading.bot.strategy.KneeFixtures
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.KneePullback
import com.trading.common.strategy.KneeReversal
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KneeStrategyBacktestTest {

    private val engine = BacktestEngine(listOf(KneeReversal(), KneePullback()), TradingProperties())

    @Test
    fun `runs both knee strategies over 50 bar windows without error`() = runTest {
        // 백테는 전략에 항상 정확히 50봉 window 를 넘긴다(BacktestEngine.kt:89).
        // lookback 40 설계가 그 안에서 예외 없이 계산되는지 확인한다.
        val candles = KneeFixtures.reversalThenRally()

        assertNotNull(engine.run("knee_reversal", candles, "KRW-BTC"))
        assertNotNull(engine.run("knee_pullback", candles, "KRW-BTC"))
    }

    @Test
    fun `shoulder exit fires when chart exit is enabled and hold limit allows it`() = runTest {
        // 기본 설정(chartExitEnabled=false, maxHoldDays=1)에서는 어깨 청산이 dead path 다.
        // 옵션을 켜고 보유 상한을 늘렸을 때 실제로 CHART_EXIT 이 발생하는지가 이 PR 의 핵심 증거.
        // 손절·트레일링·익절을 넓게 잡아야 그것들이 먼저 잡아먹지 않는다(우선순위 TRAILING>SL>TP>CHART).
        val candles = KneeFixtures.reversalThenRally()
        val config = BacktestConfig(
            takeProfitPct = 100.0,
            maxLossPct = 50.0,
            trailingArmPct = 60.0,
            trailingStopPct = 40.0,
            maxHoldDays = 50,
            chartExitEnabled = true,
        )

        val result = engine.run("knee_reversal", candles, "KRW-BTC", config)

        assertNotNull(result)
        assertTrue(
            result!!.trades.any { it.reason == "CHART_EXIT" },
            "어깨 청산이 한 번도 발동하지 않았다: ${result.trades.map { it.reason }}",
        )
    }

    @Test
    fun `shoulder exit stays dormant while chart exit is disabled`() = runTest {
        // chartExitEnabled 만 끈 채로 나머지는 위 테스트와 같게 둔다. maxHoldDays 를 기본값 1 로 두면
        // atHoldLimit 이 CHART_EXIT 을 먼저 차단해(IntrabarExitModel.kt:55) 이 플래그가 실제로
        // 고정되는지 알 수 없다 — 두 메커니즘을 분리해야 의미가 있다.
        val candles = KneeFixtures.reversalThenRally()
        val config = BacktestConfig(
            takeProfitPct = 100.0,
            maxLossPct = 50.0,
            trailingArmPct = 60.0,
            trailingStopPct = 40.0,
            maxHoldDays = 50,
            chartExitEnabled = false,
        )

        val result = engine.run("knee_reversal", candles, "KRW-BTC", config)

        assertNotNull(result)
        assertTrue(result!!.trades.isNotEmpty(), "매수가 없어 비교가 무의미하다")
        assertTrue(
            result.trades.none { it.reason == "CHART_EXIT" },
            "차트 청산을 껐는데 CHART_EXIT 이 나왔다: ${result.trades.map { it.reason }}",
        )
    }

    @Test
    fun `knee_pullback also produces entries in the backtest`() = runTest {
        // 눌림목 전략이 백테 경로에서 실제로 진입하는지 — 예외 없음만으로는 증거가 되지 않는다.
        val candles = KneeFixtures.pullback(bars = 95, tailBars = 5)

        val result = engine.run("knee_pullback", candles, "KRW-BTC")

        assertNotNull(result)
        assertTrue(result!!.trades.isNotEmpty(), "knee_pullback 이 백테에서 한 번도 진입하지 않았다")
    }
}
