package com.trading.common.config

import com.trading.common.strategy.LadderParams
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 적립(사다리) 프로파일 설정. `tickers` 가 비어 있으면 꺼진 것이고 기본값이 그렇다 —
 * 머지가 곧 배포이므로 `.env` 에 `TRADING_ACCUMULATE_TICKERS` 를 넣기 전에는 동작이 바뀌지 않는다.
 * 전역 설정이라 모든 사용자 엔진에 같은 예산이 적용된다. `tickers` 는 Upbit 마켓코드(`KRW-BTC`) 전용이다 —
 * 다른 거래소에 사다리를 붙이려면 그쪽 prefix 의 설정을 따로 둔다(`AccumulateLadder` 자체는 거래소 중립).
 */
@ConfigurationProperties(prefix = "trading.accumulate")
data class AccumulateProperties(
    val tickers: String = "",
    val budgetKrw: Double = 100_000.0,
    val maxRungs: Int = 5,
    val stepDownPct: Double = 3.0,
    val stepUpPct: Double = 3.0,
) {
    // LadderParams 의 init 이 단당 금액·부호를 검증한다 — 꺼져 있어도 기동 시 잘못된 값을 드러낸다.
    private val params = LadderParams(budgetKrw, maxRungs, stepDownPct, stepUpPct)

    fun tickerList(): List<String> = tickers.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()

    fun ladderParams(): LadderParams = params
}
