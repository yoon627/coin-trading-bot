package com.trading.bot.exchange

import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.Ticker
import com.trading.common.domain.Candle

/**
 * 엔진이 거래소에 요구하는 동작. 값은 Upbit 응답 어휘로 돌려준다.
 * - [getAccounts]: currency = "KRW" 인 현금 행이 있고, 보유 행의 currency 는 ticker 의 '-' 뒤 부분이다.
 * - [getDayCandles]: 최신 봉이 [0] 이고, candleDateTimeUtc 는 그 거래일의 00:00(UTC 표기)이다.
 * - [Order.state] 는 wait·done·cancel 중 하나다. done·cancel 이면 trades 로 체결 단가를 계산할 수 있다.
 */
interface ExchangeClient {
    suspend fun getAccounts(): List<Account>
    suspend fun getDayCandles(market: String, count: Int = 30): List<Candle>
    suspend fun getTicker(markets: String): List<Ticker>
    suspend fun placeOrder(request: OrderRequest): Order
    suspend fun getOrder(uuid: String): Order

    /**
     * null 은 거래소가 그 identifier 를 모른다는 답이고, 모르면 예외를 던진다. 둘을 섞으면 조회 실패를 미접수로 잘못 확정한다.
     * null 을 미접수 확정으로 쓸지는 [UnknownOrderPolicy.supportsClientOrderId] 가 정하고, false 면 엔진은 사람 확인으로 넘긴다.
     * 확정 근거를 스스로 영속하는 구현도 있다. 아직 보이지 않거나 사람이 봐야 하면 [OrderLookupException] 을 던진다.
     */
    suspend fun getOrderByIdentifier(identifier: String): Order?
}
