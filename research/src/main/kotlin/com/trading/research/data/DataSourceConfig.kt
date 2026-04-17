package com.trading.research.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

data class DataSourceConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
) {
    fun dataSource(): DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = this@DataSourceConfig.jdbcUrl
            username = this@DataSourceConfig.username
            password = this@DataSourceConfig.password
            maximumPoolSize = MAX_POOL_SIZE
        }
    )

    companion object {
        private const val MAX_POOL_SIZE = 4
    }
}
