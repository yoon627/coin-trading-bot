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
 *
 * 국면이 둘이다 — 하락장([Regime.BEAR]) 하나만 보면 "이 전략이 원래 나쁜지, 이 장에서만 나쁜지"를
 * 가를 수 없어서 상승장([Regime.BULL])을 함께 둔다.
 */
internal object BacktestFixtures {

    enum class Regime(val dir: String, val label: String) {
        /** 2026-01-31 ~ 2026-08-18. 8마켓 중 7개가 마이너스. */
        BEAR("bear", "하락장 2026-01~08"),

        /** 2023-11-23 ~ 2024-06-09. BTC +96%, ETH +89%, DOGE +102%, XRP -16%. */
        BULL("bull", "상승장 2023-11~2024-06"),
    }

    /** 두 국면 모두에 존재하는 마켓 — 마켓 효과를 통제한 paired 비교에 쓴다. */
    val PAIRED_MARKETS = listOf("KRW-XRP", "KRW-BTC", "KRW-ETH", "KRW-DOGE")

    private val MARKETS_BY_REGIME = mapOf(
        Regime.BEAR to listOf(
            "KRW-XRP", "KRW-BTC", "KRW-MMT", "KRW-ETH",
            "KRW-WLD", "KRW-RVN", "KRW-ONDO", "KRW-DOGE",
        ),
        // MMT·WLD·RVN·ONDO 는 이 시기 미상장이라 4마켓뿐이다. 현재 거래대금 상위의 절반이 2년 전엔
        // 없었다는 뜻이고, 이것 자체가 유니버스 선정에 생존편향이 있다는 증거다.
        Regime.BULL to PAIRED_MARKETS,
    )

    private val mapper = jacksonObjectMapper()

    fun markets(regime: Regime): List<String> = MARKETS_BY_REGIME.getValue(regime)

    /** 최신순 200봉. */
    fun load(regime: Regime, market: String): List<Candle> {
        val path = "/backtest/${regime.dir}/$market.json"
        val stream = requireNotNull(javaClass.getResourceAsStream(path)) { "fixture 없음: $path" }
        return stream.use { mapper.readValue(it) }
    }

    fun loadAll(regime: Regime): Map<String, List<Candle>> =
        markets(regime).associateWith { load(regime, it) }

    /** 두 국면에 공통으로 있는 마켓만 — paired 비교용. */
    fun loadPaired(regime: Regime): Map<String, List<Candle>> =
        PAIRED_MARKETS.associateWith { load(regime, it) }

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
