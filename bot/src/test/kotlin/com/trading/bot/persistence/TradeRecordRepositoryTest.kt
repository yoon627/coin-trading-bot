package com.trading.bot.persistence

import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.persistence.entity.TradeRecordEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * `save` 는 도메인 필드를 손으로 엔티티에 옮긴다. 필드를 빠뜨려도 컴파일이 통과하고 런타임에 조용히
 * NULL 로 저장되므로(실제로 `exchangeOrderId` 가 그렇게 유실되고 있다) 매핑을 직접 단언한다.
 */
class TradeRecordRepositoryTest {

    private val r2dbc: TradeRecordR2dbcRepository = mockk()
    private val repository = TradeRecordRepository(r2dbc)

    @Test
    fun `save carries the realized pnl fields onto the entity`() = runTest {
        val entity = slot<TradeRecordEntity>()
        every { r2dbc.save(capture(entity)) } answers { Mono.just(firstArg()) }

        repository.save(
            TradeRecord(
                ticker = "KRW-BTC",
                side = TradeSide.SELL,
                price = 52000000.0,
                volume = 0.001,
                totalAmount = 52000.0,
                pnlPercent = 3.9,
                pnlAmount = 1950.0,
                strategy = "knee_reversal",
                reason = "TAKE_PROFIT",
                userId = 1L,
                fee = FeeBasis.Estimate,
            )
        )

        assertEquals(3.9, entity.captured.pnlPercent)
        assertEquals(1950.0, entity.captured.pnlAmount)
        assertEquals("knee_reversal", entity.captured.strategy)
    }
}
