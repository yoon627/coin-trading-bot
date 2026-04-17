package com.trading.research.report

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.OrderSide
import com.trading.research.engine.ClosedTrade
import com.trading.research.engine.EquityPoint
import com.trading.research.engine.RunResult
import com.trading.research.portfolio.Fill
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.LocalDate

class ReportEmitterTest {
    @Test
    fun `emits JSON, CSV, Markdown with expected contents`() {
        val asset = Asset(Exchange.UPBIT, "BTC/KRW")
        val result = RunResult(
            strategyName = "UnitTestStrategy",
            fills = listOf(Fill(asset, OrderSide.BUY, 1.0, 100.0, 0.1, "entry", 0L)),
            equityCurve = listOf(
                EquityPoint(LocalDate.of(2024, 1, 1), 10_000.0),
                EquityPoint(LocalDate.of(2024, 1, 2), 10_100.0),
            ),
            finalEquity = 10_100.0,
            tradesClosed = listOf(
                ClosedTrade(asset, 0, 1, 100.0, 105.0, 1.0, 5.0, "TAKE_PROFIT"),
            ),
        )

        val outDir = Files.createTempDirectory("report-test").resolve("run")
        ReportEmitter.emit(outDir, result)

        assertTrue(outDir.resolve("result.json").toFile().exists())
        assertTrue(outDir.resolve("equity-curve.csv").toFile().exists())
        assertTrue(outDir.resolve("trades.csv").toFile().exists())
        val md = outDir.resolve("report.md").toFile().readText()
        assertTrue(md.contains("UnitTestStrategy"))
        assertTrue(md.contains("Final Equity"))
    }
}
