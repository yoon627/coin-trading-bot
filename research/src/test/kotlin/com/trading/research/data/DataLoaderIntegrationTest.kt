package com.trading.research.data

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/**
 * Real PG integration test. Opt-in via `-Dtest.docker=true` to run —
 * default CI/local runs skip it because Docker Desktop 4.66+ on macOS
 * has a socket-proxy that Testcontainers 1.21 does not fully support.
 * Enable once running on Linux CI or a Docker setup with direct socket access.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "test.docker", matches = "true")
class DataLoaderIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val pg = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("research")
            .withUsername("test")
            .withPassword("test")
    }

    @Test
    fun `loads market_candles rows as Bar list`() = runTest {
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.createStatement().execute(
                """
                CREATE TABLE market_candles (
                    id SERIAL PRIMARY KEY,
                    exchange TEXT NOT NULL,
                    market TEXT NOT NULL,
                    interval_minutes INT NOT NULL,
                    open_time TIMESTAMPTZ NOT NULL,
                    close_time TIMESTAMPTZ NOT NULL,
                    open_price DOUBLE PRECISION NOT NULL,
                    high_price DOUBLE PRECISION NOT NULL,
                    low_price DOUBLE PRECISION NOT NULL,
                    close_price DOUBLE PRECISION NOT NULL,
                    volume DOUBLE PRECISION NOT NULL,
                    quote_volume DOUBLE PRECISION NOT NULL
                );
                """.trimIndent()
            )
            val stmt = conn.prepareStatement(
                """INSERT INTO market_candles
                   (exchange, market, interval_minutes, open_time, close_time,
                    open_price, high_price, low_price, close_price, volume, quote_volume)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent()
            )
            for (d in 1..3) {
                val ot = Timestamp.from(Instant.parse("2024-01-0${d}T00:00:00Z"))
                val ct = Timestamp.from(Instant.parse("2024-01-0${d + 1}T00:00:00Z"))
                stmt.setString(1, "UPBIT")
                stmt.setString(2, "BTC/KRW")
                stmt.setInt(3, 1440)
                stmt.setTimestamp(4, ot)
                stmt.setTimestamp(5, ct)
                stmt.setDouble(6, 100.0 * d)
                stmt.setDouble(7, 110.0 * d)
                stmt.setDouble(8, 90.0 * d)
                stmt.setDouble(9, 105.0 * d)
                stmt.setDouble(10, 1.0)
                stmt.setDouble(11, 105.0 * d)
                stmt.executeUpdate()
            }
        }

        val loader = DataLoader(DataSourceConfig(pg.jdbcUrl, pg.username, pg.password))
        val result = loader.load(
            assets = listOf(Asset(Exchange.UPBIT, "BTC/KRW")),
            from = LocalDate.of(2024, 1, 1),
            to = LocalDate.of(2024, 1, 5),
            intervalMinutes = 1440,
        )

        assertEquals(1, result.size)
        val bars = result.values.first()
        assertEquals(3, bars.size)
        assertEquals(100.0, bars[0].open)
        assertEquals(315.0, bars[2].close)
    }
}
