package com.trading.bot.strategy

import com.trading.bot.engine.BacktestFixtures
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.KneePullback
import com.trading.common.strategy.KneeReversal
import com.trading.common.strategy.ShoulderExit
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 백테는 전략에 정확히 50봉을, 라이브는 21~60봉을 넘긴다. `calculateRsi` 가 리스트 전체로 Wilder
 * smoothing 을 돌기 때문에 슬라이스가 없으면 같은 시점인데도 판정이 갈린다.
 *
 * 합성 캔들로는 이 차이가 임계(35/55·40/60)를 넘는 일이 드물어 통과해 버리므로, 실제 8마켓 fixture 의
 * **모든 인덱스**를 순회해 한 건이라도 어긋나면 실패하게 한다.
 */
class KneeRsiWindowTest {

    private val reversal = KneeReversal()
    private val pullback = KneePullback()
    private val config = TradingProperties()

    /** 시간순 index [end] 에서 뒤를 [size] 만큼 본 window(최신순). */
    private fun window(chronological: List<Candle>, end: Int, size: Int): List<Candle> =
        chronological.subList(end - size + 1, end + 1).reversed()

    @Test
    fun `entry decision does not depend on how many candles the caller passes`() = runTest {
        var checked = 0
        val mismatches = mutableListOf<String>()

        for ((market, candles) in BacktestFixtures.loadAll()) {
            val chronological = candles.reversed()
            for (end in 59 until chronological.size) {
                val w50 = window(chronological, end, 50)
                val w60 = window(chronological, end, 60)
                val price = w50[0].tradePrice

                val rev50 = reversal.shouldBuy(w50, price, config)
                val rev60 = reversal.shouldBuy(w60, price, config)
                if (rev50 != rev60) mismatches += "$market@$end knee_reversal $rev50/$rev60"

                val pb50 = pullback.shouldBuy(w50, price, config)
                val pb60 = pullback.shouldBuy(w60, price, config)
                if (pb50 != pb60) mismatches += "$market@$end knee_pullback $pb50/$pb60"

                checked++
            }
        }

        assertEquals(
            emptyList<String>(),
            mismatches.take(10),
            "window 길이에 따라 진입 판정이 갈린다 (검사 $checked 건, 불일치 ${mismatches.size} 건)",
        )
    }

    @Test
    fun `shoulder exit does not depend on how many candles the caller passes`() {
        val mismatches = mutableListOf<String>()

        for ((market, candles) in BacktestFixtures.loadAll()) {
            val chronological = candles.reversed()
            for (end in 59 until chronological.size) {
                // 41 은 ShoulderExit 이 두 epoch 를 모두 40봉으로 잡는 최소치.
                val verdicts = listOf(41, 50, 60).map { ShoulderExit.isTriggered(window(chronological, end, it)) }
                if (verdicts.distinct().size > 1) mismatches += "$market@$end $verdicts"
            }
        }

        assertEquals(
            emptyList<String>(),
            mismatches.take(10),
            "window 길이에 따라 어깨 청산 판정이 갈린다 (불일치 ${mismatches.size} 건)",
        )
    }
}
