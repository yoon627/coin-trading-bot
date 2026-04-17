package com.trading.research.cli

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.assertEquals
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
}
