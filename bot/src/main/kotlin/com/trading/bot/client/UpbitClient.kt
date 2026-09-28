package com.trading.bot.client

import com.trading.bot.domain.Account
import com.trading.bot.domain.MarketInfo
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.Ticker
import com.trading.common.domain.Candle
import java.util.UUID
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
    /**
     * 주문 생성 때 보낸 identifier 로 조회한다. 거래소가 그 identifier 를 모르면(404 `order_not_found`) null 이고,
     * 그 밖의 오류는 예외다 — "모른다"와 "물어볼 수 없었다"를 호출자가 구분해야 미접수를 잘못 확정하지 않는다.
     * identifier 는 URL 안전 문자만 쓴다고 전제한다([newOrderIdentifier]) — JWT 해시에는 원문이, 요청 URI 에는 인코딩된
     * 값이 실려 특수문자가 있으면 서명이 어긋난다.
     */
    suspend fun getOrderByIdentifier(identifier: String): Order?
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

/**
 * 주문 요청 내용이 검증에서 거절된 오류 — 이 이름들만 "주문이 만들어지지 않았다"의 증거로 쓴다.
 * 출처: docs.upbit.com `rest-api-guide`·`new-order` (2026-09-28). `invaild_parameter` 는 문서 표기 그대로다.
 */
private val ORDER_REJECTION_ERRORS = setOf(
    "create_ask_error", "create_bid_error",
    "insufficient_funds_ask", "insufficient_funds_bid",
    "under_min_total_ask", "under_min_total_bid",
    "over_krw_funds_bid", "validation_error", "invalid_parameter", "invaild_parameter",
    "notfoundmarket", "market_offline",
)

/**
 * 주문 생성 실패가 **주문이 만들어지지 않았음**을 확정하는가. 인증·요청 수 제한·5xx·이름 없는 응답·`duplicated_identifier`·
 * 타임아웃·연결 끊김·응답 해석 실패는 접수 여부를 말해 주지 않으므로 false — 호출자는 identifier 로 확정해야 한다(#227).
 */
fun Throwable.provesOrderNotPlaced(): Boolean =
    this is UpbitApiException && statusCode in 400..499 && errorName in ORDER_REJECTION_ERRORS

/** 거래소가 주문 금액이 최소주문(원화 5,000원) 미만이라 거절했다 — 매도였다면 그 수량은 지금 팔 수 없는 dust 다(#234). */
fun Throwable.isRejectedAsBelowMinimumOrder(): Boolean =
    this is UpbitApiException && (errorName == "under_min_total_ask" || errorName == "under_min_total_bid")

/** 주문 identifier. Upbit 는 계정 전체에서 영구히 고유해야 하고(취소·실패 주문 포함) 64자 이하다 — `ctb-` + UUID v4 = 40자. */
fun newOrderIdentifier(): String = "ctb-${UUID.randomUUID()}"
