package com.trading.bot.auth

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.api.RequestValidators
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.env.StandardEnvironment
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono

/** 로그인 유지의 HTTP 계약: 어떤 쿠키를 어떤 속성으로 내리고 지우는가. 본문에는 토큰을 싣지 않는다. */
class AuthControllerRefreshTest {

    private val userRepository = mockk<UserRepository>()
    private val passwordEncoder = mockk<PasswordEncoder> {
        every { matches(any(), any()) } returns true
        every { encode(any()) } returns "hash"
    }
    private val jwtProvider = mockk<JwtProvider> {
        every { generateToken(any(), any()) } returns "access-jwt"
        every { expiration } returns java.time.Duration.ofHours(1)
    }
    private val refreshTokens = mockk<RefreshTokenService>(relaxed = true)
    private val user = UserEntity(id = 1L, username = "owner", password = "hash")

    // 앱의 ObjectMapper 와 같은 이름 규칙 — camelCase 키는 조용히 버려진다.
    private val mapper = jacksonObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    private val client = WebTestClient.bindToController(
        AuthController(
            userRepository, passwordEncoder, jwtProvider, RequestValidators(),
            mockk<UserSecretsService> { every { encryptUpbitKeys(null, null) } returns (null to null) },
            StandardEnvironment(), refreshTokens, Dispatchers.Unconfined,
        ),
    ).httpMessageCodecs {
        it.defaultCodecs().jackson2JsonDecoder(Jackson2JsonDecoder(mapper))
        it.defaultCodecs().jackson2JsonEncoder(Jackson2JsonEncoder(mapper))
    }.build()

    private fun cookies(result: WebTestClient.ResponseSpec) =
        result.returnResult(String::class.java).responseHeaders[HttpHeaders.SET_COOKIE].orEmpty()

    private fun login(remember: Boolean) = client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""{"username":"owner","password":"long-enough-pass","remember":$remember}""").exchange()

    @Test
    fun `login sets an httpOnly strict refresh cookie scoped to the auth path, remembered for thirty days`() {
        every { userRepository.findByUsername("owner") } returns Mono.just(user)
        coEvery { refreshTokens.issue(1L, true) } returns RefreshTokenService.Issued("rt-1", Duration.ofDays(30))

        val set = cookies(login(remember = true).expectStatus().isOk)

        val refresh = set.single { it.startsWith("refresh=") }
        assertThat(refresh).contains("refresh=rt-1").contains("Path=/api/auth").contains("HttpOnly").contains("SameSite=Strict")
        assertThat(Regex("Max-Age=(\\d+)").find(refresh)!!.groupValues[1].toLong()).isEqualTo(30L * 24 * 3600)
        assertThat(set).anySatisfy { assertThat(it).startsWith("token=access-jwt") }
        coVerify { refreshTokens.issue(1L, true) }
    }

    // 접근 JWT 쿠키는 JWT 수명만큼만 남긴다 — 만료 뒤에는 로그인 유지 쿠키로 새로 받는다.
    @Test
    fun `the access token cookie lives as long as the JWT`() {
        every { userRepository.findByUsername("owner") } returns Mono.just(user)

        val token = cookies(login(remember = false).expectStatus().isOk).single { it.startsWith("token=") }

        assertThat(token).contains("Max-Age=3600").contains("Path=/").contains("HttpOnly").contains("SameSite=Lax")
    }

    @Test
    fun `login without remember and registration issue a session refresh token`() {
        every { userRepository.findByUsername("owner") } returns Mono.just(user)
        every { userRepository.count() } returns Mono.just(0L)
        every { userRepository.save(any()) } returns Mono.just(user)

        login(remember = false).expectStatus().isOk
        client.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username":"owner","password":"long-enough-pass"}""").exchange().expectStatus().isOk

        coVerify(exactly = 2) { refreshTokens.issue(1L, false) }
    }

    @Test
    fun `refresh swaps both cookies and puts no token in the body`() {
        coEvery { refreshTokens.refresh<UserEntity>("rt-1", any()) } returns RefreshTokenService.Refresh.Rotated(user, "rt-2", Duration.ofHours(1))

        val result = client.post().uri("/api/auth/refresh").cookie("refresh", "rt-1").exchange().expectStatus().isOk
        val body = result.expectBody(String::class.java).returnResult()

        val set = body.responseHeaders[HttpHeaders.SET_COOKIE].orEmpty()
        assertThat(set).anySatisfy { assertThat(it).startsWith("token=access-jwt") }
        assertThat(set).anySatisfy { assertThat(it).startsWith("refresh=rt-2").contains("Path=/api/auth") }
        assertThat(body.responseBody).doesNotContain("access-jwt").doesNotContain("rt-2")
    }

    // 다른 탭이 같은 토큰으로 방금 바꿨다 — 쿠키를 지우면 그 탭이 받은 새 쿠키까지 지운다.
    @Test
    fun `a concurrent refresh is refused without touching cookies`() {
        coEvery { refreshTokens.refresh<UserEntity>("rt-1", any()) } returns RefreshTokenService.Refresh.Concurrent

        val set = cookies(client.post().uri("/api/auth/refresh").cookie("refresh", "rt-1").exchange().expectStatus().isUnauthorized)

        assertThat(set).isEmpty()
    }

    @Test
    fun `a rejected or missing refresh clears the refresh cookie on its own path`() {
        coEvery { refreshTokens.refresh<UserEntity>("bad", any()) } returns RefreshTokenService.Refresh.Rejected

        listOf(
            client.post().uri("/api/auth/refresh").cookie("refresh", "bad"),
            client.post().uri("/api/auth/refresh"),
        ).forEach { request ->
            val set = cookies(request.exchange().expectStatus().isUnauthorized)
            assertThat(set).singleElement().satisfies({ assertThat(it).startsWith("refresh=;").contains("Max-Age=0").contains("Path=/api/auth") })
        }
    }

    // 갱신은 사용자를 사용자 표에서 다시 읽는다 — 행만 믿지 않는다.
    @Test
    fun `refresh reads the user from the user table`() {
        val loader = slot<suspend (Long) -> UserEntity?>()
        coEvery { refreshTokens.refresh("rt-1", capture(loader)) } returns RefreshTokenService.Refresh.Rejected
        every { userRepository.findById(7L) } returns Mono.just(user)

        client.post().uri("/api/auth/refresh").cookie("refresh", "rt-1").exchange().expectStatus().isUnauthorized

        assertThat(kotlinx.coroutines.runBlocking { loader.captured(7L) }).isEqualTo(user)
    }

    // 다시 로그인하면 이 브라우저에 남은 옛 로그인을 끊는다 — 새어 나간 옛 토큰이 더는 쓰이지 않게.
    @Test
    fun `login revokes the login the browser already held`() {
        every { userRepository.findByUsername("owner") } returns Mono.just(user)

        client.post().uri("/api/auth/login").cookie("refresh", "old-rt").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username":"owner","password":"long-enough-pass","remember":true}""").exchange().expectStatus().isOk

        coVerify { refreshTokens.revoke("old-rt") }
    }

    @Test
    fun `logout clears both cookies even when the server cannot revoke`() {
        coEvery { refreshTokens.revoke(any()) } throws IllegalStateException("db down")

        val set = cookies(client.post().uri("/api/auth/logout").cookie("refresh", "rt-1").exchange().expectStatus().isOk)

        assertThat(set).anySatisfy { assertThat(it).startsWith("token=;").contains("Max-Age=0") }
        assertThat(set).anySatisfy { assertThat(it).startsWith("refresh=;").contains("Max-Age=0") }
    }

    @Test
    fun `logout revokes the login and clears both cookies even when the access token has expired`() {
        val set = cookies(client.post().uri("/api/auth/logout").cookie("refresh", "rt-1").exchange().expectStatus().isOk)

        coVerify { refreshTokens.revoke("rt-1") }
        assertThat(set).anySatisfy { assertThat(it).startsWith("token=;").contains("Max-Age=0").contains("Path=/") }
        assertThat(set).anySatisfy { assertThat(it).startsWith("refresh=;").contains("Max-Age=0").contains("Path=/api/auth") }
    }
}
