package com.trading.bot.auth

import com.trading.bot.api.RequestValidators
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.env.StandardEnvironment
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/** bcrypt 입력 한계(UTF-8 72바이트)를 넘는 비밀번호가 실제 인코더에서 500 이 되지 않는지 본다. */
class AuthControllerPasswordLengthTest {

    private val userRepository = mockk<UserRepository>()
    private val passwordEncoder = BCryptPasswordEncoder(4)
    private val jwtProvider = mockk<JwtProvider>()
    private val secrets = mockk<UserSecretsService>()
    private val controller = AuthController(
        userRepository, passwordEncoder, jwtProvider, RequestValidators(), secrets, StandardEnvironment(),
    )

    // 25자지만 75바이트 — 문자 수 상한(72)은 통과하고 bcrypt 한계는 넘는다.
    private val overLimit = "가".repeat(25)

    private fun request() = MockServerHttpRequest.post("http://localhost/api/auth").build()

    private fun Result<*>.status() = (exceptionOrNull() as? ResponseStatusException)?.statusCode

    @Test
    fun `registering with a password over 72 UTF-8 bytes is a 400 before hashing`() = runTest {
        every { userRepository.count() } returns Mono.just(0L)
        every { secrets.encryptUpbitKeys(null, null) } returns (null to null)
        every { userRepository.save(any()) } answers { Mono.just(firstArg<UserEntity>().copy(id = 1L)) }
        every { jwtProvider.generateToken(any(), any()) } returns "jwt"

        val result = runCatching {
            controller.register(AuthRequest("owner", overLimit), request(), MockServerHttpResponse())
        }

        assertEquals(HttpStatus.BAD_REQUEST, result.status(), "$result")
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `logging in with a password over 72 UTF-8 bytes is a 401, not a 500`() = runTest {
        val stored = UserEntity(id = 1L, username = "owner", password = passwordEncoder.encode("owner-password-1"))
        every { userRepository.findByUsername("owner") } returns Mono.just(stored)

        val result = runCatching {
            controller.login(AuthRequest("owner", overLimit), request(), MockServerHttpResponse())
        }

        assertEquals(HttpStatus.UNAUTHORIZED, result.status(), "$result")
    }

    @Test
    fun `an account registered before the byte cap still logs in with its over-limit password`() = runTest {
        // 옛 검증기는 문자 수만 봐서 75바이트 비밀번호를 받았고, bcrypt 는 앞 72바이트로 해시했다.
        val stored = UserEntity(id = 1L, username = "owner", password = passwordEncoder.encode("가".repeat(24)))
        every { userRepository.findByUsername("owner") } returns Mono.just(stored)
        every { jwtProvider.generateToken(1L, "owner") } returns "jwt"

        val result = runCatching {
            controller.login(AuthRequest("owner", overLimit), request(), MockServerHttpResponse())
        }

        assertEquals("owner", result.getOrThrow().username)
    }
}
