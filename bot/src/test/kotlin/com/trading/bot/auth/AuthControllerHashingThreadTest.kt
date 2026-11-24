package com.trading.bot.auth

import com.trading.bot.api.RequestValidators
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import org.springframework.core.env.StandardEnvironment
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.security.crypto.password.PasswordEncoder
import reactor.core.publisher.Mono

/**
 * 비밀번호 해시(BCrypt, 수십~100ms CPU)는 요청을 받은 스레드가 아니라 해시 전용 디스패처에서 돈다. 요청 스레드가 netty 이벤트
 * 루프면 그동안 같은 루프의 시세 프레임·주문 응답·DB 응답이 밀린다.
 */
class AuthControllerHashingThreadTest {

    private val userRepository = mockk<UserRepository>()
    private val hashedOn = java.util.Collections.synchronizedList(mutableListOf<Thread>())
    private val hashThread = Executors.newSingleThreadExecutor { Thread(it, "hash-lane") }
    private val passwordEncoder = object : PasswordEncoder {
        override fun encode(rawPassword: CharSequence): String = "hash".also { hashedOn += Thread.currentThread() }
        override fun matches(rawPassword: CharSequence, encodedPassword: String): Boolean = true.also { hashedOn += Thread.currentThread() }
    }
    private val jwtProvider = mockk<JwtProvider> { every { generateToken(any(), any()) } returns "jwt" }
    private val secrets = mockk<UserSecretsService> { every { encryptUpbitKeys(null, null) } returns (null to null) }
    private val controller = AuthController(
        userRepository, passwordEncoder, jwtProvider, RequestValidators(), secrets, StandardEnvironment(), refreshTokens(), hashThread.asCoroutineDispatcher(),
    )
    private val user = UserEntity(id = 1, username = "owner", password = "hash")

    @Test
    fun `login and registration hash the password on the hash dispatcher, not the calling thread`() {
        every { userRepository.findByUsername("owner") } returns Mono.just(user)
        every { userRepository.count() } returns Mono.just(0L)
        every { userRepository.save(any()) } returns Mono.just(user)
        val caller = Thread.currentThread()

        runBlocking {
            controller.login(AuthRequest("owner", "long-enough-pass"), MockServerHttpRequest.post("http://localhost/api/auth/login").build(), MockServerHttpResponse())
            controller.register(AuthRequest("owner", "long-enough-pass"), MockServerHttpRequest.post("http://localhost/api/auth/register").build(), MockServerHttpResponse())
        }

        assertEquals(2, hashedOn.size, "matches·encode 가 한 번씩 불려야 한다")
        hashedOn.forEach { assertNotSame(caller, it, "해시가 요청 스레드(${caller.name})에서 돌았다") }
        assertEquals(listOf("hash-lane", "hash-lane"), hashedOn.map { it.name })
        hashThread.shutdown()
    }
}
