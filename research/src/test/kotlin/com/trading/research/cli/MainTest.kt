package com.trading.research.cli

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainTest {
    @Test
    fun `help prints usage banner`() {
        val result = BacktestCommand().test("--help")
        assertTrue(result.output.contains("--strategy"))
        assertTrue(result.output.contains("--from"))
        assertTrue(result.output.contains("--to"))
    }

    @Test
    fun `parses asset list argument`() {
        val result = BacktestCommand().test(
            "--strategy", "RsiBounce",
            "--assets", "UPBIT:BTC/KRW,KIS:AAPL",
            "--from", "2024-01-01",
            "--to", "2024-06-01",
            "--dry-run",
        )
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `help no longer advertises vol-target sizing`() {
        val result = BacktestCommand().test("--help")
        assertFalse(
            result.output.contains("vol-target"),
            "help should not advertise vol-target until realized vol is wired in; got: ${result.output}",
        )
    }

    @Test
    fun `rejects vol-target sizing at parse time with actionable message`() {
        val result = BacktestCommand().test(
            "--strategy", "RsiBounce",
            "--assets", "UPBIT:BTC/KRW",
            "--from", "2024-01-01",
            "--to", "2024-06-01",
            "--sizing", "vol-target:0.15",
        )
        assertNotEquals(0, result.statusCode, "expected non-zero exit for vol-target sizing")
        assertTrue(
            result.output.contains("vol-target", ignoreCase = true),
            "error should mention vol-target; got: ${result.output}",
        )
    }
}
