package com.trading.research.engine

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import com.trading.research.domain.OrderRequest
import com.trading.research.domain.OrderSide
import com.trading.research.domain.SizingRule
import com.trading.research.execution.FlatFeeSlippageModel
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import com.trading.research.strategy.BarEvent
import com.trading.research.strategy.ResearchContext
import com.trading.research.strategy.ResearchStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant

class DeterminismTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")
    private val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    private class Trivial : ResearchStrategy {
        override val name = "Trivial"
        override val warmupBars = 0
        override suspend fun onBar(ctx: ResearchContext, event: BarEvent) =
            if (event.barIndex == 3L) {
                listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.FixedFraction(0.25)))
            } else {
                emptyList()
            }
    }

    private fun bars(): List<Bar> {
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        return (0L..9L).map { i ->
            val t = t0.plusSeconds(i * SECONDS_PER_DAY)
            Bar(t, t.plusSeconds(SECONDS_PER_DAY), 100.0 + i, 100.0 + i, 100.0 + i, 100.0 + i, 1.0, 100.0 + i)
        }
    }

    @Test
    fun `two runs with same input produce byte-equal JSON`() = runTest {
        val cfg = BacktestRunConfig(
            strategy = Trivial(),
            history = mapOf(asset to bars()),
            initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(0.0005, 5.0),
            risk = RiskPolicy(),
            killSwitch = KillSwitch(),
        )
        val first = Engine.run(cfg)
        val second = Engine.run(cfg)
        val md = MessageDigest.getInstance("SHA-256")
        val hashFirst = md.digest(mapper.writeValueAsBytes(first))
        val hashSecond = md.digest(mapper.writeValueAsBytes(second))
        assertEquals(hashFirst.toHex(), hashSecond.toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val SECONDS_PER_DAY = 86_400L
    }
}
