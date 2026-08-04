package com.trading.bot.api

import com.trading.bot.engine.RuntimeReloadFailedException
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.kis.client.KisClientFactory
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono

/**
 * 런타임 교체 실패가 **실제 WebFlux 파이프라인에서** 503 + 안내 문구로 나오는지 검증한다.
 * 단위 테스트로는 상태코드를 보증할 수 없다 — 예외가 프레임워크를 거쳐야 응답이 정해진다.
 */
class ReloadFailureResponseWebFluxTest {

    private val userTradingManager: UserTradingManager = mockk(relaxed = true)
    private val userRepository: UserRepository = mockk()
    private val userSecretsService: UserSecretsService = mockk()
    private val requestValidators = RequestValidators()
    private val kisClientFactory: KisClientFactory = mockk(relaxed = true)

    private fun user() = UserEntity(id = 1L, username = "u", password = "p")

    /** ReactiveSecurityContextHolder 에 principal(userId)을 넣어 currentUserId() 가 동작하게 한다. */
    private fun authFilter(): WebFilter = WebFilter { exchange, chain ->
        chain.filter(exchange).contextWrite(
            ReactiveSecurityContextHolder.withAuthentication(
                UsernamePasswordAuthenticationToken(1L, null, emptyList()),
            ),
        )
    }

    private fun keysClient(): WebTestClient = WebTestClient
        .bindToController(
            TradingController(userTradingManager, userRepository, requestValidators, userSecretsService, kisClientFactory),
        )
        .webFilter<WebTestClient.ControllerSpec>(authFilter())
        .build()

    @Test
    fun `키 저장 후 런타임 교체가 실패하면 503 과 안내 문구가 나온다`() {
        every { userRepository.findById(1L) } returns Mono.just(user())
        every { userRepository.save(any()) } returns Mono.just(user())
        every { userSecretsService.encryptUpbitKeys(any(), any()) } returns ("enc-a" to "enc-s")
        coEvery { userTradingManager.reloadUserRuntime(1L) } throws
            RuntimeReloadFailedException(1L, RuntimeException("db down"), engineRestored = true)

        keysClient().post().uri("/api/user/keys")
            .header("Content-Type", "application/json")
            .bodyValue(mapOf("accessKey" to "A".repeat(32), "secretKey" to "B".repeat(32)))
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        // 이 reason 이 응답 body 의 message 로 노출되는 것은 SafeErrorAttributesTest
        // (`ResponseStatusException reason is exposed as message`)가 보장한다.
        // 여기서는 예외가 WebFlux 파이프라인을 거쳐 503 이 되는지만 확인한다.
    }

    @Test
    fun `안내 문구는 저장 성공·미반영·이전 설정·재시도를 모두 담는다`() {
        // "저장 실패"로 읽히면 사용자가 키를 다시 넣는 헛수고를 한다.
        val msg = com.trading.bot.engine.RELOAD_FAILED_MESSAGE
        assert(msg.contains("저장은 완료")) { "저장 성공 사실이 빠졌다: $msg" }
        assert(msg.contains("반영")) { "미반영 사실이 빠졌다: $msg" }
        assert(msg.contains("이전 설정")) { "이전 설정으로 거래 중이라는 사실이 빠졌다: $msg" }
        assert(msg.contains("다시 저장")) { "재시도 안내가 빠졌다: $msg" }
    }

    @Test
    fun `복구 실패는 정지 상태를 알리는 다른 문구를 쓴다`() {
        // 두 상황은 사용자가 할 조치가 정반대다 — 문구를 공유하면 정지된 봇을 방치하게 된다.
        val restored = com.trading.bot.engine.reloadFailureMessage(
            RuntimeReloadFailedException(1L, RuntimeException("x"), engineRestored = true),
        )
        val stopped = com.trading.bot.engine.reloadFailureMessage(
            RuntimeReloadFailedException(1L, RuntimeException("x"), engineRestored = false),
        )
        assert(restored != stopped) { "복구 성공/실패가 같은 문구를 쓰면 안 된다" }
        assert(stopped.contains("정지")) { "정지 사실이 빠졌다: $stopped" }
        assert(stopped.contains("손절")) { "손절이 멈춘다는 사실이 빠졌다: $stopped" }
    }

    @Test
    fun `런타임 교체가 성공하면 종전대로 200 이다`() {
        every { userRepository.findById(1L) } returns Mono.just(user())
        every { userRepository.save(any()) } returns Mono.just(user())
        every { userSecretsService.encryptUpbitKeys(any(), any()) } returns ("enc-a" to "enc-s")
        coEvery { userTradingManager.reloadUserRuntime(1L) } returns Unit

        keysClient().post().uri("/api/user/keys")
            .header("Content-Type", "application/json")
            .bodyValue(mapOf("accessKey" to "A".repeat(32), "secretKey" to "B".repeat(32)))
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("saved")
    }
}
