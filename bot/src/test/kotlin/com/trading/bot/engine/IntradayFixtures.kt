package com.trading.bot.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.trading.common.domain.Candle

/**
 * 240분봉 fixture — `scripts/collect_intraday_fixtures.py` 가 만든다. 일봉 fixture 와 **같은 구간·같은 로스터**다.
 *
 * 일봉만으로는 "왜 하필 09:00 인가" 를 잴 수 없다 — Upbit 일봉 경계가 곧 09:00 이라 일봉 종가 ≡ 익일 일봉 시가이고,
 * 따라서 일봉 데이터에는 09:00 이외 시각에 대한 정보가 0비트다.
 *
 * 240분 격자는 KST 01/05/09/13/17/21 이며 09:00 이 격자 위에 있다. 1분봉은 청산 시각의 체결가를 바꾸지 않으면서
 * 데이터를 240배로 늘리므로 이 질문에 쓰지 않는다.
 */
internal object IntradayFixtures {

    const val UNIT_MINUTES = 240
    val OFFSET_HOURS = listOf(0, 4, 8, 12, 16, 20)

    private val mapper = jacksonObjectMapper()

    /** 청산 경계 시각(KST) 라벨 — `09:00 + offset`. */
    fun label(offsetHours: Int): String = "%02d:00".format((9 + offsetHours) % 24)

    /** 최신순. [BacktestFixtures] 와 같은 규약. */
    fun load(dir: String, market: String): List<Candle> {
        val path = "/backtest/intraday240/$dir/$market.json"
        val stream = requireNotNull(javaClass.getResourceAsStream(path)) {
            "fixture 없음: $path — python3 scripts/collect_intraday_fixtures.py --write"
        }
        return stream.use { mapper.readValue(it) }
    }

    fun loadAll(dir: String, markets: Collection<String>): Map<String, List<Candle>> =
        markets.associateWith { load(dir, it) }
}
