package com.trading.bot.auth

import com.trading.bot.api.RequestValidators
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // 동시 요청 둘이 모두 계정 0 개를 보고 가입하지 않도록 확인부터 저장까지 묶는다. 단일 인스턴스 기준이다.
    private val registrationLock = Mutex()

    /** 한 명만 쓰는 봇이 인터넷에 열려 있으므로 계정이 없을 때 첫 계정만 받는다. */
    @PostMapping("/register")
    suspend fun register(@RequestBody req: AuthRequest, request: ServerHttpRequest, response: ServerHttpResponse): AuthResponse {
        val user = registrationLock.withLock {
            // 닫힌 서버가 입력 규칙을 알려 주거나 해시 비용을 쓰지 않게 검증보다 먼저 막는다.
            if (userRepository.count().awaitSingle() > 0) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "Registration is closed")
            }
            val username = requestValidators.normalizeUsername(req.username)
            requestValidators.validatePassword(req.password)
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
            userRepository.save(
                UserEntity(
                    username = username,
                    password = passwordEncoder.encode(req.password),
                    upbitAccessKey = encryptedAccessKey,
                    upbitSecretKey = encryptedSecretKey,
                )
            ).awaitSingle()
        }
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

    // prod 에서는 프록시가 X-Forwarded-Proto 를 빠뜨려도 JWT 가 평문으로 나가지 않도록 항상 Secure 로 둔다.
    // cookie-force-insecure 는 TLS 없는 임시 테스트 서버용이며 켜 두는 동안 JWT 가 평문으로 오간다.
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
