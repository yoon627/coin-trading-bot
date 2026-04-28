package com.trading.bot.api

import com.trading.bot.client.UpbitApiException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Translates UpbitApiException into an explicit ResponseEntity. Returning the
 * response (rather than throwing ResponseStatusException) is required under
 * WebFlux: a throw inside `@ExceptionHandler` does not re-enter the framework's
 * global error pipeline, so the original exception bubbles up as a generic 500.
 *
 * Critically, raw 401 from Upbit is remapped to 400 because the SPA's api.js
 * auto-redirects on 401 to /login.html (treating it as our own session expiry),
 * which would silently log the user out on any Upbit auth glitch.
 *
 * Body shape mirrors SafeErrorAttributes so the SPA can read `message` uniformly.
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
            )
        )
    }

    private fun mapException(ex: UpbitApiException): Pair<HttpStatus, String> = when {
        ex.statusCode == 401 && ex.errorName == "no_authorization_ip" ->
            HttpStatus.BAD_REQUEST to
                "Upbit API 키의 허용 IP에 서버 주소가 등록되어 있지 않습니다. " +
                "Upbit Open API 페이지에서 허용 IP 목록을 확인해주세요."
        ex.statusCode == 401 ->
            HttpStatus.BAD_REQUEST to "Upbit API 인증에 실패했습니다 (${ex.errorName ?: "unauthorized"})."
        ex.statusCode == 429 ->
            HttpStatus.TOO_MANY_REQUESTS to "Upbit API 호출 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
        ex.statusCode in 400..499 ->
            HttpStatus.BAD_REQUEST to "Upbit가 요청을 거부했습니다 (${ex.errorName ?: "error ${ex.statusCode}"})."
        else ->
            HttpStatus.BAD_GATEWAY to "Upbit API 호출에 일시적인 문제가 있습니다. 잠시 후 다시 시도해주세요."
    }
}
