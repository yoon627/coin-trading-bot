package com.trading.bot.api

import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.entity.TradeRecordEntity
import io.mockk.coEvery
import io.mockk.mockk
import java.time.LocalDateTime
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder

class TradeHistoryControllerTest {

    private val userId = 1L
    private val authContext = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken(userId, null, emptyList())
    )
    private val repository = mockk<TradeRecordRepository>()
    private val controller = TradeHistoryController(repository, RequestValidators())

    private fun <T : Any> authed(block: suspend () -> T): T =
        mono { block() }.contextWrite(authContext).block()!!

    /** BUY/SELL 을 번갈아 만들어 짝이 맞는 레코드 목록을 만든다. */
    private fun records(count: Int): List<TradeRecordEntity> = (0 until count).map { i ->
        TradeRecordEntity(
            id = i.toLong(),
            ticker = "KRW-BTC",
            side = if (i % 2 == 0) "BUY" else "SELL",
            price = 100.0,
            volume = 1.0,
            totalAmount = 100.0,
            userId = userId,
            createdAt = LocalDateTime.parse("2026-01-01T00:00").plusMinutes(i.toLong()),
        )
    }

    @Test
    fun `조회 결과가 상한과 정확히 같으면 잘린 것이 아니다`() {
        val max = TradeHistoryController.MAX_SOURCE_RECORDS
        // 상한+1 을 요청했는데 상한만큼만 돌아왔다 = 더 이상 없다
        coEvery { repository.findRecentAscending(userId, max + 1) } returns records(max)

        val result = authed { controller.getRoundTrips(100) }

        assertFalse(result["truncated"] as Boolean, "정확히 상한이면 잘린 것이 없는데 truncated 로 표시됐다")
    }

    @Test
    fun `상한을 넘으면 truncated 로 표시된다`() {
        val max = TradeHistoryController.MAX_SOURCE_RECORDS
        coEvery { repository.findRecentAscending(userId, max + 1) } returns records(max + 1)

        val result = authed { controller.getRoundTrips(100) }

        assertTrue(result["truncated"] as Boolean)
    }
}
