package com.trading.bot.config

import org.springframework.boot.web.error.ErrorAttributeOptions
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.server.ResponseStatusException

/**
 * 컨트롤러가 직접 쓴 ResponseStatusException 사유만 클라이언트에 노출하고 나머지 내부 정보는 숨긴다.
 * Spring 업그레이드로 새 진단 키가 생겨도 새지 않도록 허용 목록으로 거른다.
 */
@Component
class SafeErrorAttributes : DefaultErrorAttributes() {

    override fun getErrorAttributes(
        request: ServerRequest,
        options: ErrorAttributeOptions,
    ): MutableMap<String, Any> {
        val raw = super.getErrorAttributes(request, options)
        val safe = mutableMapOf<String, Any>()
        for (key in SAFE_KEYS) {
            raw[key]?.let { safe[key] = it }
        }

        val error = getError(request)
        if (error is ResponseStatusException) {
            error.reason?.let { safe["message"] = it }
        }
        return safe
    }

    private companion object {
        val SAFE_KEYS = setOf("timestamp", "path", "status", "error", "requestId")
    }
}
