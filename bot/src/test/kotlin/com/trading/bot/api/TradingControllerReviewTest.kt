package com.trading.bot.api

import com.trading.bot.engine.TradingManagers
import com.trading.bot.engine.UserTradingManager
import com.trading.common.domain.Exchange
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Duration
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException

/** 주문 확인이 남은 종목의 halt 해제 거절은 409 로 나간다. */
class TradingControllerReviewTest {

    private val userId = 42L
    private val manager = mockk<UserTradingManager>()
    private val controller = TradingController(TradingManagers.of(Exchange.UPBIT to manager), mockk(), RequestValidators(), mockk())

    @Test
    fun `halt clear refused for an order review is 409`() {
        coEvery { manager.clearHalt(userId, "KRW-BTC") } returns
            mapOf("error" to "KRW-BTC 는 주문 확인이 필요하다", "code" to UserTradingManager.CONFLICT_CODE)

        val ex = assertThrows<ResponseStatusException> {
            mono { controller.clearHalt(ClearHaltRequest("KRW-BTC")) }
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(UsernamePasswordAuthenticationToken(userId, null, emptyList())))
                .block(Duration.ofSeconds(5))
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }

    @Test
    fun `a cleared halt is answered as before`() {
        coEvery { manager.clearHalt(userId, "KRW-BTC") } returns mapOf("status" to "cleared")

        val result = mono { controller.clearHalt(ClearHaltRequest("KRW-BTC")) }
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(UsernamePasswordAuthenticationToken(userId, null, emptyList())))
            .block(Duration.ofSeconds(5))

        assertEquals(mapOf("status" to "cleared"), result)
    }
}
