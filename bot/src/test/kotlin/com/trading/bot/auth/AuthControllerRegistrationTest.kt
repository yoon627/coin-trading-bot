package com.trading.bot.auth

import com.trading.bot.api.RequestValidators
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.env.StandardEnvironment
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks

/** 가입은 계정이 하나도 없는 서버에서 첫 계정을 만들 때만 받는다 — 한 명만 쓰는 봇이 인터넷에 열려 있다. */
class AuthControllerRegistrationTest {

    private val userRepository = mockk<UserRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val jwtProvider = mockk<JwtProvider>()
    private val secrets = mockk<UserSecretsService>()
    private val controller = AuthController(
        userRepository, passwordEncoder, jwtProvider, RequestValidators(), secrets, StandardEnvironment(),
    )

    private fun request() = MockServerHttpRequest.post("http://localhost/api/auth/register").build()
    private val password = "long-enough-pass"

    @BeforeEach
    fun setup() {
        every { passwordEncoder.encode(any()) } returns "hash"
        every { secrets.encryptUpbitKeys(null, null) } returns (null to null)
        every { jwtProvider.generateToken(any(), any()) } returns "jwt"
    }

    private suspend fun register(username: String, response: MockServerHttpResponse = MockServerHttpResponse()) =
        runCatching { controller.register(AuthRequest(username, password), request(), response) }

    private fun Result<*>.status() = (exceptionOrNull() as? ResponseStatusException)?.statusCode

    @Test
    fun `registration is refused once an account exists`() = runTest {
        every { userRepository.count() } returns Mono.just(1L)

        val result = register("intruder")

        assertEquals(HttpStatus.FORBIDDEN, result.status(), "$result")
        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
    }

    @Test
    fun `a closed server refuses before validating the input`() = runTest {
        // 닫힌 서버가 검증 오류로 사용자명 규칙을 알려 줄 이유가 없다.
        every { userRepository.count() } returns Mono.just(1L)

        assertEquals(HttpStatus.FORBIDDEN, register("!!").status())
    }

    @Test
    fun `the first account is created on an empty server`() = runTest {
        every { userRepository.count() } returns Mono.just(0L)
        every { userRepository.save(any()) } answers { Mono.just(firstArg<UserEntity>().copy(id = 1L)) }
        val response = MockServerHttpResponse()

        val result = register("owner", response)

        assertEquals("owner", result.getOrThrow().username)
        verify(exactly = 1) { userRepository.save(any()) }
        assertNotNull(response.cookies.getFirst("token"), "첫 가입은 곧바로 로그인 상태가 된다")
    }

    @Test
    fun `two simultaneous registrations on an empty server create only one account`() = runTest {
        // 확인(count)과 저장이 끊기면 두 요청이 모두 0 을 보고 계정이 둘 생긴다. 저장을 게이트에서 멈춰 두 요청이 겹치게 한다.
        var saved = 0
        every { userRepository.count() } answers { Mono.just(saved.toLong()) }
        val gate = Sinks.one<Unit>()
        every { userRepository.save(any()) } answers {
            val entity = firstArg<UserEntity>()
            gate.asMono().then(Mono.fromCallable { saved++; entity.copy(id = saved.toLong()) })
        }

        val outcomes = listOf("owner", "intruder").map { name -> async { register(name) } }
        advanceUntilIdle()
        gate.tryEmitValue(Unit)
        val results = outcomes.awaitAll()

        assertEquals(1, saved)
        assertEquals(1, results.count { it.isSuccess }, "$results")
        assertEquals(HttpStatus.FORBIDDEN, results.single { it.isFailure }.status())
    }
}
