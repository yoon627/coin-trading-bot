package com.trading.research.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.path
import com.trading.research.domain.Asset
import com.trading.research.domain.SizingRule
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class BacktestCommand : CliktCommand(name = "research") {
    val strategy by option("--strategy", help = "Strategy name (legacy: RsiBounce, GoldenCross, ...)").required()
    val assets by option("--assets", help = "Comma-separated EXCHANGE:MARKET list").required()
    val from by option("--from", help = "Backtest start date (YYYY-MM-DD)")
        .convert { LocalDate.parse(it) }
        .required()
    val to by option("--to", help = "Backtest end date (YYYY-MM-DD, exclusive)")
        .convert { LocalDate.parse(it) }
        .required()
    val initialCash by option("--initial-cash", help = "Initial cash in KRW").double().default(DEFAULT_INITIAL_CASH)
    val feeRate by option("--fee-rate", help = "Flat fee rate (e.g., 0.0005 = 5bps)").double().default(DEFAULT_FEE_RATE)
    val slippageBps by option("--slippage-bps", help = "Slippage in basis points").double().default(DEFAULT_SLIPPAGE_BPS)
    val sizing by option(
        "--sizing",
        help = "Position sizing rule, e.g. fixed-fraction:0.1 | notional:1000000",
    ).default(DEFAULT_SIZING)
    val output by option("--output", help = "Report output directory")
        .path(canBeFile = false)
        .default(Paths.get(DEFAULT_OUTPUT_DIR))
    val dryRun by option("--dry-run", help = "Parse args and exit without running the engine").flag(default = false)

    override fun run() {
        // Validate assets + sizing BEFORE honoring --dry-run: the whole point of --dry-run is
        // to catch argument errors in CI/scripts, so unsupported or malformed inputs must be
        // rejected here too. Parse failures are wrapped as clikt UsageError so the CLI prints
        // an actionable message instead of a raw IllegalArgumentException stacktrace.
        val assetList = try {
            assets.split(",").map { Asset.parse(it.trim()) }
        } catch (e: IllegalArgumentException) {
            throw UsageError(
                e.message ?: "invalid --assets value '$assets'",
                paramName = "--assets",
            )
        }
        val sizingRule = try {
            parseSizing(sizing)
        } catch (e: IllegalArgumentException) {
            throw UsageError(
                e.message ?: "invalid --sizing value '$sizing'",
                paramName = "--sizing",
            )
        }
        if (dryRun) {
            echo("Dry run: strategy=$strategy assets=$assetList sizing=$sizingRule period=$from..$to")
            return
        }

        runBlocking {
            // v1: strategy instantiation + data loading is wired in a follow-up (v1.1).
            // The CLI scaffolds argument parsing; callers should use programmatic Engine.run()
            // with BacktestRunConfig until the legacy-strategy factory is added.
            throw NotImplementedError(
                "CLI v1 scaffolds argument parsing; strategy instantiation wired in follow-up " +
                    "(strategy=$strategy, assets=$assetList, sizing=$sizingRule).",
            )
        }
    }

    private fun parseSizing(raw: String): SizingRule {
        val parts = raw.split(":", limit = 2)
        require(parts.size == 2) { "Sizing must be KIND:ARG, got '$raw'" }
        val (kind, arg) = parts
        return when (kind) {
            "fixed-fraction" -> SizingRule.FixedFraction(arg.toDouble())
            "notional" -> SizingRule.Notional(arg.toDouble())
            "vol-target" -> throw UsageError(
                "vol-target sizing is not supported in v1: the engine currently hardcodes " +
                    "assetDailyVol=0.0 and SizingCalculator.notional throws on non-positive vol. " +
                    "Use fixed-fraction:<f> or notional:<amount> until realized-vol wiring lands.",
                paramName = "--sizing",
            )
            else -> throw UsageError("Unknown sizing: '$raw'", paramName = "--sizing")
        }
    }

    @Suppress("unused")
    private fun timestampDir(strategyName: String): Path {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN))
        return output.resolve(strategyName).resolve(ts)
    }

    companion object {
        private const val DEFAULT_INITIAL_CASH: Double = 10_000_000.0
        private const val DEFAULT_FEE_RATE: Double = 0.0005
        private const val DEFAULT_SLIPPAGE_BPS: Double = 5.0
        private const val DEFAULT_SIZING: String = "fixed-fraction:0.1"
        private const val DEFAULT_OUTPUT_DIR: String = "research-reports"
        private const val TIMESTAMP_PATTERN: String = "yyyyMMdd-HHmmss"
    }
}

fun main(args: Array<String>) = BacktestCommand().main(args)
