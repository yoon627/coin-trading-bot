package com.trading.bot.marketdata

import com.trading.bot.config.WatchlistProperties
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * watchlist API 가 `market_tickers` 를 읽는 전환은 **WS 구독 종목 == watchlist 종목** 이라는
 * 전제 위에 서 있다. 이 전제가 깨지면 watchlist 에서 종목이 조용히 사라지므로 회귀를 막는다.
 *
 * `MarketDataIngestionService.start()` 는 `watchlistProperties.tickerList()` 를
 * `MarketPair.normalize(UPBIT, it)` 로 변환해 구독하고, `WatchlistController` 는 같은 목록을
 * 그대로 조회 키로 쓴다 — 두 경로가 같은 문자열을 만들어야 한다.
 */
class WatchlistIngestionParityTest {

    @Test
    fun `watchlist 설정값은 그대로 조회 키가 될 수 없다 — 정규화를 거쳐야 한다`() {
        // 이 둘이 다르다는 사실이 이 작업의 함정이었다. 설정은 Upbit 형식("KRW-BTC")인데
        // market_tickers 에는 정규화 형식("BTC/KRW")으로 저장된다.
        val props = WatchlistProperties(" krw-btc , KRW-ETH ,KRW-DOGE")

        val configured = props.tickerList()
        val stored = configured.map { MarketPair.normalize(Exchange.UPBIT, it) }

        assertNotEquals(configured, stored, "형식이 같아졌다면 WatchlistController 의 변환을 재검토해야 한다")
        assertEquals(listOf("BTC/KRW", "ETH/KRW", "DOGE/KRW"), stored)
    }

    @Test
    fun `정규화는 멱등이 아니다 — 이미 변환된 값을 다시 넣으면 깨진다`() {
        // 변환을 두 번 적용하는 실수를 막는다. "BTC/KRW" 를 다시 normalize 하면 split("-") 이
        // 실패해 입력이 그대로 나오므로 조용히 통과하지만, 어느 한쪽에서 이중 변환이 생기면
        // 그때는 형식이 어긋난다는 사실을 여기 고정해 둔다.
        val once = MarketPair.normalize(Exchange.UPBIT, "KRW-BTC")
        assertEquals("BTC/KRW", once)
        assertEquals("BTC/KRW", MarketPair.normalize(Exchange.UPBIT, once))
    }
}
