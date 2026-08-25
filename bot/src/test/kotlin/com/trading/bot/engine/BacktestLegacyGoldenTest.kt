package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.VolatilityBreakout
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `BacktestConfig()` 기본값의 fixture 실데이터 결과를 trade 단위로 고정한다(#128 A3b).
 *
 * 재진입 모델(`ReentryMode`)은 기본값이 [ReentryMode.LEGACY_NEXT_BAR] 라 기존 결과를 바꾸지 않아야 하는데,
 * 단위 테스트 통과만으로는 그걸 못 덮는다 — 실제로 도입 직전 커밋(`db48763`)과 이 골든이 바이트 동일함을
 * 대조해 확인했다. 기본값을 바꾸면 `M1ReplayBiasTest`·`ParameterSweepTest`·`/backtest` 호출자의 모집단이
 * 조용히 달라지므로, 그 변경이 여기서 먼저 빨갛게 뜨도록 둔다.
 *
 * 의도적으로 기본값을 바꿀 때만 재생성한다:
 *   `GOLDEN_OUT=bot/src/test/resources/backtest/legacy-golden.txt ./gradlew :bot:test --tests "*LegacyGolden*"`
 */
class BacktestLegacyGoldenTest {

    private val engine = BacktestEngine(listOf(VolatilityBreakout(), CombinedStrategy()), TradingProperties())

    @Test
    fun `default config results match committed golden`() = runBlocking {
        val actual = renderAllFixtures()

        val regenerateTo = System.getenv("GOLDEN_OUT")
        if (regenerateTo != null) {
            File(regenerateTo).apply { parentFile?.mkdirs() }.writeText(actual)
            println("golden regenerated: $regenerateTo")
            return@runBlocking
        }

        val expected = requireNotNull(javaClass.getResourceAsStream("/backtest/legacy-golden.txt")) {
            "golden fixture 없음"
        }.use { it.reader().readText() }

        assertEquals(expected, actual, "BacktestConfig() 기본값 결과가 골든과 다르다 — 의도한 변경이면 GOLDEN_OUT 로 재생성")
    }

    private suspend fun renderAllFixtures(): String {
        val sb = StringBuilder()
        for (regime in BacktestFixtures.Regime.values()) {
            for (market in BacktestFixtures.markets(regime)) {
                val candles = BacktestFixtures.load(regime, market)
                for (strategy in listOf("volatility_breakout", "combined")) {
                    val r = engine.run(strategy, candles, market, BacktestConfig())
                    if (r == null) {
                        sb.appendLine("${regime.dir}\t$market\t$strategy\tNULL")
                        continue
                    }
                    sb.appendLine(
                        "${regime.dir}\t$market\t$strategy\ttrades=${r.totalTrades}\t" +
                            "total=%.6f\twin=%.6f\tmdd=%.6f".format(r.totalReturnPct, r.winRate, r.maxDrawdownPct),
                    )
                    r.trades.forEach { t ->
                        sb.appendLine(
                            "\t%d\t%d\t%.8f\t%.8f\t%.8f\t%d\t%s".format(
                                t.buyIndex, t.sellIndex, t.buyPrice, t.sellPrice, t.pnlPercent, t.holdDays, t.reason,
                            ),
                        )
                    }
                }
            }
        }
        return sb.toString()
    }
}
