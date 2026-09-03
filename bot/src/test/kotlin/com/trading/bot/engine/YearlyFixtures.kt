package com.trading.bot.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.trading.common.domain.Candle

/**
 * 운영 티커 8종의 최근 1년(365봉) 일봉 — `scripts/collect_yearly_fixtures.py` 가 만든다.
 *
 * [BacktestFixtures] 와 같은 최신순 규약이지만 **국면별 시점 중립 유니버스가 아니라 사용자가 지정한 티커**라
 * `Regime` 에 얹지 않는다(그 enum 을 순회하는 기존 측정의 모집단이 바뀐다). 생존편향(이 8종이 1년을 살아남은 종목이라는
 * 사실)은 제거하지 못하며 결과 보고에 명시한다.
 */
internal object YearlyFixtures {
    val MARKETS = listOf("KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-DOGE", "KRW-ADA", "KRW-AVAX", "KRW-LINK")
    const val BARS = 365

    private val mapper = jacksonObjectMapper()

    /** 최신순 365봉. */
    fun load(market: String): List<Candle> {
        val path = "/backtest/yearly/$market.json"
        val stream = requireNotNull(javaClass.getResourceAsStream(path)) { "fixture 없음: $path — scripts/collect_yearly_fixtures.py --write" }
        return stream.use { mapper.readValue(it) }
    }

    fun loadAll(): Map<String, List<Candle>> = MARKETS.associateWith { load(it) }
}
