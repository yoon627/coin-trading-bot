package com.trading.bot.config

import org.springframework.boot.web.error.ErrorAttributeOptions
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.server.ResponseStatusException

/**
 * Selective error attribute exposure: only ResponseStatusException reasons —
 * which are explicitly authored by our controllers — surface to clients.
 * Anything else (DB failures, upstream errors, deserialization, framework
 * exceptions, stack traces, binding error details) is suppressed so that
 * production responses can't leak internal information regardless of how
 * server.error.include-* is configured upstream.
 */
@Component
class SafeErrorAttributes : DefaultErrorAttributes() {

    override fun getErrorAttributes(
        request: ServerRequest,
        options: ErrorAttributeOptions,
    ): MutableMap<String, Any> {
        val attrs = super.getErrorAttributes(request, options)
        attrs.remove("message")
        attrs.remove("errors")
        attrs.remove("trace")

        val error = getError(request)
        if (error is ResponseStatusException) {
            error.reason?.let { attrs["message"] = it }
        }
        return attrs
    }
}
