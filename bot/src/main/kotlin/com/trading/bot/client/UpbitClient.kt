package com.trading.bot.client

import com.trading.bot.domain.Account
import com.trading.bot.domain.MarketInfo
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.Ticker
import com.trading.common.domain.Candle
import kotlinx.coroutines.delay

interface UpbitClient {
    suspend fun getAccounts(): List<Account>
    suspend fun getDayCandles(market: String, count: Int = 30): List<Candle>
    suspend fun getMinuteCandles(market: String, unit: Int = 1, count: Int = 200): List<Candle>
    suspend fun getTicker(markets: String): List<Ticker>
    /** 전체 마켓 목록(`is_details=true` — 투자유의 플래그 포함). 인증 불필요. */
    suspend fun getMarkets(): List<MarketInfo>
    suspend fun placeOrder(request: OrderRequest): Order
    suspend fun getOrder(uuid: String): Order
    suspend fun cancelOrder(uuid: String): Order
}

const val FILL_POLL_ATTEMPTS = 10
const val FILL_POLL_DELAY_MS = 300L

/**
 * 주문 체결 폴링. terminal(done/cancel)이면 즉시 반환, 아니면 최대 [FILL_POLL_ATTEMPTS] 회 조회한 마지막 응답을 돌려준다.
 * 엔진·수동 주문이 같은 조회 동작을 쓰기 위한 확장이다 — 인터페이스 멤버로 두면 strict mock 이 깨진다.
 */
suspend fun UpbitClient.awaitFill(uuid: String): Order? {
    if (uuid.isBlank()) return null
    var last: Order? = null
    repeat(FILL_POLL_ATTEMPTS) { attempt ->
        last = getOrder(uuid)
        if (last?.isTerminal() == true) return last
        if (attempt < FILL_POLL_ATTEMPTS - 1) delay(FILL_POLL_DELAY_MS)
    }
    return last
}

class UpbitApiException(
    val statusCode: Int,
    val errorName: String?,
    val errorMessage: String?,
    val rawBody: String,
) : RuntimeException("Upbit API error: $statusCode - $rawBody")
