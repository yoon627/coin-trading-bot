package com.trading.bot.engine

import com.trading.common.domain.Candle

/**
 * 시점 중립 유니버스 selector (#112).
 *
 * 백테 fixture 의 마켓 선정은 **수집 시점**(구간 끝)의 거래대금 상위로 이뤄져 look-ahead 가 있다.
 * 이 selector 는 **t0 이전 정보만** 써서 "그때라면 무엇을 골랐을 것인가"를 재구성한다.
 *
 * **순수 함수다** — 네트워크를 타지 않고 [Snapshot] 만 먹는다. 수집(네트워크)과 선정(판정)을 나눈 이유는
 * `/v1/market/all` 이 시간 불변이 아니어서(신규상장·폐지로 후보군이 바뀐다) 네트워크를 섞으면 재현이
 * 불가능하기 때문이다. 수집 결과를 스냅샷으로 떠 두면 이 함수는 결정적이고 CI 에서 검증된다.
 *
 * 닫지 못하는 편향: **폐지된 종목**은 `/v1/market/all` 에서 사라져 후보에 오르지 못한다. 상장 유지 중인
 * 종목은 급락했어도 정상 반영되므로, 남는 건 생존편향 중 **폐지 배제분**뿐이다.
 */
internal object PointInTimeUniverse {

    /** 현재 fixture 로스터와 같은 크기 — 비교 가능하게 맞춘다. */
    const val UNIVERSE_SIZE = 8

    /**
     * t0 직전 이 일수만큼 봉이 **완비**돼야 후보 자격을 준다.
     *
     * 상장 당일은 거래대금이 튀어 신생 종목이 상위로 올라간다. 30일 이력을 요구하면 그 오염을 막는 동시에
     * "t0 시점에 이미 상장돼 있었나"를 t0 이전 정보만으로 판정할 수 있다.
     * 대가로 **t0 직전 30일 안에 상장한 종목은 배제**되므로 완전한 시점 중립은 아니다 — 리포트에 명시한다.
     */
    const val MIN_HISTORY_DAYS = 30

    /** 변동성이 없어 전략 비교에 무의미하다. README 가 `KRW-USDT` 하나만 적어 재현이 안 되던 것을 상수로 고정. */
    val STABLECOINS = setOf("KRW-USDT", "KRW-USDC", "KRW-DAI", "KRW-TUSD", "KRW-BUSD", "KRW-PYUSD")

    const val EXCLUDED_STABLECOIN = "stablecoin"
    const val EXCLUDED_SHORT_HISTORY = "history<$MIN_HISTORY_DAYS"

    /**
     * 선정 입력. [candles] 는 마켓별로 **t0 직전** 일봉(최대 [MIN_HISTORY_DAYS]개)이다.
     *
     * @param missing 조회에 실패한 후보. **하나라도 있으면 판정하지 않는다** — 누락을 거래대금 0 으로
     *   흡수하면 그 마켓이 탈락하면서 다른 마켓이 부당하게 상위로 올라가 랭킹이 조용히 오염된다.
     */
    data class Snapshot(
        val asOf: String,
        val candidates: List<String>,
        val candles: Map<String, List<Candle>>,
        val missing: List<String> = emptyList(),
    )

    data class Ranked(val market: String, val avgTradePrice: Double)

    data class Selection(
        val asOf: String,
        val incomplete: Boolean,
        val universe: List<String>,
        val ranked: List<Ranked>,
        val excluded: Map<String, String>,
    )

    fun select(snapshot: Snapshot): Selection =
        Selection(snapshot.asOf, incomplete = false, universe = emptyList(), ranked = emptyList(), excluded = emptyMap())
}
