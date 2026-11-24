package com.trading.bot.auth

import com.trading.bot.api.RequestValidators
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
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
    private val refreshTokens: RefreshTokenService,
    /**
     * BCrypt(수십~100ms CPU)를 돌릴 곳. 요청 스레드(netty 이벤트 루프일 수 있다)도, 매매 엔진이 쓰는 Dispatchers.Default(이 서버는
     * 2개)도 피한다 — 로그인이 몰려도 줄 서는 것은 로그인이다.
     */
    private val hashDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
                    password = hashing { passwordEncoder.encode(req.password) },
                    upbitAccessKey = encryptedAccessKey,
                    upbitSecretKey = encryptedSecretKey,
                )
            ).awaitSingle()
        }
        return signIn(user, req.remember, request, response)
    }

    @PostMapping("/login")
    suspend fun login(@RequestBody req: AuthRequest, request: ServerHttpRequest, response: ServerHttpResponse): AuthResponse {
        val username = requestValidators.normalizeUsername(req.username)
        val user = userRepository.findByUsername(username).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        if (!hashing { passwordEncoder.matches(req.password, user.password) }) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        }
        return signIn(user, req.remember, request, response)
    }

    /**
     * 접근 JWT 가 만료되면 화면이 부른다. refresh 쿠키를 새 토큰으로 바꾸고 새 접근 JWT 를 쿠키로 내린다(본문에 토큰을 넣지 않는다).
     * 다른 탭이 같은 토큰으로 방금 바꿨으면 401 만 — 쿠키를 건드리면 그 탭이 받은 새 쿠키를 지운다.
     */
    @PostMapping("/refresh")
    suspend fun refresh(request: ServerHttpRequest): ResponseEntity<Map<String, String>> {
        val presented = request.cookies.getFirst(REFRESH_COOKIE)?.value?.takeIf { it.isNotBlank() }
            ?: return rejected(request)
        // 행만 믿지 않는다 — 사용자를 다시 읽고, 못 읽으면 바꾸기가 되돌려진다(RefreshTokenService).
        return when (val result = refreshTokens.refresh(presented) { userRepository.findById(it).awaitSingleOrNull() }) {
            is RefreshTokenService.Refresh.Rotated -> {
                val user = result.user
                ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, authCookie(request, jwtProvider.generateToken(user.id!!, user.username)).toString())
                    .header(HttpHeaders.SET_COOKIE, refreshCookie(request, result.token, result.lifetime).toString())
                    .body(mapOf("status" to "refreshed"))
            }
            RefreshTokenService.Refresh.Concurrent -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            RefreshTokenService.Refresh.Rejected -> rejected(request)
        }
    }

    // 접근 JWT 가 이미 만료돼도 refresh 쿠키로 그 로그인(계열)을 끊는다. 쿠키는 내릴 때와 같은 Path 로 지워야 지워진다.
    @PostMapping("/logout")
    suspend fun logout(request: ServerHttpRequest, response: ServerHttpResponse): Map<String, String> {
        // 서버에서 끊지 못해도 이 브라우저의 쿠키는 지운다. 끊지 못한 로그인은 ERROR(Discord)로 알린다.
        revokePresented(request, "logout")
        response.addCookie(cleared(request, TOKEN_COOKIE, "/", "Lax"))
        response.addCookie(cleared(request, REFRESH_COOKIE, REFRESH_PATH, "Strict"))
        return mapOf("status" to "logged_out")
    }

    private suspend fun signIn(user: UserEntity, remember: Boolean, request: ServerHttpRequest, response: ServerHttpResponse): AuthResponse {
        // 이 브라우저에 남은 옛 로그인을 끊는다 — 그 토큰이 새어 나갔어도 다시 로그인하면 더는 쓰이지 않는다.
        revokePresented(request, "login")
        val token = jwtProvider.generateToken(user.id!!, user.username)
        val issued = refreshTokens.issue(user.id, remember)
        response.addCookie(authCookie(request, token))
        response.addCookie(refreshCookie(request, issued.token, issued.lifetime))
        return AuthResponse(token = token, username = user.username)
    }

    private suspend fun revokePresented(request: ServerHttpRequest, during: String) {
        val presented = request.cookies.getFirst(REFRESH_COOKIE)?.value?.takeIf { it.isNotBlank() } ?: return
        try {
            refreshTokens.revoke(presented)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("[AUTH] {} 중 이전 로그인 유지 토큰을 서버에서 끊지 못했다 — 그 토큰은 만료까지 쓰일 수 있다: {}", during, e.message)
        }
    }

    private fun rejected(request: ServerHttpRequest): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.SET_COOKIE, cleared(request, REFRESH_COOKIE, REFRESH_PATH, "Strict").toString())
            .build()

    private suspend fun <T> hashing(block: () -> T): T = withContext(hashDispatcher) { block() }

    private fun authCookie(request: ServerHttpRequest, token: String): ResponseCookie =
        ResponseCookie.from(TOKEN_COOKIE, token)
            .httpOnly(true)
            .secure(shouldMarkSecure(request))
            .path("/")
            .sameSite("Lax")
            .maxAge(Duration.ofDays(1))
            .build()

    // 다른 API 요청에 실리지 않게 /api/auth 로만, 다른 사이트에서 보내지지 않게 Strict 로 둔다.
    private fun refreshCookie(request: ServerHttpRequest, token: String, lifetime: Duration): ResponseCookie =
        ResponseCookie.from(REFRESH_COOKIE, token)
            .httpOnly(true)
            .secure(shouldMarkSecure(request))
            .path(REFRESH_PATH)
            .sameSite("Strict")
            .maxAge(lifetime)
            .build()

    private fun cleared(request: ServerHttpRequest, name: String, path: String, sameSite: String): ResponseCookie =
        ResponseCookie.from(name, "")
            .httpOnly(true)
            .secure(shouldMarkSecure(request))
            .path(path)
            .sameSite(sameSite)
            .maxAge(Duration.ZERO)
            .build()

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
    // 켜면 마지막 사용부터 30일, 끄면 로그인부터 24시간 다시 로그인하지 않는다(RefreshTokenService).
    val remember: Boolean = false,
)

data class AuthResponse(val token: String, val username: String)

private const val TOKEN_COOKIE = "token"
private const val REFRESH_COOKIE = "refresh"
private const val REFRESH_PATH = "/api/auth"
