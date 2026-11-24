package com.trading.bot.marketdata

import com.trading.common.domain.NormalizedTicker
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

/** 시세판 마켓별 최신 ticker. 봇의 [MarketDataStore] 와 나눠, 시세판 목록이 바뀌어도 봇 시세를 지우지 않는다. */
@Component
class MarketBoardStore {
    private val tickers = ConcurrentHashMap<String, NormalizedTicker>()

    // netty 스레드에서 불린다 — 넣기만 한다.
    fun update(ticker: NormalizedTicker) {
        tickers[ticker.market] = ticker
    }

    fun retain(markets: Collection<String>) {
        tickers.keys.retainAll(markets.toSet())
    }

    fun get(market: String): NormalizedTicker? = tickers[market]

    fun snapshot(): Map<String, NormalizedTicker> = HashMap(tickers)
}
