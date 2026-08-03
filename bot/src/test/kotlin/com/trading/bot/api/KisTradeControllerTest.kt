package com.trading.bot.api

import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.client.KisClientFactory
import com.trading.bot.kis.config.KisProperties
import com.trading.bot.kis.marketdata.KisMarketCalendar
import com.trading.bot.kis.order.StockOrderService
import com.trading.bot.kis.order.StockOrderValidationException
import com.trading.bot.kis.order.SubmitOrderCommand
import com.trading.bot.persistence.StockOrderIntentRepository
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.StockOrderIntentEntity
import com.trading.bot.persistence.entity.UserEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class KisTradeControllerTest {

    private val userId = 42L
    private val authContext = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken(userId, null, emptyList())
    )

    private val userRepo = mockk<UserRepository>()
    private val kisClientFactory = mockk<KisClientFactory>()
    private val stockOrderService = mockk<StockOrderService>()
    private val intentRepo = mockk<StockOrderIntentRepository>()
    private val validators = RequestValidators()
    private val marketCalendar = mockk<KisMarketCalendar>()
    private val controller = KisTradeController(
        userRepo, kisClientFactory, stockOrderService, intentRepo, validators, marketCalendar,
        KisProperties(liveEnabled = true),
    )

    init {
        // 기본은 장중 — 게이트 자체를 검증하는 테스트만 false 로 덮는다.
        every { marketCalendar.isTradingNow() } returns true
    }

    private fun <T : Any> authed(block: suspend () -> T): T =
        mono { block() }.contextWrite(authContext).block()!!

    private fun userWithKeys() = UserEntity(
        id = userId, username = "u", password = "p",
        kisAppKey = "enc:ak", kisAppSecret = "enc:sk", kisCano = "12345678", kisAcntPrdtCd = "01",
    )

    @Test
    fun `placeOrder is rejected outside market hours and never reaches WAL`() {
        every { marketCalendar.isTradingNow() } returns false
        every { userRepo.findById(userId) } returns Mono.just(userWithKeys())

        val ex = assertThrows<ResponseStatusException> {
            authed {
                controller.placeOrder(
                    KisOrderApiRequest(symbol = "005930", side = "buy", orderType = "limit", qty = 10, price = 70000),
                )
            }
        }

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.statusCode)
        // 게이트가 WAL 앞에 서야 미접수 주문 흔적이 남지 않는다.
        coVerify(exactly = 0) { stockOrderService.submit(any(), any()) }
    }

    @Test
    fun `placeOrder submits through WAL and returns intent status`() {
        every { userRepo.findById(userId) } returns Mono.just(userWithKeys())
        every { kisClientFactory.forUser(any()) } returns mockk<KisClient>()
        val cmd = slot<SubmitOrderCommand>()
        coEvery { stockOrderService.submit(any(), capture(cmd)) } returns StockOrderIntentEntity(
            id = 1, userId = userId, clientRef = "ref", accountNo = "12345678-01", symbol = "005930",
            side = "BUY", orderType = "LIMIT", qty = 10, status = "DRY_RUN", orderDate = "20260614",
        )

        val result = authed {
            controller.placeOrder(KisOrderApiRequest(symbol = "005930", side = "buy", orderType = "limit", qty = 10, price = 70000))
        }

        assertEquals("DRY_RUN", result["status"])
        assertEquals(1L, result["id"])
        // 컨트롤러가 사용자 계좌(cano/prdt)를 커맨드에 정확히 채우는지.
        assertEquals("12345678", cmd.captured.cano)
        assertEquals("01", cmd.captured.acntPrdtCd)
        assertEquals("005930", cmd.captured.symbol)
        coVerify { kisClientFactory.forUser(any()) }
    }

    @Test
    fun `placeOrder rejects when KIS keys not configured`() {
        every { userRepo.findById(userId) } returns Mono.just(UserEntity(id = userId, username = "u", password = "p"))

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.placeOrder(KisOrderApiRequest(symbol = "005930", side = "BUY", qty = 1, price = 100)) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        coVerify(exactly = 0) { stockOrderService.submit(any(), any()) }
    }

    @Test
    fun `placeOrder rejects invalid symbol`() {
        every { userRepo.findById(userId) } returns Mono.just(userWithKeys())

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.placeOrder(KisOrderApiRequest(symbol = "AAPL", side = "BUY", qty = 1, price = 100)) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `placeOrder maps validation failure to 400`() {
        every { userRepo.findById(userId) } returns Mono.just(userWithKeys())
        every { kisClientFactory.forUser(any()) } returns mockk<KisClient>()
        coEvery { stockOrderService.submit(any(), any()) } throws StockOrderValidationException("active order exists")

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.placeOrder(KisOrderApiRequest(symbol = "005930", side = "BUY", qty = 1, price = 100)) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `listOrders returns user's intents`() {
        every { intentRepo.findByUserId(userId, any()) } returns Flux.just(
            StockOrderIntentEntity(
                id = 5, userId = userId, clientRef = "r", accountNo = "12345678-01", symbol = "005930",
                side = "BUY", orderType = "LIMIT", qty = 10, status = "FILLED", orderDate = "20260614", executedQty = 10,
            ),
        )

        val result = authed { controller.listOrders(50) }

        assertEquals(1, result.size)
        assertEquals("005930", result[0]["symbol"])
        assertEquals("FILLED", result[0]["status"])
    }
}
