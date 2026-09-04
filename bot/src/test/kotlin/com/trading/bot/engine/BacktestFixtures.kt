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
 *
 * 마켓 선정은 **구간 시작 시점 기준**이다(#112). 예전처럼 수집 시점 상위를 과거에 소급하면
 * 그 구간을 살아남아 커진 종목만 뽑혀 성과가 부풀려진다. 남은 한계는 **생존편향** —
 * 그 사이 상장폐지된 종목은 Upbit API 가 404 라 표본에 넣을 수도, 크기를 잴 수도 없다.
 */
internal object BacktestFixtures {

    enum class Regime(val dir: String, val label: String) {
        /** 2026-01-31 ~ 2026-08-18. 8마켓 전부 마이너스(-27% ~ -91%). */
        BEAR("bear", "하락장 2026-01~08"),

        /**
         * 2023-11-23 ~ 2024-06-09. SOL +196%, POLYX +153%, BTC +96%, MINA +27% /
         * XRP -14%, BLUR -23%, GAS -40%, ARK -43% — **상승장에서도 절반이 손실**이다.
         *
         * 옛 유니버스(오늘의 거래대금 상위를 2023년에 소급)에서는 이 국면이 BTC +96%·ETH +89%·
         * DOGE +102%·XRP -16% 로 보였다. 균일하게 오르는 장처럼 보였던 것은 **오늘의 승자들로만
         * 채워져 있었기 때문**이다(#112).
         */
        BULL("bull", "상승장 2023-11~2024-06"),

        /**
         * 2024-06-10 ~ 2024-12-26. XRP +366%, DOGE +132%, BTC +48% / ETH −2%, PYTH −4% — **강한 상승 국면**.
         * 이름을 기간으로 붙인 이유: 성격은 수집 뒤에야 알 수 있고, 이름이 곧 결론이 되면 사후 서사가 데이터보다 먼저 자리잡는다.
         */
        P2024H2("p2024h2", "2024-06~12"),

        /** 2025-01-01 ~ 2025-07-19. XRP +35%, BTC +14% / 나머지 6종 −2 ~ −71% — **혼조·약세 국면**. */
        P2025H1("p2025h1", "2025-01~07"),
    }

    /**
     * yearly fixture(2025-09-03~2026-09-02) 구간과 **겹치지 않는** 국면 — 시간 독립 holdout.
     * `BEAR`(2026-01~08)는 yearly 안에 통째로 들어가므로 여기 없다(robustness 표기 전용).
     */
    val TIME_INDEPENDENT = listOf(Regime.BULL, Regime.P2024H2, Regime.P2025H1)

    /**
     * 국면 fixture 가 둘뿐이던 시절의 모집단.
     *
     * 골든·paired 비교·적립 스윕처럼 **이미 결과가 인용된 측정**은 이 목록을 순회해야 한다 — `Regime.entries` 를 쓰면
     * 국면을 추가할 때마다 그 측정의 모집단이 조용히 달라져 과거 수치와 비교가 끊긴다(#112 이후 같은 함정).
     * 새 국면을 함께 보고 싶으면 그 측정의 사전고정을 다시 쓴 뒤 옮긴다.
     */
    val ORIGINAL_REGIMES = listOf(Regime.BEAR, Regime.BULL)

    /**
     * 두 국면 모두의 상위 8에 든 마켓 — 마켓 효과를 통제한 paired 비교에 쓴다.
     *
     * 시점 중립 선정으로 바꾸면서 4개(XRP·BTC·ETH·DOGE)에서 3개로 줄었다. 유동 유니버스가 실제로
     * 회전하기 때문이며, **겹침을 늘리려 선정 규칙을 손대면 그게 다시 선택 편향**이라 그대로 둔다.
     * 대신 BULL 국면 내 표본이 4 → 8 로 늘었다.
     */
    val PAIRED_MARKETS = listOf("KRW-XRP", "KRW-BTC", "KRW-SOL")

    /**
     * 국면이 넷으로 늘면서 **네 구간 공통 마켓은 XRP·BTC 둘뿐**이다(SOL 은 `p2025h1` 상위 8에 없다).
     * paired 비교는 3개 미만이면 하지 않는다 — 교집합을 늘리려 선정 규칙을 손대면 그게 다시 선택 편향이다.
     */
    // by lazy — 이 object 안에서 로스터 맵보다 먼저 선언돼 있어 즉시 계산하면 초기화 순서에 걸린다.
    val PAIRED_MARKETS_ALL_REGIMES: List<String> by lazy {
        Regime.entries.map { markets(it).toSet() }.reduce { acc, set -> acc intersect set }.toList()
    }

    // 각 구간 **시작 시점 이전** 30일 평균 거래대금 상위 8 (스테이블 제외).
    // 선정 규칙과 재수집은 `scripts/collect_backtest_fixtures.py` 가 소유한다 — 여기 목록은 그 산출물이다.
    private val MARKETS_BY_REGIME = mapOf(
        Regime.BEAR to listOf(
            "KRW-XRP", "KRW-BTC", "KRW-ETH", "KRW-AXS",
            "KRW-DATA", "KRW-ENSO", "KRW-SOL", "KRW-BERA",
        ),
        Regime.BULL to listOf(
            "KRW-GAS", "KRW-XRP", "KRW-BTC", "KRW-SOL",
            "KRW-ARK", "KRW-MINA", "KRW-BLUR", "KRW-POLYX",
        ),
        Regime.P2024H2 to listOf(
            "KRW-BTC", "KRW-ETH", "KRW-SHIB", "KRW-SOL",
            "KRW-DOGE", "KRW-ETC", "KRW-XRP", "KRW-PYTH",
        ),
        Regime.P2025H1 to listOf(
            "KRW-XRP", "KRW-BTC", "KRW-DOGE", "KRW-ETH",
            "KRW-HBAR", "KRW-ENS", "KRW-AGLD", "KRW-CTC",
        ),
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
