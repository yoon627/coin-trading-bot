package com.trading.research.data

import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneOffset
import javax.sql.DataSource

class DataLoader(
    private val config: DataSourceConfig,
    private val dataSource: DataSource = config.dataSource(),
) {
    suspend fun load(
        assets: List<Asset>,
        from: LocalDate,
        to: LocalDate,
        intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    ): Map<Asset, List<Bar>> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Asset, List<Bar>>()
        val fromTimestamp = Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC))
        val toTimestamp = Timestamp.from(to.atStartOfDay().toInstant(ZoneOffset.UTC))
        dataSource.connection.use { conn ->
            for (asset in assets) {
                result[asset] = loadBars(conn, asset, intervalMinutes, fromTimestamp, toTimestamp)
            }
        }
        result
    }

    private fun loadBars(
        conn: Connection,
        asset: Asset,
        intervalMinutes: Int,
        fromTimestamp: Timestamp,
        toTimestamp: Timestamp,
    ): List<Bar> {
        val bars = mutableListOf<Bar>()
        conn.prepareStatement(SELECT_CANDLES_SQL).use { ps ->
            ps.setString(1, asset.exchange.name)
            ps.setString(2, asset.market)
            ps.setInt(3, intervalMinutes)
            ps.setTimestamp(4, fromTimestamp)
            ps.setTimestamp(5, toTimestamp)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    bars.add(
                        Bar(
                            openTime = rs.getTimestamp(1).toInstant(),
                            closeTime = rs.getTimestamp(2).toInstant(),
                            open = rs.getDouble(3),
                            high = rs.getDouble(4),
                            low = rs.getDouble(5),
                            close = rs.getDouble(6),
                            volume = rs.getDouble(7),
                            quoteVolume = rs.getDouble(8),
                        )
                    )
                }
            }
        }
        return bars
    }

    companion object {
        private const val DEFAULT_INTERVAL_MINUTES = 1440

        private val SELECT_CANDLES_SQL = """
            SELECT open_time, close_time, open_price, high_price, low_price, close_price, volume, quote_volume
            FROM market_candles
            WHERE exchange = ? AND market = ? AND interval_minutes = ?
              AND open_time >= ? AND open_time < ?
            ORDER BY open_time ASC
        """.trimIndent()
    }
}
