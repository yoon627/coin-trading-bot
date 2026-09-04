package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy

/**
 * 진입 신호 memoize — 청산 파라미터를 바꿔도 `shouldBuy` 는 (전략, 마켓, kValue, 봉) 의 함수이므로 그리드 배수만큼의
 * 지표 재계산을 없앤다.
 *
 * ⚠️ **키에 마켓이 반드시 들어간다.** yearly fixture 8종은 같은 365개 날짜를 공유하므로, 마켓 없는 키는 BTC 신호를
 * ETH 에 그대로 재생하면서 컴파일도 테스트도 통과한다.
 *
 * 캐시는 **순차 실행 전용**이다(동시성 도입 안 함). 스윕을 병렬화하려면 마켓별 인스턴스를 쓰거나 동시성 자료구조로 바꿔야 한다.
 */
internal class SignalCache {

    private data class Key(
        val strategy: String,
        val market: String,
        val kValue: Double,
        val newestBar: String,
        val windowSize: Int,
        val currentPrice: Double,
    )

    private val buys = HashMap<Key, Boolean>()

    var hits = 0L
        private set
    var misses = 0L
        private set

    fun decorate(delegate: TradingStrategy, market: String): TradingStrategy = Cached(delegate, market)

    /**
     * `name`·`minCandles`·`shouldSell` 은 전부 위임한다 — `name` 을 안 넘기면 `BacktestEngine.run` 의
     * `strategies.find { it.name == strategyName }` 이 전략을 못 찾아 조용히 null 을 반환한다.
     */
    private inner class Cached(private val delegate: TradingStrategy, private val market: String) : TradingStrategy {
        override val name: String get() = delegate.name
        override val minCandles: Int get() = delegate.minCandles

        override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties): Boolean {
            val key = Key(delegate.name, market, config.kValue, candles.first().candleDateTimeKst, candles.size, currentPrice)
            buys[key]?.let { hits++; return it }
            misses++
            return delegate.shouldBuy(candles, currentPrice, config).also { buys[key] = it }
        }

        override suspend fun shouldSell(candles: List<Candle>, currentPrice: Double, config: TradingProperties): Boolean =
            delegate.shouldSell(candles, currentPrice, config)
    }
}
