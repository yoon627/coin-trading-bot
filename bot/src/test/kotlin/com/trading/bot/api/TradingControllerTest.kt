package com.trading.bot.api

import com.trading.bot.engine.UserTradingManager
import com.trading.bot.engine.RELOAD_FAILED_ENGINE_STOPPED_MESSAGE
import com.trading.bot.engine.RELOAD_FAILED_MESSAGE
import com.trading.bot.engine.RuntimeReloadFailedException
import com.trading.bot.kis.engine.StockUserTradingManager
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

class TradingControllerTest {

    private val userId = 42L
    private val authContext = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken(userId, null, emptyList())
    )

    private val manager = mockk<UserTradingManager>()
    private val userRepo = mockk<UserRepository>()
    private val validators = RequestValidators()
    private val secrets = mockk<UserSecretsService>()
    private val stockManager = mockk<StockUserTradingManager>(relaxed = true)
    private val controller = TradingController(manager, userRepo, validators, secrets, stockManager)

    private fun <T : Any> authed(block: suspend () -> T): T =
        mono { block() }.contextWrite(authContext).block()!!

    @Test
    fun `startBot surfaces missing-keys error as 400 instead of 200`() {
        coEvery { manager.startBot(userId, null, null) } returns
            mapOf("error" to "Upbit API keys not configured. Set them via /api/user/keys")

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.startBot(null) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `startBot surfaces user-not-found error as 404`() {
        coEvery { manager.startBot(userId, null, null) } returns
            mapOf("error" to "User not found")

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.startBot(null) }
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `startBot returns success map without throwing on happy path`() {
        coEvery { manager.startBot(userId, null, null) } returns
            mapOf("status" to "started", "strategy" to "volatility_breakout")

        val result = authed { controller.startBot(null) }
        assertEquals("started", result["status"])
        assertEquals("volatility_breakout", result["strategy"])
    }

    @Test
    fun `changeStrategy throws 400 on unknown strategy instead of 200 with error body`() {
        coEvery { manager.setStrategy(userId, "nonexistent") } returns false

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.changeStrategy(StrategyRequest("nonexistent")) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `changeStrategy returns success map on valid strategy`() {
        coEvery { manager.setStrategy(userId, "volatility_breakout") } returns true

        val result = authed { controller.changeStrategy(StrategyRequest("volatility_breakout")) }
        assertEquals("changed", result["status"])
        assertEquals("volatility_breakout", result["strategy"])
    }

    @Test
    fun `setKisKeys encrypts, saves and reloads the running stock engine`() {
        every { userRepo.findById(userId) } returns Mono.just(UserEntity(id = userId, username = "u", password = "p"))
        every { secrets.encryptKisKeys(any(), any()) } returns ("enc:ak" to "enc:sk")
        val saved = slot<UserEntity>()
        every { userRepo.save(capture(saved)) } answers { Mono.just(saved.captured) }

        val result = authed {
            controller.setKisKeys(
                KisKeysRequest(
                    appKey = "PSAPPKEY1234567890ABCDEF",
                    appSecret = "SECRET1234567890ABCDEFGHIJKLMNOPQRSTUVWX",
                    cano = "12345678", acntPrdtCd = "01", paper = true,
                ),
            )
        }

        assertEquals("saved", result["status"])
        // 평문이 아니라 암호화된 값이 저장돼야 한다.
        assertEquals("enc:ak", saved.captured.kisAppKey)
        assertEquals("enc:sk", saved.captured.kisAppSecret)
        assertEquals("12345678", saved.captured.kisCano)
        assertTrue(saved.captured.kisPaper)
        // 캐시 무효화·엔진 교체는 매니저 몫 — 호출하지 않으면 실행 중 봇이 옛 계좌로 계속 주문한다(#91).
        coVerify(exactly = 1) { stockManager.reloadUserRuntime(userId) }
    }

    @Test
    fun `setKisKeys reports 503 with the restored-engine message when reload fails but the old engine came back`() {
        every { userRepo.findById(userId) } returns Mono.just(UserEntity(id = userId, username = "u", password = "p"))
        every { secrets.encryptKisKeys(any(), any()) } returns ("enc:ak" to "enc:sk")
        every { userRepo.save(any()) } answers { Mono.just(firstArg()) }
        coEvery { stockManager.reloadUserRuntime(userId) } throws
            RuntimeReloadFailedException(userId, IllegalStateException("db down"), engineRestored = true)

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.setKisKeys(validKisKeys()) }
        }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.statusCode)
        assertEquals(RELOAD_FAILED_MESSAGE, ex.reason)
    }

    @Test
    fun `setKisKeys reports 503 with the engine-stopped message when the old engine could not be restored`() {
        every { userRepo.findById(userId) } returns Mono.just(UserEntity(id = userId, username = "u", password = "p"))
        every { secrets.encryptKisKeys(any(), any()) } returns ("enc:ak" to "enc:sk")
        every { userRepo.save(any()) } answers { Mono.just(firstArg()) }
        coEvery { stockManager.reloadUserRuntime(userId) } throws
            RuntimeReloadFailedException(userId, IllegalStateException("cannot restart"), engineRestored = false)

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.setKisKeys(validKisKeys()) }
        }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.statusCode)
        assertEquals(RELOAD_FAILED_ENGINE_STOPPED_MESSAGE, ex.reason)
    }

    @Test
    fun `setKisKeys rejects malformed cano with 400`() {
        every { userRepo.findById(userId) } returns Mono.just(UserEntity(id = userId, username = "u", password = "p"))

        val ex = assertThrows<ResponseStatusException> {
            authed {
                controller.setKisKeys(
                    KisKeysRequest(
                        appKey = "PSAPPKEY1234567890ABCDEF",
                        appSecret = "SECRET1234567890ABCDEFGHIJKLMNOPQRSTUVWX",
                        cano = "123", acntPrdtCd = "01",
                    ),
                )
            }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    private fun validKisKeys() = KisKeysRequest(
        appKey = "PSAPPKEY1234567890ABCDEF",
        appSecret = "SECRET1234567890ABCDEFGHIJKLMNOPQRSTUVWX",
        cano = "12345678", acntPrdtCd = "01", paper = true,
    )
}
