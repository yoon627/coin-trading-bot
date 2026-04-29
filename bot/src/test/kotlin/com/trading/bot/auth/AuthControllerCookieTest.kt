package com.trading.bot.auth

import com.trading.bot.api.RequestValidators
import com.trading.bot.persistence.UserRepository
import com.trading.bot.security.UserSecretsService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.core.env.Environment
import org.springframework.core.env.StandardEnvironment
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Verifies that prod profile always emits Secure cookies, independent of the
 * observed request scheme — guarding against the regression codex flagged
 * where a misconfigured reverse proxy (no X-Forwarded-Proto) would let the
 * JWT be issued over plain HTTP. Drives `shouldMarkSecure` through the real
 * /api/auth/logout endpoint so the cookie attributes are checked end-to-end.
 */
class AuthControllerCookieTest {

    private fun controllerWithProfile(profile: String): AuthController {
        val env: Environment = StandardEnvironment().apply { setActiveProfiles(profile) }
        return AuthController(
            userRepository = mockk<UserRepository>(),
            passwordEncoder = mockk<PasswordEncoder>(),
            jwtProvider = mockk<JwtProvider>(),
            requestValidators = mockk<RequestValidators>(),
            userSecretsService = mockk<UserSecretsService>(),
            environment = env,
        )
    }

    @Test
    fun `prod profile sets Secure on logout cookie even when request is http`() {
        val client = WebTestClient.bindToController(controllerWithProfile("prod")).build()

        val cookie = client.post().uri("/api/auth/logout")
            .exchange()
            .expectStatus().isOk
            .returnResult(Map::class.java)
            .responseCookies["token"]?.firstOrNull()

        assertNotNull(cookie) { "logout should emit a token cookie to clear it" }
        assertEquals(true, cookie!!.isSecure) {
            "prod profile must always emit Secure regardless of request scheme"
        }
    }

    @Test
    fun `dev profile over plain http omits Secure on logout cookie`() {
        val client = WebTestClient.bindToController(controllerWithProfile("dev")).build()

        val cookie = client.post().uri("/api/auth/logout")
            .exchange()
            .expectStatus().isOk
            .returnResult(Map::class.java)
            .responseCookies["token"]?.firstOrNull()

        assertNotNull(cookie)
        assertEquals(false, cookie!!.isSecure) {
            "dev profile over http must skip Secure so HTTP localhost works"
        }
    }

    @Test
    fun `dev profile honors X-Forwarded-Proto https when scheme is http`() {
        val client = WebTestClient.bindToController(controllerWithProfile("dev")).build()

        val cookie = client.post().uri("/api/auth/logout")
            .header("X-Forwarded-Proto", "https")
            .exchange()
            .expectStatus().isOk
            .returnResult(Map::class.java)
            .responseCookies["token"]?.firstOrNull()

        assertNotNull(cookie)
        assertEquals(true, cookie!!.isSecure) {
            "non-prod over forwarded https should still emit Secure"
        }
    }
}
