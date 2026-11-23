package com.trading.bot.api

import com.trading.bot.persistence.OrderRowRepository
import com.trading.bot.persistence.entity.OrderRowEntity
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class TradeHistoryControllerTest {

    private val userId = 1L
    private val authContext = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken(userId, null, emptyList())
    )
    private val repository = mockk<OrderRowRepository>()
    private val controller = TradeHistoryController(repository, RequestValidators(), ZoneOffset.UTC)

    private fun <T : Any> authed(block: suspend () -> T): T =
        mono { block() }.contextWrite(authContext).block()!!

    private fun records(count: Int): Flux<OrderRowEntity> = Flux.fromIterable(
        (0 until count).map { i ->
            OrderRowEntity(
                id = i.toLong(),
                userId = userId,
                exchange = "UPBIT",
                market = "KRW-BTC",
                side = if (i % 2 == 0) "BUY" else "SELL",
                status = "FILLED",
                decisionPrice = 100.0,
                filledVolume = 1.0,
                positionVolumeAfter = 1.0,
                bookAvgPrice = 100.0,
                fillSource = "ORDER",
                completedAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(60L * i),
            )
        },
    )

    @Test
    fun `조회 결과가 상한과 정확히 같으면 잘린 것이 아니다`() {
        val max = TradeHistoryController.MAX_SOURCE_RECORDS
        // 상한+1 을 요청해 상한만큼만 오면 다음 페이지가 없다.
        every { repository.findFilledRecentAscending(userId, "UPBIT", max + 1) } returns records(max)

        val result = authed { controller.getRoundTrips(100) }

        assertFalse(result["truncated"] as Boolean, "정확히 상한이면 잘린 것이 없는데 truncated 로 표시됐다")
    }

    @Test
    fun `상한을 넘으면 truncated 로 표시된다`() {
        val max = TradeHistoryController.MAX_SOURCE_RECORDS
        every { repository.findFilledRecentAscending(userId, "UPBIT", max + 1) } returns records(max + 1)

        val result = authed { controller.getRoundTrips(100) }

        assertTrue(result["truncated"] as Boolean)
    }

    @Test
    fun `화면 값 칼럼이 빈 체결 줄도 0 으로 채워 화면에 낸다`() {
        val empty = OrderRowEntity(id = 7L, userId = userId, exchange = "UPBIT", market = "KRW-BTC", side = "SELL", status = "FILLED", fillSource = "ORDER")
        every { repository.findFilledPage(userId, "UPBIT", 100, 0) } returns Flux.just(empty)
        every { repository.countFilled(userId, "UPBIT") } returns Mono.just(1L)

        val rows = authed { controller.getTrades(100, 0) }["records"] as List<*>

        assertEquals(listOf(7L to 0.0), rows.map { (it as TradeHistoryRow).id to it.price })
    }

    @Test
    fun `쪽 나누기는 저장소 쿼리가 한다`() {
        every { repository.findFilledPage(userId, "UPBIT", 7, 3) } returns records(2)
        every { repository.countFilled(userId, "UPBIT") } returns Mono.just(9L)

        val result = authed { controller.getTrades(7, 3) }

        assertEquals(9L, result["total"])
        assertEquals(listOf(0L, 1L), (result["records"] as List<*>).map { (it as TradeHistoryRow).id })
    }
}
