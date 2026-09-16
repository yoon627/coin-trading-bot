package com.trading.bot.api

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.FillOutcome
import com.trading.bot.engine.TradeExecutionResult
import com.trading.bot.engine.TradeExecutionService
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.core.publisher.Mono

class ManualTradeControllerTest {

    private val userId = 42L
    private val authContext = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken(userId, null, emptyList())
    )

    private val manager = mockk<UserTradingManager>()
    private val tradeExecutionService = mockk<TradeExecutionService>()
    private val userRepo = mockk<UserRepository>()
    private val secrets = mockk<UserSecretsService>()
    private val client = mockk<UpbitClient>()
    private val controller = ManualTradeController(manager, tradeExecutionService, userRepo, RequestValidators(), secrets)

    private val user = UserEntity(id = userId, username = "u", password = "p", upbitAccessKey = "ak", upbitSecretKey = "sk")

    init {
        every { userRepo.findById(userId) } returns Mono.just(user)
        every { secrets.decryptUserSecrets(user) } returns user
        every { manager.createUpbitClient(user) } returns client
        coEvery { manager.clearDurableEntryMeta(userId, any()) } returns true
    }

    private fun <T : Any> authed(block: suspend () -> T): T =
        mono { block() }.contextWrite(authContext).block()!!

    private fun confirmed(uuid: String) = TradeExecutionResult.success(uuid, fill = FillOutcome.CONFIRMED)

    @Test
    fun `manual sell of an engine position is attributed to the entry strategy`() {
        // 엔진이 combined 로 잡은 포지션을 사람이 청산해도 손익은 combined 의 것이다(#129) — 사유(MANUAL)와 귀속(strategy)은 축이 다르다.
        coEvery { manager.resolveEntryStrategy(userId, "KRW-BTC") } returns "combined"
        coEvery { tradeExecutionService.executeSellVolume(client, "KRW-BTC", "0.3", "combined", userId, "u", null) } returns confirmed("s1")
        coEvery { client.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.7"))

        val res = authed { controller.manualSell(ManualSellRequest(market = "KRW-BTC", volume = "0.3")) }

        assertEquals("s1", res["order_uuid"])
        coVerify(exactly = 1) { tradeExecutionService.executeSellVolume(client, "KRW-BTC", "0.3", "combined", userId, "u", null) }
        // 잔량이 남았으면 진입 메타를 지우지 않는다 — 남은 포지션의 귀속을 잃는다.
        coVerify(exactly = 0) { manager.clearDurableEntryMeta(any(), any()) }
    }

    @Test
    fun `manual sell without a known entry strategy stays in the manual bucket`() {
        coEvery { manager.resolveEntryStrategy(userId, "KRW-BTC") } returns null
        coEvery { tradeExecutionService.executeSellVolume(client, "KRW-BTC", "0.3", "manual", userId, "u", null) } returns
            TradeExecutionResult.unrecorded("s2", FillOutcome.UNCONFIRMED)

        authed { controller.manualSell(ManualSellRequest(market = "KRW-BTC", volume = "0.3")) }

        coVerify(exactly = 1) { tradeExecutionService.executeSellVolume(client, "KRW-BTC", "0.3", "manual", userId, "u", null) }
        // 체결을 확인하지 못했으면 잔고를 판정하지 않는다.
        coVerify(exactly = 0) { client.getAccounts() }
        coVerify(exactly = 0) { manager.clearDurableEntryMeta(any(), any()) }
    }

    @Test
    fun `selling out clears the durable entry meta so the next position is not attributed to it`() {
        // 엔진 OFF 에서 전량 청산 뒤 거래소에서 직접 산 새 포지션의 수동 매도가 옛 전략에 귀속되는 것을 막는다(D4).
        coEvery { manager.resolveEntryStrategy(userId, "KRW-BTC") } returns "combined"
        coEvery { tradeExecutionService.executeSellAll(client, "KRW-BTC", "combined", userId, "u", null) } returns confirmed("s3")
        coEvery { client.getAccounts() } returns listOf(Account(currency = "KRW", balance = "1000"))

        authed { controller.manualSell(ManualSellRequest(market = "KRW-BTC", sellAll = true)) }

        coVerify(exactly = 1) { manager.clearDurableEntryMeta(userId, "KRW-BTC") }
    }

    @Test
    fun `coins locked in another order still count as held`() {
        // free 는 0 이어도 locked 가 있으면 포지션은 살아 있다 — 지우면 남은 수량의 귀속과 보유일 게이트를 잃는다.
        coEvery { manager.resolveEntryStrategy(userId, "KRW-BTC") } returns "combined"
        coEvery { tradeExecutionService.executeSellAll(client, "KRW-BTC", "combined", userId, "u", null) } returns confirmed("s5")
        coEvery { client.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0", locked = "0.2"))

        authed { controller.manualSell(ManualSellRequest(market = "KRW-BTC", sellAll = true)) }

        coVerify(exactly = 0) { manager.clearDurableEntryMeta(any(), any()) }
    }

    @Test
    fun `an order that ended without a fill does not touch the entry meta`() {
        coEvery { manager.resolveEntryStrategy(userId, "KRW-BTC") } returns "combined"
        coEvery { tradeExecutionService.executeSellAll(client, "KRW-BTC", "combined", userId, "u", null) } returns
            TradeExecutionResult.unrecorded("s6", FillOutcome.NOT_FILLED)

        authed { controller.manualSell(ManualSellRequest(market = "KRW-BTC", sellAll = true)) }

        coVerify(exactly = 0) { client.getAccounts() }
        coVerify(exactly = 0) { manager.clearDurableEntryMeta(any(), any()) }
    }

    @Test
    fun `a balance lookup failure after the sell does not fail the request`() {
        coEvery { manager.resolveEntryStrategy(userId, "KRW-BTC") } returns "combined"
        coEvery { tradeExecutionService.executeSellAll(client, "KRW-BTC", "combined", userId, "u", null) } returns confirmed("s4")
        coEvery { client.getAccounts() } throws RuntimeException("upbit down")

        val res = authed { controller.manualSell(ManualSellRequest(market = "KRW-BTC", sellAll = true)) }

        assertEquals("s4", res["order_uuid"])
        coVerify(exactly = 0) { manager.clearDurableEntryMeta(any(), any()) }
    }
}
