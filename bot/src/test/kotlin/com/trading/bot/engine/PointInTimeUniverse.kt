package com.trading.bot.engine

import com.trading.common.domain.Candle
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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

    /**
     * 30봉이 덮어도 되는 최대 달력일. 봉 **수**만 세면 거래 공백이 있는 종목이 통과해, 랭킹 기준이
     * "t0 직전 30일 평균"이 아니라 "관측된 30 거래일 평균"으로 종목마다 달라진다.
     * 업비트는 24/7 이라 정상 종목은 30봉 = 30일이고, 여유 2일은 결측 한두 개를 허용하는 폭이다.
     */
    const val MAX_WINDOW_SPAN_DAYS = MIN_HISTORY_DAYS + 2L

    const val EXCLUDED_STABLECOIN = "stablecoin"
    const val EXCLUDED_SHORT_HISTORY = "history<$MIN_HISTORY_DAYS"
    const val EXCLUDED_GAPPED_WINDOW = "window>${MAX_WINDOW_SPAN_DAYS}d"
    const val EXCLUDED_UNPARSEABLE_DATE = "unparseable-date"

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

    /**
     * 창의 가장 오래된 봉부터 t0 까지의 달력일. 날짜를 읽지 못하면 **null** — 호출부가 판정을 막는다.
     *
     * 0 을 돌려주면 창 검사가 조용히 통과하고, 그냥 제외하면 top-8 에 들어야 할 마켓이 빠져 랭킹이
     * 오염된다. 둘 다 "불완전을 흡수하지 않는다"는 이 selector 의 원칙에 어긋난다.
     */
    private fun windowSpanDays(asOf: String, window: List<Candle>): Long? {
        // 양끝만 보면 중간 봉의 날짜가 불량이어도 평균·선정에 그대로 섞인다 — 전부 검증한다.
        val dates = window.map { parseDate(it.candleDateTimeKst) ?: return null }
        val oldest = dates.minOrNull() ?: return null
        val t0 = parseDate(asOf) ?: return null
        return ChronoUnit.DAYS.between(oldest, t0)
    }

    private fun parseDate(raw: String): LocalDate? =
        runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()

    fun select(snapshot: Snapshot): Selection {
        // 완전성 먼저 — 누락이 있으면 랭킹 자체를 내지 않는다. 부분 결과를 내면 누락분이 거래대금 0 으로
        // 취급돼 탈락하고, 그 자리를 다른 마켓이 부당하게 채운 top-N 이 진단 결론으로 쓰인다.
        if (snapshot.missing.isNotEmpty()) {
            return Selection(snapshot.asOf, incomplete = true, emptyList(), emptyList(), emptyMap())
        }

        val excluded = mutableMapOf<String, String>()
        val eligible = mutableListOf<Ranked>()
        // 날짜를 읽지 못한 창 — 하나라도 있으면 판정을 막는다(누락과 같은 부류의 불완전).
        val malformed = mutableListOf<String>()

        for (market in snapshot.candidates.distinct().sorted()) {
            if (market in STABLECOINS) {
                excluded[market] = EXCLUDED_STABLECOIN
                continue
            }
            val window = snapshot.candles[market].orEmpty()
            if (window.size < MIN_HISTORY_DAYS) {
                excluded[market] = EXCLUDED_SHORT_HISTORY
                continue
            }
            // 봉 수가 찼어도 창이 늘어져 있으면 다른 종목과 같은 기준으로 비교할 수 없다.
            val span = windowSpanDays(snapshot.asOf, window)
            if (span == null) {
                malformed += market
                continue
            }
            if (span > MAX_WINDOW_SPAN_DAYS) {
                excluded[market] = EXCLUDED_GAPPED_WINDOW
                continue
            }
            // 합계가 아니라 평균 — 봉 수가 다르면 합계는 이력이 긴 쪽을 유리하게 만든다.
            eligible += Ranked(market, window.sumOf { it.candleAccTradePrice } / window.size)
        }

        if (malformed.isNotEmpty()) {
            return Selection(
                snapshot.asOf,
                incomplete = true,
                universe = emptyList(),
                ranked = emptyList(),
                excluded = malformed.associateWith { EXCLUDED_UNPARSEABLE_DATE },
            )
        }

        // 동점은 마켓 코드 오름차순으로 가른다(정렬은 안정적이고 입력은 이미 코드순이라 재현된다).
        val ranked = eligible.sortedWith(compareByDescending<Ranked> { it.avgTradePrice }.thenBy { it.market })
        return Selection(
            asOf = snapshot.asOf,
            incomplete = false,
            universe = ranked.take(UNIVERSE_SIZE).map { it.market },
            ranked = ranked,
            excluded = excluded,
        )
    }
}
