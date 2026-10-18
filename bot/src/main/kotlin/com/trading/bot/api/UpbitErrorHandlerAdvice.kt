package com.trading.bot.api

import com.trading.bot.client.UpbitApiException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * WebFlux 에서는 `@ExceptionHandler` 안에서 예외를 던지면 전역 오류 처리로 돌아가지 않고 500 이 되므로 응답을 직접 만든다.
 * Upbit 의 401 은 400 으로 바꾼다. SPA 는 401 을 세션 만료로 보고 로그인 화면으로 보낸다.
 * 본문 형식은 SafeErrorAttributes 와 같다.
 */
@RestControllerAdvice
class UpbitErrorHandlerAdvice {

    @ExceptionHandler(UpbitApiException::class)
    fun handle(ex: UpbitApiException): ResponseEntity<Map<String, Any?>> {
        val (status, reason) = mapException(ex)
        return ResponseEntity.status(status).body(
            mapOf(
                "status" to status.value(),
                "error" to status.reasonPhrase,
                "message" to reason,
                // 상태 코드만으로는 권한·잔액·최소주문·IP 차단을 구분할 수 없다.
                "error_name" to ex.errorName,
            )
        )
    }

    private fun mapException(ex: UpbitApiException): Pair<HttpStatus, String> {
        val name = ex.errorName
        return when {
            ex.statusCode == 401 && name == "no_authorization_ip" ->
                HttpStatus.BAD_REQUEST to
                    "Upbit API 키의 허용 IP에 서버 주소가 등록되어 있지 않습니다. " +
                    "Upbit Open API 페이지에서 허용 IP 목록을 확인해주세요."
            ex.statusCode == 401 ->
                HttpStatus.BAD_REQUEST to "Upbit API 인증에 실패했습니다 (${name ?: "unauthorized"})."
            ex.statusCode == 429 ->
                HttpStatus.TOO_MANY_REQUESTS to "Upbit API 호출 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
            // 418 은 Upbit 의 IP 차단이다.
            ex.statusCode == 418 ->
                HttpStatus.FORBIDDEN to "Upbit가 요청 IP를 차단했습니다(418). 잠시 후 다시 시도하거나 허용 IP 설정을 확인해주세요."
            ex.statusCode in 400..499 ->
                HttpStatus.BAD_REQUEST to "Upbit가 요청을 거부했습니다 (${name ?: "error ${ex.statusCode}"})."
            else ->
                HttpStatus.BAD_GATEWAY to "Upbit API 호출에 일시적인 문제가 있습니다. 잠시 후 다시 시도해주세요."
        }
    }
}
