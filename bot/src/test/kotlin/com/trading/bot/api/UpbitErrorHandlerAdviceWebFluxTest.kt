package com.trading.bot.api

import com.trading.bot.client.UpbitApiException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * Verifies the advice actually fires inside a real WebFlux dispatcher
 * (not just a direct method call), since `@ExceptionHandler` methods that
 * throw — rather than return — depend on framework propagation. This test
 * exists specifically to refute or confirm a code-review concern about
 * that pattern in WebFlux.
 */
class UpbitErrorHandlerAdviceWebFluxTest {

    private val client: WebTestClient = WebTestClient
        .bindToController(StubController())
        .controllerAdvice(UpbitErrorHandlerAdvice())
        .build()

    @Test
    fun `401 no_authorization_ip surfaces as 400 in the WebFlux pipeline`() {
        client.get().uri("/_test/upbit/no-auth-ip")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `other 401 surfaces as 400 not 401 in the WebFlux pipeline`() {
        client.get().uri("/_test/upbit/other-401")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `429 surfaces as 429 in the WebFlux pipeline`() {
        client.get().uri("/_test/upbit/rate-limit")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `other 4xx surfaces as 400 in the WebFlux pipeline`() {
        client.get().uri("/_test/upbit/forbidden")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `5xx surfaces as 502 in the WebFlux pipeline`() {
        client.get().uri("/_test/upbit/server-error")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
    }
}

@RestController
private class StubController {
    @GetMapping("/_test/upbit/{kind}")
    fun throwIt(@PathVariable kind: String): String = when (kind) {
        "no-auth-ip" -> throw UpbitApiException(401, "no_authorization_ip", "This is not a verified IP.", "{}")
        "other-401" -> throw UpbitApiException(401, "invalid_access_key", "invalid access key", "{}")
        "rate-limit" -> throw UpbitApiException(429, null, "Too many requests", "")
        "forbidden" -> throw UpbitApiException(403, "forbidden", "Forbidden", "")
        "server-error" -> throw UpbitApiException(503, null, null, "")
        else -> error("unknown stub kind: $kind")
    }
}
