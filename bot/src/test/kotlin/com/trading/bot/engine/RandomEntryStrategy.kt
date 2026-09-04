package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.Indicators
import com.trading.common.strategy.TradingStrategy

/**
 * null 대조군 — 신호를 **무작위 진입**으로 대체해 "게이트를 통과했다"가 잡음과 구분되는지 재는 데 쓴다.
 * 수천 개 후보 중 최고는 정의상 항상 존재하므로, 이 대조군이 없으면 리포트가 스스로를 반증하지 못한다.
 *
 * **상태 있는 RNG 를 쓰면 안 된다.** 엔진은 flat 인 봉에서만 `shouldBuy` 를 부르므로 호출 시점·횟수가 exit config 에
 * 따라 달라진다. 순차 RNG 면 config 마다 대조군의 진입 시점 자체가 바뀌어 비교 불가능해지고, 신호 캐시와도 충돌한다.
 * 그래서 진입은 `(seed, market, 봉)` 의 **순수 해시 함수**다.
 */
internal class RandomEntryStrategy(
    private val seed: Int,
    private val market: String,
    /** 원시 신호 발생률 — baseline 전략을 전 봉에서 평가해 구한 값을 넣는다([StrategySearch.signalRate]). */
    private val entryRate: Double,
) : TradingStrategy {

    override val name: String = NAME

    override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties): Boolean =
        unitInterval(seed, market, candles.first().candleDateTimeKst) < entryRate

    /** 청산 신호는 baseline 과 같은 기본 데드크로스를 쓴다(진입만 무작위화하는 것이 이 대조군의 정의). */
    override suspend fun shouldSell(candles: List<Candle>, currentPrice: Double, config: TradingProperties): Boolean =
        Indicators.checkDeadCross(candles, 5, 20)

    companion object {
        const val NAME = "random_entry"

        /** splitmix64 최종 믹싱 — 같은 (seed, market, 봉)이면 항상 같은 값. */
        fun unitInterval(seed: Int, market: String, bar: String): Double {
            var h = -0x61c8864680b583ebL * (seed + 1L)
            for (s in arrayOf(market, bar)) {
                for (c in s) h = (h xor c.code.toLong()) * 0x100000001b3L
                h = h * 31 + 0x9E3779B9L
            }
            var z = h
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            return (z ushr 11).toDouble() / (1L shl 53).toDouble()
        }
    }
}
