package com.trading.bot.client

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.util.retry.Retry

/**
 * Specifies the retry policy used by UpbitClient.retryOnRateLimit so that
 * the original UpbitApiException survives retry exhaustion and the advice
 * can map it to a 429. Without `onRetryExhaustedThrow { _, s -> s.failure() }`
 * Reactor wraps the last failure, the advice no longer recognizes it, and
 * the response degrades to a generic 500.
 */
class UpbitClientRetryPolicyTest {

    @Test
    fun `retry exhaustion on 429 rethrows original UpbitApiException`() {
        val source = Mono.error<String>(UpbitApiException(429, null, "Too many requests", ""))
            .retryWhen(
                Retry.backoff(2, Duration.ofMillis(10))
                    .filter { it is UpbitApiException && it.statusCode == 429 }
                    .onRetryExhaustedThrow { _, signal -> signal.failure() }
            )

        val ex = assertThrows<UpbitApiException> { source.block() }
        assertEquals(429, ex.statusCode)
    }

    @Test
    fun `default Reactor backoff without onRetryExhaustedThrow wraps the original — counter-example`() {
        // Documents the framework default that the production fix overrides.
        // If Reactor ever changes this behavior, this test starts failing and
        // forces a deliberate review of the retry policy.
        val source = Mono.error<String>(UpbitApiException(429, null, "Too many requests", ""))
            .retryWhen(
                Retry.backoff(2, Duration.ofMillis(10))
                    .filter { it is UpbitApiException && it.statusCode == 429 }
            )

        val ex = assertThrows<Exception> { source.block() }
        // Wrapped: the unwrapped UpbitApiException is on `cause`, not the top.
        assertEquals(true, ex !is UpbitApiException) {
            "Reactor default should wrap, but got UpbitApiException directly — framework behavior may have changed"
        }
        assertEquals(429, (ex.cause as? UpbitApiException)?.statusCode)
    }
}
