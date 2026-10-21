package com.trading.bot.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.entity.TradingStateEntity
import com.trading.common.domain.Exchange
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifySequence
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** Upbit 는 거래소 축 전의 조회 키를 쓰고 결과를 거래소로 거른다. 다른 거래소는 (user, exchange, ticker) 로 읽고 쓴다. */
class TradingStateServiceExchangeTest {

    private val repository = mockk<TradingStateRepository>()
    private val service = TradingStateService(repository, ObjectMapper())

    private val upbitRow = TradingStateEntity(id = 1L, userId = 1L, ticker = "KRW-BTC", pendingBuyUuid = "u-1")
    private val kisRow = TradingStateEntity(
        id = 2L, userId = 1L, exchange = "KIS_PAPER", ticker = "KRX-005930", pendingSellUuid = "KIS_PAPER:20261020:0001",
        reviewReason = "주문 확인 필요(SELL KIS_PAPER:20261020:0001): 후보 2건",
    )

    @Test
    fun `upbit loadStates reads by user only and drops other exchanges' rows`() = runTest {
        every { repository.findByUserId(1L) } returns Flux.just(upbitRow, kisRow)

        val states = service.loadStates(1L)

        assertEquals(listOf("KRW-BTC"), states.keys.toList())
        assertEquals(Exchange.UPBIT, states.getValue("KRW-BTC").exchange)
        verifySequence { repository.findByUserId(1L) }
    }

    @Test
    fun `a KIS loadStates reads the exchange's rows and maps the review reason`() = runTest {
        every { repository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Flux.just(kisRow)

        val state = service.loadStates(1L, Exchange.KIS_PAPER).getValue("KRX-005930")

        assertEquals(Exchange.KIS_PAPER, state.exchange)
        assertEquals(kisRow.reviewReason, state.reviewReason)
        verifySequence { repository.findByUserIdAndExchange(1L, "KIS_PAPER") }
    }

    @Test
    fun `a state carries its row's exchange, not the one asked for`() = runTest {
        // 조회가 엉뚱한 행을 돌려줘도 엔진·매니저의 거래소 검사가 행의 진짜 값을 보게 한다.
        every { repository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Flux.just(upbitRow)

        assertEquals(Exchange.UPBIT, service.loadStates(1L, Exchange.KIS_PAPER).getValue("KRW-BTC").exchange)
    }

    @Test
    fun `a row with an unknown exchange is not loaded`() = runTest {
        val unknown = kisRow.copy(exchange = "NYSE")
        every { repository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Flux.just(unknown)
        every { repository.findByUserIdAndExchangeAndTicker(1L, "KIS_PAPER", "KRX-005930") } returns Mono.just(unknown)

        assertEquals(emptyMap<String, TradingState>(), service.loadStates(1L, Exchange.KIS_PAPER))
        assertNull(service.loadState(1L, "KRX-005930", Exchange.KIS_PAPER))
    }

    @Test
    fun `upbit loadState treats another exchange's row as missing`() = runTest {
        every { repository.findByUserIdAndTicker(1L, "KRX-005930") } returns Mono.just(kisRow)

        assertNull(service.loadState(1L, "KRX-005930"))

        verifySequence { repository.findByUserIdAndTicker(1L, "KRX-005930") }
    }

    @Test
    fun `a KIS loadState reads by user, exchange and ticker`() = runTest {
        every { repository.findByUserIdAndExchangeAndTicker(1L, "KIS_PAPER", "KRX-005930") } returns Mono.just(kisRow)

        assertEquals(Exchange.KIS_PAPER, service.loadState(1L, "KRX-005930", Exchange.KIS_PAPER)?.exchange)

        verifySequence { repository.findByUserIdAndExchangeAndTicker(1L, "KIS_PAPER", "KRX-005930") }
    }

    @Test
    fun `upbit upsert refuses to save over another exchange's row`() = runTest {
        every { repository.findByUserIdAndTicker(1L, "KRX-005930") } returns Mono.just(kisRow)

        assertThrows<IllegalStateException> { service.upsert(1L, TradingState("KRX-005930")) }

        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `a KIS upsert looks up its own row and saves the exchange and review reason`() = runTest {
        every { repository.findByUserIdAndExchangeAndTicker(1L, "KIS_PAPER", "KRX-005930") } returns Mono.just(kisRow)
        val saved = slot<TradingStateEntity>()
        every { repository.save(capture(saved)) } answers { Mono.just(saved.captured) }

        service.upsert(1L, TradingState("KRX-005930", exchange = Exchange.KIS_PAPER, reviewReason = "후보 2건"))

        verifySequence {
            repository.findByUserIdAndExchangeAndTicker(1L, "KIS_PAPER", "KRX-005930")
            repository.save(any())
        }
        assertEquals(2L, saved.captured.id)
        assertEquals("KIS_PAPER", saved.captured.exchange)
        assertEquals("후보 2건", saved.captured.reviewReason)
    }
}
