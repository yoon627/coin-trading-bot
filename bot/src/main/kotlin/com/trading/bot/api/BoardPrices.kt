package com.trading.bot.api

import com.trading.bot.marketdata.MarketBoardStore
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import java.time.Duration
import org.springframework.stereotype.Component

/**
 * 화면용 현재가(업비트 마켓 코드 `KRW-BTC`). 모르면 null — 호출자가 업비트 REST 로 묻는다.
 * 포트폴리오는 이 인터페이스로만 시세판을 본다 — 시세판을 다른 서비스로 떼어 내면 구현만 바꾼다.
 */
fun interface BoardPrices {
    fun price(upbitMarket: String): Double?
}

/**
 * 시세판 저장소의 최신 값. 마켓별 시각으로는 거르지 않는다 — 거래가 뜸해 스냅샷만 온 마켓도 유효한 현재가다.
 * 대신 시세판 전체가 [STALE_AFTER] 넘게 아무것도 받지 못했으면(업비트 WS 장애) 모른다고 답해 REST 로 묻게 한다.
 */
@Component
class MarketBoardPrices(private val store: MarketBoardStore) : BoardPrices {
    override fun price(upbitMarket: String): Double? {
        if (!store.updatedWithin(STALE_AFTER)) return null
        return store.get(MarketPair.normalize(Exchange.UPBIT, upbitMarket))?.price
    }

    private companion object {
        // 시세판 연결의 무수신 재연결 기준과 같다.
        val STALE_AFTER: Duration = Duration.ofSeconds(120)
    }
}
