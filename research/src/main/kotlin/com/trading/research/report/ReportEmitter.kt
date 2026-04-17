package com.trading.research.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.research.engine.RunResult
import com.trading.research.metrics.Metrics
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a completed [RunResult] to disk as four artifacts:
 *  - `result.json`       — pretty-printed full object (fills, trades, equity curve) for audit + re-ingest.
 *  - `equity-curve.csv`  — date,equity rows for charting pipelines.
 *  - `trades.csv`        — per-trade rows for downstream PnL analysis.
 *  - `report.md`         — headline metrics table, human-readable summary.
 *
 * Kept as an `object` with no state so callers can invoke from anywhere without DI wiring;
 * any future per-run configuration (output format toggles, template choice) should graduate
 * to a class with an explicit config record rather than accumulating optional parameters here.
 */
object ReportEmitter {

    // Matches Metrics' default annualization basis (see WalkForwardRunner, SizingCalculator).
    // Kept local to avoid a cross-module constant; promote to :common if a third call site appears.
    private const val TRADING_DAYS_PER_YEAR = 252

    private val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun emit(dir: Path, result: RunResult) {
        Files.createDirectories(dir)
        writeJson(dir.resolve("result.json"), result)
        writeEquityCsv(dir.resolve("equity-curve.csv"), result)
        writeTradesCsv(dir.resolve("trades.csv"), result)
        writeMarkdown(dir.resolve("report.md"), result)
    }

    private fun writeJson(path: Path, result: RunResult) {
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result))
    }

    private fun writeEquityCsv(path: Path, result: RunResult) {
        val sb = StringBuilder("date,equity\n")
        result.equityCurve.forEach { sb.appendLine("${it.date},${it.equity}") }
        Files.writeString(path, sb.toString())
    }

    private fun writeTradesCsv(path: Path, result: RunResult) {
        val sb = StringBuilder("asset,entry_bar,exit_bar,entry_price,exit_price,qty,pnl,reason\n")
        result.tradesClosed.forEach {
            sb.appendLine(
                "${it.asset},${it.entryBarIndex},${it.exitBarIndex},${it.entryPrice}," +
                    "${it.exitPrice},${it.quantity},${it.pnl},${it.reason}",
            )
        }
        Files.writeString(path, sb.toString())
    }

    private fun writeMarkdown(path: Path, result: RunResult) {
        val equity = result.equityCurve.map { it.equity }
        val returns = Metrics.periodReturns(equity)
        val sharpe = Metrics.annualizedSharpe(returns, TRADING_DAYS_PER_YEAR)
        val sortino = Metrics.annualizedSortino(returns, TRADING_DAYS_PER_YEAR)
        val maxDrawdown = Metrics.maxDrawdown(equity)
        val tradePnls = result.tradesClosed.map { it.pnl }
        val winRate = Metrics.winRate(tradePnls)
        val profitFactor = Metrics.profitFactor(tradePnls)

        val md = buildString {
            appendLine("# Backtest Report: ${result.strategyName}")
            appendLine()
            appendLine("## Headline")
            appendLine("| Metric | Value |")
            appendLine("|---|---|")
            appendLine("| Final Equity | ${"%.2f".format(result.finalEquity)} |")
            appendLine("| Sharpe | ${"%.3f".format(sharpe)} |")
            appendLine("| Sortino | ${"%.3f".format(sortino)} |")
            appendLine("| Max Drawdown | ${"%.2f%%".format(maxDrawdown * 100)} |")
            appendLine("| Trades Closed | ${result.tradesClosed.size} |")
            appendLine("| Win Rate | ${"%.1f%%".format(winRate * 100)} |")
            appendLine("| Profit Factor | ${"%.2f".format(profitFactor)} |")
        }
        Files.writeString(path, md)
    }
}
