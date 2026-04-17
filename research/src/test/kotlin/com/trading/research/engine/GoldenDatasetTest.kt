package com.trading.research.engine

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
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
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

class GoldenDatasetTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .enable(SerializationFeature.INDENT_OUTPUT)

    private class BuyOnBar3 : ResearchStrategy {
        override val name = "BuyOnBar3"
        override val warmupBars = 0
        override suspend fun onBar(ctx: ResearchContext, event: BarEvent) =
            if (event.barIndex == 3L) {
                listOf(OrderRequest(event.asset, OrderSide.BUY, SizingRule.FixedFraction(0.25)))
            } else {
                emptyList()
            }
    }

    private fun fixedBars(): List<Bar> {
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        return (0L..9L).map { i ->
            val t = t0.plusSeconds(i * SECONDS_PER_DAY)
            Bar(t, t.plusSeconds(SECONDS_PER_DAY), 100.0 + i, 100.0 + i, 100.0 + i, 100.0 + i, 1.0, 100.0 + i)
        }
    }

    @Test
    fun `fixture run matches checked-in golden JSON`() = runTest {
        val cfg = BacktestRunConfig(
            strategy = BuyOnBar3(),
            history = mapOf(asset to fixedBars()),
            initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(0.0005, 5.0),
            risk = RiskPolicy(),
            killSwitch = KillSwitch(),
        )
        val result = Engine.run(cfg)
        val actualJson = mapper.writeValueAsString(result)

        val golden = this::class.java.getResourceAsStream(GOLDEN_RESOURCE_PATH)
            ?.bufferedReader()?.readText()
            ?: run {
                // First-run convenience: write the current output as the new golden so a subsequent
                // run can diff against it. Keeps the fixture test self-bootstrapping during
                // development while still failing loudly if serialization ever drifts. Resolves
                // the path relative to the first ancestor containing `settings.gradle.kts` so it
                // works whether Gradle runs the test with cwd=repo-root or cwd=research/.
                val goldenPath = resolveGoldenFilesystemPath()
                Files.createDirectories(goldenPath.parent)
                Files.writeString(goldenPath, actualJson)
                println("Wrote new golden fixture to $goldenPath — re-run the test")
                actualJson
            }

        assertEquals(golden.trim(), actualJson.trim())
    }

    private fun resolveGoldenFilesystemPath(): java.nio.file.Path {
        val cwd = Paths.get("").toAbsolutePath()
        val researchRoot = generateSequence(cwd) { it.parent }
            .firstOrNull { Files.exists(it.resolve("research/src/test/resources")) || it.fileName?.toString() == "research" }
            ?: cwd
        val base = if (researchRoot.fileName?.toString() == "research") researchRoot
        else researchRoot.resolve("research")
        return base.resolve("src/test/resources/golden/buy-on-bar3-result.json")
    }

    companion object {
        private const val SECONDS_PER_DAY = 86_400L
        private const val GOLDEN_RESOURCE_PATH = "/golden/buy-on-bar3-result.json"
    }
}
