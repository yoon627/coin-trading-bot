package com.trading.bot.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.trading.common.domain.Candle

/**
 * 실제 Upbit 일봉으로 백테를 돌리기 위한 fixture 로더.
 *
 * 데이터 출처·정규화 규칙은 `bot/src/test/resources/backtest/README.md` 참조.
 * JSON 은 API 응답과 같은 **최신순**(index 0 = 최신)이고, [BacktestEngine.run] 이 내부에서 뒤집으므로
 * 이 로더도 최신순 그대로 돌려준다. 구간을 자를 때만 시간순으로 뒤집었다가 되돌린다.
 */
internal object BacktestFixtures {

    val MARKETS = listOf(
        "KRW-XRP", "KRW-BTC", "KRW-MMT", "KRW-ETH",
        "KRW-WLD", "KRW-RVN", "KRW-ONDO", "KRW-DOGE",
    )

    private val mapper = jacksonObjectMapper()

    /** 최신순 200봉. */
    fun load(market: String): List<Candle> {
        val path = "/backtest/$market.json"
        val stream = requireNotNull(javaClass.getResourceAsStream(path)) { "fixture 없음: $path" }
        return stream.use { mapper.readValue(it) }
    }

    fun loadAll(): Map<String, List<Candle>> = MARKETS.associateWith { load(it) }

    /**
     * 시간순 [from]..[to] (양끝 포함) 구간을 잘라 최신순으로 돌려준다.
     * 입력이 최신순이므로 뒤집어 자른 뒤 다시 뒤집는다 — 이 방향을 틀리면 조용히 반대 구간을 백테하게 된다.
     */
    fun slice(candles: List<Candle>, from: Int, to: Int): List<Candle> =
        candles.reversed().subList(from, to + 1).reversed()

    /**
     * in-sample / out-of-sample 분할. 겹치는 [80..129] 는 out 쪽 워밍업으로만 쓰여 신호를 내지 않으므로
     * 두 구간의 신호는 섞이지 않는다.
     */
    fun inSample(candles: List<Candle>) = slice(candles, 0, 129)

    fun outOfSample(candles: List<Candle>) = slice(candles, 80, 199)
}
