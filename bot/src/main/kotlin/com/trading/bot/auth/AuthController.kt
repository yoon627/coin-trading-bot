package com.trading.bot.auth

import com.trading.bot.api.RequestValidators
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtProvider: JwtProvider,
    private val requestValidators: RequestValidators,
    private val userSecretsService: UserSecretsService,
    private val environment: Environment,
) {
    @PostMapping("/register")
    suspend fun register(@RequestBody req: AuthRequest, request: ServerHttpRequest, response: ServerHttpResponse): AuthResponse {
        val username = requestValidators.normalizeUsername(req.username)
        requestValidators.validatePassword(req.password)
        val existing = userRepository.findByUsername(username).awaitSingleOrNull()
        if (existing != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Username already exists")
        }
        val accessKey = req.upbitAccessKey?.takeIf { it.isNotBlank() }?.let {
            requestValidators.normalizeApiKey(it, "accessKey")
        }
        val secretKey = req.upbitSecretKey?.takeIf { it.isNotBlank() }?.let {
            requestValidators.normalizeApiKey(it, "secretKey")
        }
        if ((accessKey == null) != (secretKey == null)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Both accessKey and secretKey must be provided together")
        }
        val (encryptedAccessKey, encryptedSecretKey) = userSecretsService.encryptUpbitKeys(accessKey, secretKey)
        val user = userRepository.save(
            UserEntity(
                username = username,
                password = passwordEncoder.encode(req.password),
                upbitAccessKey = encryptedAccessKey,
                upbitSecretKey = encryptedSecretKey,
            )
        ).awaitSingle()
        val token = jwtProvider.generateToken(user.id!!, user.username)
        writeAuthCookie(request, response, token)
        return AuthResponse(token = token, username = user.username)
    }

    @PostMapping("/login")
    suspend fun login(@RequestBody req: AuthRequest, request: ServerHttpRequest, response: ServerHttpResponse): AuthResponse {
        val username = requestValidators.normalizeUsername(req.username)
        val user = userRepository.findByUsername(username).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        if (!passwordEncoder.matches(req.password, user.password)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        }
        val token = jwtProvider.generateToken(user.id!!, user.username)
        writeAuthCookie(request, response, token)
        return AuthResponse(token = token, username = user.username)
    }

    @PostMapping("/logout")
    fun logout(request: ServerHttpRequest, response: ServerHttpResponse): Map<String, String> {
        response.addCookie(
            ResponseCookie.from("token", "")
                .httpOnly(true)
                .secure(shouldMarkSecure(request))
                .path("/")
                .sameSite("Lax")
                .maxAge(Duration.ZERO)
                .build()
        )
        return mapOf("status" to "logged_out")
    }

    private fun writeAuthCookie(request: ServerHttpRequest, response: ServerHttpResponse, token: String) {
        response.addCookie(
            ResponseCookie.from("token", token)
                .httpOnly(true)
                .secure(shouldMarkSecure(request))
                .path("/")
                .sameSite("Lax")
                .maxAge(Duration.ofDays(1))
                .build()
        )
    }

    // In production we always emit Secure cookies so the JWT is never sent over
    // plain HTTP, even when the request is observed as scheme=http (e.g. behind
    // a reverse proxy that drops X-Forwarded-Proto, or a misrouted direct
    // connection). Local prod-mode HTTP testing is unsupported as a result —
    // use the dev profile for that. In non-prod profiles we still derive Secure
    // from the request scheme so HTTP localhost works for development.
    //
    // Escape hatch: APP_AUTH_COOKIE_FORCE_INSECURE=true forces non-Secure cookies
    // even under prod. Use ONLY for short-lived HTTP-only test boxes (e.g. EC2 8080
    // before TLS is set up) — JWT travels in plaintext while this is on.
    private fun shouldMarkSecure(request: ServerHttpRequest): Boolean {
        if (environment.getProperty("app.auth.cookie-force-insecure", "false").toBoolean()) return false
        if (environment.acceptsProfiles(Profiles.of("prod"))) return true
        if (request.uri.scheme.equals("https", ignoreCase = true)) return true
        return request.headers.getFirst("X-Forwarded-Proto")
            ?.split(",")?.firstOrNull()?.trim()
            ?.equals("https", ignoreCase = true) == true
    }
}

data class AuthRequest(
    val username: String,
    val password: String,
    val upbitAccessKey: String? = null,
    val upbitSecretKey: String? = null,
)
data class AuthResponse(val token: String, val username: String)
