package com.trading.bot.client

import com.trading.bot.exchange.OrderErrorClassifier
import com.trading.bot.exchange.OrderFailure
import org.springframework.web.reactive.function.client.WebClientRequestException

object UpbitOrderErrorClassifier : OrderErrorClassifier {
    override fun classify(error: Throwable): OrderFailure = when {
        error.provesOrderNotSent() -> OrderFailure.NOT_SENT
        error is UpbitApiException && error.statusCode in 400..499 && error.errorName == "duplicated_identifier" -> OrderFailure.DUPLICATE_IDENTIFIER
        error.provesOrderNotPlaced() ->
            if (error.isRejectedAsBelowMinimumOrder()) OrderFailure.REJECTED_BELOW_MIN_ORDER else OrderFailure.REJECTED
        error.isTransient() -> OrderFailure.UNKNOWN_TRANSIENT
        else -> OrderFailure.UNKNOWN_OUTCOME
    }

    // 응답 헤더를 받기 전의 실패(응답 시한 초과·연결 끊김)는 WebClientRequestException 으로 온다.
    // 인증 실패·이름 없는 4xx·해석할 수 없는 응답은 다시 보내도 같은 결과라 넣지 않는다.
    private fun Throwable.isTransient(): Boolean =
        this is WebClientRequestException || (this is UpbitApiException && (statusCode == 429 || statusCode >= 500))
}
