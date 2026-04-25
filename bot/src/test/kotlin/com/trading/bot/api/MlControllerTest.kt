package com.trading.bot.api

import com.trading.bot.client.UpbitClient
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.ml.HyperparameterTuner
import com.trading.bot.ml.MlModelService
import com.trading.bot.ml.TrainResult
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

class MlControllerTest {

    private val userId = 42L
    private val authContext = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken(userId, null, emptyList())
    )

    private val mlModelService = mockk<MlModelService>()
    private val tradingManager = mockk<UserTradingManager>()
    private val userRepo = mockk<UserRepository>()
    private val secrets = mockk<UserSecretsService>()
    private val tuner = mockk<HyperparameterTuner>()
    private val controller = MlController(mlModelService, tradingManager, userRepo, secrets, tuner)

    private fun <T : Any> authed(block: suspend () -> T): T =
        mono { block() }.contextWrite(authContext).block()!!

    private val encryptedUser = UserEntity(
        id = userId, username = "u", password = "x",
        upbitAccessKey = "ENCRYPTED::aaa==", upbitSecretKey = "ENCRYPTED::bbb==",
    )
    private val decryptedUser = encryptedUser.copy(
        upbitAccessKey = "raw_access", upbitSecretKey = "raw_secret",
    )

    @Test
    fun `train decrypts user secrets before constructing Upbit client`() {
        every { userRepo.findById(userId) } returns Mono.just(encryptedUser)
        every { secrets.decryptUserSecrets(encryptedUser) } returns decryptedUser
        val mockClient = mockk<UpbitClient>()
        every { tradingManager.createUpbitClient(decryptedUser) } returns mockClient
        coEvery { mockClient.getDayCandles(any(), any()) } returns emptyList()
        every { mlModelService.train(any(), any(), any(), any()) } returns
            TrainResult(success = false, error = "no data")

        authed { controller.train(TrainRequest(ticker = "KRW-BTC")) }

        verify { secrets.decryptUserSecrets(encryptedUser) }
        verify { tradingManager.createUpbitClient(decryptedUser) }
        // Regression guard: encrypted entity must never be passed to client builder
        verify(exactly = 0) { tradingManager.createUpbitClient(encryptedUser) }
    }

    @Test
    fun `predict decrypts user secrets before constructing Upbit client`() {
        every { userRepo.findById(userId) } returns Mono.just(encryptedUser)
        every { secrets.decryptUserSecrets(encryptedUser) } returns decryptedUser
        every { mlModelService.hasModel("KRW-BTC") } returns true
        val mockClient = mockk<UpbitClient>()
        every { tradingManager.createUpbitClient(decryptedUser) } returns mockClient
        coEvery { mockClient.getDayCandles(any(), any()) } returns emptyList()
        every { mlModelService.predict(any(), any()) } returns null

        assertThrows<ResponseStatusException> {
            authed { controller.predict("KRW-BTC") }
        }

        verify { secrets.decryptUserSecrets(encryptedUser) }
        verify { tradingManager.createUpbitClient(decryptedUser) }
        verify(exactly = 0) { tradingManager.createUpbitClient(encryptedUser) }
    }

    @Test
    fun `train returns 400 when user has no Upbit keys`() {
        val noKeys = encryptedUser.copy(upbitAccessKey = null, upbitSecretKey = null)
        every { userRepo.findById(userId) } returns Mono.just(noKeys)

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.train(TrainRequest(ticker = "KRW-BTC")) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}
