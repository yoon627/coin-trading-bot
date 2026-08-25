package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.VolatilityBreakout
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * `reentryCooldownBars=2` 는 legacy 규약과 **정확히 같아야 한다**.
 *
 * legacy: TIME_EXIT 봉 `E` → 반복 `E+1` 에서 신호(봉 `E+1` 종가) → 체결 봉 `E+2` 시가.
 * cooldown=2: `reentryDueAt = E+2` → 반복 `E+1` 차단 → 반복 `E+2` 에서 신호(봉 `E+1` 종가) → 체결 봉 `E+2` 시가.
 *
 * 같은 사건이므로 두 설정의 trade 리스트가 일치해야 한다. 어긋나면 쿨다운 경로가 legacy 에 없는
 * 부수효과를 넣었다는 뜻이고, 그러면 `cooldown-N` 팔로 잰 #128 반사실이 정책 차이가 아니라
 * 구현 아티팩트를 재게 된다. 실데이터 fixture 로 검증한다 — 합성 캔들은 이 종류의 누락을 못 잡는다.
 */
class BacktestReentryEquivalenceTest {

    private val engine = BacktestEngine(listOf(VolatilityBreakout(), CombinedStrategy()), TradingProperties())

    @Test
    fun `config rejects cooldown values that would silently disable the cooldown`() {
        // 음수는 reentryDueAt < i 라 다음 봉에 바로 풀리고, Int.MAX_VALUE 는 i + cooldown 이 오버플로해
        // reentryDueAt 이 음수가 되면서 쿨다운이 통째로 사라진다. 둘 다 예외 없이 '그럴듯한' 결과를 내므로
        // 생성 시점에 막혀야 한다 — 측정 도구에서 조용한 오작동이 가장 위험하다.
        val live = { n: Int ->
            BacktestConfig(maxHoldDays = 1, reentryMode = ReentryMode.LIVE_SAME_BAR, reentryCooldownBars = n)
        }
        assertThrows(IllegalArgumentException::class.java) { live(-1) }
        assertThrows(IllegalArgumentException::class.java) { live(Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { live(BacktestConfig.MAX_COOLDOWN_BARS + 1) }
        live(BacktestConfig.MAX_COOLDOWN_BARS) // 경계값은 허용

        // LEGACY 모드에서 쿨다운을 주는 것은 조용히 무시되므로 이것도 막는다.
        assertThrows(IllegalArgumentException::class.java) {
            BacktestConfig(reentryMode = ReentryMode.LEGACY_NEXT_BAR, reentryCooldownBars = 3)
        }
    }

    @Test
    fun `cooldown 2 reproduces legacy exactly on real fixtures`() = runBlocking {
        val legacy = BacktestConfig(maxHoldDays = 1)
        val cooldown2 = BacktestConfig(
            maxHoldDays = 1,
            reentryMode = ReentryMode.LIVE_SAME_BAR,
            reentryCooldownBars = 2,
        )

        for (regime in BacktestFixtures.Regime.values()) {
            for (market in BacktestFixtures.markets(regime)) {
                val candles = BacktestFixtures.load(regime, market)
                for (strategy in listOf("volatility_breakout", "combined")) {
                    val a = engine.run(strategy, candles, market, legacy)
                    val b = engine.run(strategy, candles, market, cooldown2)
                    assertEquals(
                        a?.trades,
                        b?.trades,
                        "${regime.dir}/$market/$strategy — cooldown=2 가 legacy 와 다르다",
                    )
                }
            }
        }
    }
}
