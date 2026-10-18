package com.trading.bot.characterization

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.TradingStateRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.entity.TradingStateEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verifySequence
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * trading_states 조회 키를 고정한다. 전체 복원은 findByUserId, 저장과 단건 조회는 findByUserIdAndTicker 이고 거래소 축이 없다.
 */
class TradingStateServiceCharacterizationTest {

    private val repository = mockk<TradingStateRepository>()
    private val service = TradingStateService(repository, mockk<ObjectMapper>())

    private val row = TradingStateEntity(id = 7L, userId = 1L, ticker = "KRW-BTC", pendingBuyIdentifier = "ctb-1")

    @Test
    fun `loadStates reads every row of the user by findByUserId only`() = runTest {
        every { repository.findByUserId(1L) } returns Flux.just(row)

        val states = service.loadStates(1L)

        assertEquals(listOf("KRW-BTC"), states.keys.toList())
        assertEquals("ctb-1", states["KRW-BTC"]?.pendingBuyIdentifier)
        verifySequence { repository.findByUserId(1L) }
    }

    @Test
    fun `upsert looks the row up by user and ticker and saves over its id`() = runTest {
        every { repository.findByUserIdAndTicker(1L, "KRW-BTC") } returns Mono.just(row)
        val saved = slot<TradingStateEntity>()
        every { repository.save(capture(saved)) } answers { Mono.just(saved.captured) }

        service.upsert(1L, TradingState("KRW-BTC", pendingBuyUuid = "u-1"))

        verifySequence {
            repository.findByUserIdAndTicker(1L, "KRW-BTC")
            repository.save(any())
        }
        assertEquals(7L, saved.captured.id)
        assertEquals(1L, saved.captured.userId)
        assertEquals("u-1", saved.captured.pendingBuyUuid)
    }

    @Test
    fun `loadState reads one row by user and ticker`() = runTest {
        every { repository.findByUserIdAndTicker(1L, "KRW-BTC") } returns Mono.just(row)

        assertEquals("ctb-1", service.loadState(1L, "KRW-BTC")?.pendingBuyIdentifier)

        verifySequence { repository.findByUserIdAndTicker(1L, "KRW-BTC") }
    }
}
