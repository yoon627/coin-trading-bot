package com.trading.bot.client

import com.trading.bot.domain.Order
import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.exchange.FillPollPolicy
import com.trading.common.domain.Candle
import java.util.UUID
import kotlinx.coroutines.delay

/** identifier 에 특수문자가 있으면 JWT 서명이 어긋난다. [getOrderByIdentifier] 는 404 order_not_found 일 때만 null 이다. */
interface UpbitClient : ExchangeClient {
    suspend fun getMinuteCandles(market: String, unit: Int = 1, count: Int = 200): List<Candle>
    suspend fun cancelOrder(uuid: String): Order
}

const val FILL_POLL_ATTEMPTS = 10
const val FILL_POLL_DELAY_MS = 300L

/** 체결이 끝나면 바로, 아니면 마지막 조회 결과를 돌려준다. 멤버로 두면 strict mock 이 깨져 확장 함수로 둔다. */
suspend fun ExchangeClient.awaitFill(uuid: String, policy: FillPollPolicy = FillPollPolicy.UPBIT): Order? {
    if (uuid.isBlank()) return null
    var last: Order? = null
    repeat(policy.attempts) { attempt ->
        last = getOrder(uuid)
        if (last?.isTerminal() == true) return last
        if (attempt < policy.attempts - 1) delay(policy.delayMs)
    }
    return last
}

class UpbitApiException(
    val statusCode: Int,
    val errorName: String?,
    val errorMessage: String?,
    val rawBody: String,
) : RuntimeException("Upbit API error: $statusCode - $rawBody")

// Upbit 문서의 주문 검증 거절 오류 이름. invaild_parameter 는 문서 표기 그대로다.
private val ORDER_REJECTION_ERRORS = setOf(
    "create_ask_error", "create_bid_error",
    "insufficient_funds_ask", "insufficient_funds_bid",
    "under_min_total_ask", "under_min_total_bid",
    "over_krw_funds_bid", "validation_error", "invalid_parameter", "invaild_parameter",
    "notfoundmarket", "market_offline",
)

/**
 * 실패가 주문이 만들어지지 않았음을 확정할 때만 true 다. 인증 실패·요청 수 제한·5xx·이름 없는 응답·타임아웃·
 * 연결 끊김·응답 해석 실패는 접수 여부를 알 수 없으므로 호출자가 identifier 로 확인해야 한다.
 * `duplicated_identifier` 는 그 주문이 이미 있다는 뜻이라 거절 목록에 넣으면 같은 주문을 다시 낸다.
 */
fun Throwable.provesOrderNotPlaced(): Boolean =
    this is UpbitApiException && statusCode in 400..499 && errorName in ORDER_REJECTION_ERRORS

/** 최소주문 금액(5,000원) 미만 거절. 매도라면 그 수량은 지금 팔 수 없는 dust 다. */
fun Throwable.isRejectedAsBelowMinimumOrder(): Boolean =
    this is UpbitApiException && (errorName == "under_min_total_ask" || errorName == "under_min_total_bid")

/** Upbit identifier 는 취소·실패 주문을 포함해 계정 전체에서 영구히 고유해야 하고 64자 이하다. */
fun newOrderIdentifier(): String = "ctb-${UUID.randomUUID()}"
