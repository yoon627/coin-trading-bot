package com.trading.bot.kis.client

/**
 * KIS 호출 실패. 메시지에는 엔드포인트 이름·HTTP 상태·msg_cd·msg1 만 넣는다.
 * 응답 원문·헤더·키·계좌·토큰은 넣지 않는다. 전송 예외는 요청 URI(계좌번호 쿼리)와 인증 헤더 사본을 품고 있어
 * cause 로 잇지 않고 클래스 이름만 남긴다 — 이으면 stack 을 찍는 곳(Discord ERROR 알림·컨테이너 로그)으로 샌다.
 */
sealed class KisException(
    val endpoint: String,
    val httpStatus: Int?,
    val msgCd: String?,
    val msg1: String?,
    val reason: String,
) : RuntimeException(describe(endpoint, reason, httpStatus, msgCd, msg1)) {

    private companion object {
        fun describe(endpoint: String, reason: String, httpStatus: Int?, msgCd: String?, msg1: String?): String =
            buildString {
                append("KIS ").append(endpoint).append(": ").append(reason)
                val details = listOfNotNull(
                    httpStatus?.let { "http=$it" },
                    msgCd?.let { "msg_cd=$it" },
                    msg1?.let { "msg1=$it" },
                )
                if (details.isNotEmpty()) append(" (").append(details.joinToString(", ")).append(")")
            }
    }
}

/**
 * 응답이 왔고 rt_cd≠0 이거나, 조회·토큰 발급이 HTTP 4xx 를 받았다(이때 [rtCd] 는 null 일 수 있다). 유량 초과는 들지 않고,
 * 조회의 토큰 오류는 새 토큰으로 한 번 더 보낸 뒤에도 같을 때만 든다. 주문이면 2xx 에 rt_cd≠0 인 경우만이고 미접수가 확정된 것이다.
 */
class KisRejectedException(
    endpoint: String,
    httpStatus: Int?,
    val rtCd: String?,
    msgCd: String?,
    msg1: String?,
    reason: String = "rejected",
) : KisException(endpoint, httpStatus, msgCd, msg1, reason)

/**
 * 일시 지연 — 조회 재시도 소진, 리미터 대기 상한 초과·리미터 닫힘, 토큰 재발급 최소 간격 등.
 * 주문에서 이 예외가 나면 요청을 보내기 전에 멈춘 것이다. [throttled] 는 유량 제한 때문인지를 알린다.
 */
class KisTransientException(
    endpoint: String,
    val throttled: Boolean,
    reason: String,
    httpStatus: Int? = null,
    msgCd: String? = null,
    msg1: String? = null,
) : KisException(endpoint, httpStatus, msgCd, msg1, reason)

/** 주문 POST 를 보냈지만 접수 여부를 알 수 없다. 당일 주문 조회로 확인해야 한다. */
class KisOutcomeUnknownException(
    endpoint: String,
    reason: String,
    httpStatus: Int? = null,
    msgCd: String? = null,
    msg1: String? = null,
) : KisException(endpoint, httpStatus, msgCd, msg1, reason)

/** 연속조회를 끝까지 읽지 못했다. 부분 결과는 돌려주지 않는다. */
class KisIncompleteResponseException(
    endpoint: String,
    reason: String,
) : KisException(endpoint, null, null, null, reason)

/** 필요한 필드가 비었거나 숫자로 읽히지 않는다. 값은 메시지에 넣지 않고 필드 이름만 넣는다. */
class KisMalformedResponseException(
    endpoint: String,
    reason: String,
    httpStatus: Int? = null,
) : KisException(endpoint, httpStatus, null, null, reason)
