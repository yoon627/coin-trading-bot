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
 * exceptions, stack traces, binding error details, fully-qualified exception
 * class names) is suppressed so that production responses can't leak internal
 * information regardless of how server.error.include-* is configured upstream.
 *
 * Uses an allowlist rather than a denylist so any future field DefaultError-
 * Attributes (or its subclasses) might add — e.g. another diagnostic key on
 * a Spring upgrade — is excluded by default until explicitly vetted.
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
        // Vetted as safe to expose to clients: timestamp + path are observable
        // anyway, status + error are the HTTP semantics, requestId is a
        // correlation ID with no internal content.
        val SAFE_KEYS = setOf("timestamp", "path", "status", "error", "requestId")
    }
}
