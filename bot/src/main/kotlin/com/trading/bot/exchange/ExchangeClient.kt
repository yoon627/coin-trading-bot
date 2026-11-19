package com.trading.bot.exchange

import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.Ticker
import com.trading.common.domain.Candle
import java.time.Instant

/**
 * 엔진이 거래소에 요구하는 동작. 값은 Upbit 응답 어휘로 돌려준다.
 * - [getAccounts]: currency = "KRW" 인 현금 행이 있고, 보유 행의 currency 는 ticker 의 '-' 뒤 부분이다.
 * - [getDayCandles]: 최신 봉이 [0] 이고, candleDateTimeUtc 는 그 거래일의 00:00(UTC 표기)이다.
 * - [getTicker]: markets 는 쉼표로 이은 여러 마켓이고 응답마다 market 을 채운다. 엔진은 낡은 시세를 묶어 한 번에 묻는다.
 * - [Order.state] 는 wait·done·cancel 중 하나다. done·cancel 이면 trades 로 체결 단가를 계산할 수 있다.
 */
interface ExchangeClient {
    suspend fun getAccounts(): List<Account>
    suspend fun getDayCandles(market: String, count: Int = 30): List<Candle>
    suspend fun getTicker(markets: String): List<Ticker>
    /** 돌려주는 주문의 uuid 는 비어 있지 않다. 응답에 uuid 가 없으면 접수 여부를 모르는 실패로 예외를 던진다. */
    suspend fun placeOrder(request: OrderRequest): Order
    suspend fun getOrder(uuid: String): Order

    /**
     * null 은 거래소가 그 identifier 를 모른다는 답이고, 모르면 예외를 던진다. 둘을 섞으면 조회 실패를 미접수로 잘못 확정한다.
     * [UnknownOrderPolicy.supportsClientOrderId] 가 false 인 거래소는 엔진을 만들지 않는다.
     * 아직 보이지 않으면 [OrderLookupException.NotYetVisible] 을 던진다.
     * 돌려주는 주문의 uuid 는 비어 있지 않다 — 응답에 없으면 예외를 던진다.
     */
    suspend fun getOrderByIdentifier(identifier: String): Order?

    /**
     * [state](done·cancel) 인 종료 주문 중 [start]..[end] 에 만든 것. 창은 7일 이하다. identifier 로 거르지 않으므로
     * 호출부가 맞춰 본다. 목록에는 trades 가 없을 수 있어 체결 내용은 [getOrder] 로 다시 읽는다.
     * [requireComplete] 면 창을 끝까지 읽지 못했을 때(쪽 수 상한·진행 없는 쪽) 일부 목록 대신 예외를 던진다 —
     * "목록에 없다"로 판정하는 호출부가 잘린 목록을 없음으로 읽지 않게 한다. [market] 을 주면 그 마켓의 주문만 받는다.
     */
    suspend fun getClosedOrders(
        state: String,
        start: Instant,
        end: Instant,
        requireComplete: Boolean = false,
        market: String? = null,
    ): List<Order>
}
