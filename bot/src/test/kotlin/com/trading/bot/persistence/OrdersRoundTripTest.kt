package com.trading.bot.persistence

import com.trading.bot.ci.RepoFiles
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 실제 Postgres 로 `orders` 표와 V41 의 옛 체결 옮기기를 본다. 접속 방식은 [TradingStateRoundTripTest] 와 같다.
 * 마이그레이션 시험은 실행마다 만든 스키마에서 JDBC 로 돈다 — 공유 스키마에서 돌리면 다른 시험의 행이 섞인다.
 */
@DataR2dbcTest
class OrdersRoundTripTest {

    @Autowired private lateinit var databaseClient: DatabaseClient

    companion object {
        private val host = System.getenv("TEST_DB_HOST")
        private val port = System.getenv("TEST_DB_PORT") ?: "5432"
        private val name = System.getenv("TEST_DB_NAME") ?: "trading"
        private val user = System.getenv("TEST_DB_USER") ?: "trading"
        private val password = System.getenv("TEST_DB_PASSWORD") ?: "trading"
        private val required = System.getenv("DB_TESTS_REQUIRED")?.toBoolean() ?: false

        private val available = host != null
        private val jdbcUrl = "jdbc:postgresql://$host:$port/$name"

        @JvmStatic
        @BeforeAll
        fun requireDatabase() {
            check(available || !required) {
                "DB_TESTS_REQUIRED=true 인데 TEST_DB_HOST 가 없다 — DB 통합테스트가 조용히 건너뛰어질 뻔했다."
            }
            assumeTrue(available, "TEST_DB_HOST 미설정 — DB 통합테스트 skip (scripts/run-db-tests.sh 로 실행)")
            Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }

        @JvmStatic
        @DynamicPropertySource
        fun connection(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://$host:$port/$name" }
            registry.add("spring.r2dbc.username") { user }
            registry.add("spring.r2dbc.password") { password }
            registry.add("spring.flyway.enabled") { "false" }
        }

        private const val V41 = "bot/src/main/resources/db/migration/V41__orders.sql"
    }

    private val runId = UUID.randomUUID().toString().take(8)

    @AfterEach
    fun cleanUp() = runTest {
        val owned = "SELECT id FROM users WHERE username LIKE 'it-%-$runId'"
        for (sql in listOf(
            "DELETE FROM orders WHERE user_id IN ($owned)",
            "DELETE FROM users WHERE username LIKE 'it-%-$runId'",
        )) {
            databaseClient.sql(sql).fetch().rowsUpdated().awaitSingle()
        }
    }

    // --- V41: 옛 체결 옮기기 ---

    @Test
    fun `V41 moves every trade execution into orders, takes order amount and id from its paired trade record, and marks legacy buys`() =
        withScratchSchema("v41") { schema ->
            migrate(schema, "40")
            val ids = inSchema(schema) { conn -> seedLegacyTrades(conn) }

            migrate(schema, "41")
            inSchema(schema) { conn ->
                val rows = conn.rows(
                    "SELECT trade_execution_id, trade_record_id, side, status, fill_source, exchange_order_id, filled_volume, filled_funds, " +
                        "avg_fill_price, fee, fee_basis, position_volume_after, book_avg_price, decision_price, requested_volume, " +
                        "pnl_percent, reason, strategy, client_order_id FROM orders ORDER BY trade_execution_id",
                )
                assertThat(rows.map { it["trade_execution_id"] }).containsExactlyElementsOf(ids.executions.map { it.toString() })
                assertThat(rows.map { it["status"] }.toSet()).containsExactly("FILLED")
                assertThat(rows.map { it["client_order_id"] }.toSet()).containsExactly(null)

                val snapshot = rows.single { it["trade_execution_id"] == ids.snapshotBuy.toString() }
                assertThat(snapshot).containsEntry("fill_source", "BACKFILL_SNAPSHOT")
                    .containsEntry("trade_record_id", ids.snapshotBuyRecord.toString())
                    .containsEntry("exchange_order_id", "o-snap")
                    .containsEntry("position_volume_after", "2").containsEntry("book_avg_price", "100")
                    .containsEntry("filled_volume", null).containsEntry("filled_funds", "150")
                    .containsEntry("fee", "0.075").containsEntry("fee_basis", "LEGACY")

                val manual = rows.single { it["trade_execution_id"] == ids.manualBuy.toString() }
                assertThat(manual).containsEntry("fill_source", "BACKFILL_MANUAL")
                    .containsEntry("filled_volume", "1").containsEntry("filled_funds", "90")
                    .containsEntry("position_volume_after", null).containsEntry("fee", null).containsEntry("exchange_order_id", null)

                val sold = rows.single { it["trade_execution_id"] == ids.sellWithAmount.toString() }
                assertThat(sold).containsEntry("fill_source", "BACKFILL")
                    .containsEntry("filled_volume", "2").containsEntry("requested_volume", "2")
                    .containsEntry("decision_price", "110").containsEntry("filled_funds", "218").containsEntry("avg_fill_price", "109")
                    .containsEntry("pnl_percent", "9.5").containsEntry("reason", "TAKE_PROFIT").containsEntry("strategy", "combined")

                val soldOld = rows.single { it["trade_execution_id"] == ids.sellWithoutAmount.toString() }
                assertThat(soldOld).containsEntry("filled_funds", null).containsEntry("avg_fill_price", null)

                // trade_executions 만 있는 정정 줄은 옮기고, trade_records 만 있는 줄은 옮기지 않는다.
                assertThat(rows.single { it["trade_execution_id"] == ids.correctionOnly.toString() }).containsEntry("trade_record_id", null)
                assertThat(rows.map { it["trade_record_id"] }).doesNotContain(ids.recordOnly.toString())

                // 값이 같은 두 쌍은 id 순서대로 짝짓는다.
                assertThat(ids.twinExecutions.map { te -> rows.single { it["trade_execution_id"] == te.toString() }["trade_record_id"] })
                    .containsExactlyElementsOf(ids.twinRecords.map { it.toString() })

                // 같은 옮기기를 다시 돌려도 늘지 않는다(2-2 의 따라잡기가 이 부분을 다시 쓴다).
                conn.update(backfillSql())
                assertThat(conn.strings("SELECT count(*)::text FROM orders")).containsExactly(ids.executions.size.toString())
            }
        }

    @Test
    fun `the V41 down sql refuses unless V41 is last, then drops the table and its history row`() = withScratchSchema("v41down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V41_down.sql").readText()
        migrate(schema, "40")
        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V41 이 적용돼 있지 않다")

        migrate(schema, "41")
        inSchema(schema) { conn ->
            conn.update(downSql)
            assertThat(conn.strings("SELECT to_regclass('orders')::text")).containsExactly(null)
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '41'")).isEmpty()
        }
        migrate(schema, "41")
        inSchema(schema) { conn -> assertThat(conn.strings("SELECT to_regclass('orders')::text")).containsExactly("orders") }
    }

    private class LegacyIds(
        val snapshotBuy: Long,
        val snapshotBuyRecord: Long,
        val manualBuy: Long,
        val sellWithAmount: Long,
        val sellWithoutAmount: Long,
        val correctionOnly: Long,
        val recordOnly: Long,
        val twinExecutions: List<Long>,
        val twinRecords: List<Long>,
    ) {
        val executions get() = (listOf(snapshotBuy, manualBuy, sellWithAmount, sellWithoutAmount, correctionOnly) + twinExecutions).sorted()
    }

    /** 운영에 있던 모양의 옛 기록을 심는다. trade_records 와 trade_executions 는 같은 체결을 같은 값으로 두 번 적었다. */
    private fun seedLegacyTrades(conn: Connection): LegacyIds {
        conn.update("INSERT INTO users (username, password) VALUES ('it-legacy-$runId', 'x')")
        val userId = conn.strings("SELECT id::text FROM users WHERE username = 'it-legacy-$runId'").single()!!.toLong()

        fun record(side: String, price: Double, volume: Double, total: Double, strategy: String?, orderAmount: Double?, pnl: Double? = null, reason: String? = null): Long =
            conn.strings(
                "INSERT INTO trade_records (user_id, ticker, side, price, volume, total_amount, strategy, order_amount, pnl_percent, reason) " +
                    "VALUES ($userId, 'KRW-BTC', '$side', $price, $volume, $total, ${strategy?.let { "'$it'" }}, $orderAmount, $pnl, ${reason?.let { "'$it'" }}) RETURNING id::text",
            ).single()!!.toLong()

        fun execution(side: String, price: Double, volume: Double, total: Double, strategy: String?, fee: Double, orderId: String?, pnl: Double? = null, reason: String? = null): Long =
            conn.strings(
                "INSERT INTO trade_executions (user_id, exchange, market, side, price, volume, total_amount, strategy, fee, exchange_order_id, pnl_percent, reason) " +
                    "VALUES ($userId, 'UPBIT', 'KRW-BTC', '$side', $price, $volume, $total, ${strategy?.let { "'$it'" }}, $fee, ${orderId?.let { "'$it'" }}, $pnl, ${reason?.let { "'$it'" }}) RETURNING id::text",
            ).single()!!.toLong()

        val snapRecord = record("BUY", 100.0, 2.0, 200.0, "combined", 150.0)
        val snap = execution("BUY", 100.0, 2.0, 200.0, "combined", 0.075, "o-snap")
        record("BUY", 90.0, 1.0, 90.0, "manual", null)
        val manual = execution("BUY", 90.0, 1.0, 90.0, "manual", 0.0, null)
        record("SELL", 110.0, 2.0, 220.0, "combined", 218.0, 9.5, "TAKE_PROFIT")
        val sold = execution("SELL", 110.0, 2.0, 220.0, "combined", 0.11, "o-sell", 9.5, "TAKE_PROFIT")
        record("SELL", 95.0, 1.0, 95.0, "combined", null, -5.2, "STOP_LOSS")
        val soldOld = execution("SELL", 95.0, 1.0, 95.0, "combined", 0.0, null, -5.2, "STOP_LOSS")
        val correction = execution("SELL", 120.0, 0.5, 60.0, "combined", 0.03, "o-fix")
        val recordOnly = record("BUY", 80.0, 3.0, 240.0, "combined", 240.0)
        val twinRecords = listOf(record("BUY", 70.0, 1.0, 70.0, "combined", 70.0), record("BUY", 70.0, 1.0, 70.0, "combined", 70.0))
        val twins = listOf(execution("BUY", 70.0, 1.0, 70.0, "combined", 0.035, "o-t1"), execution("BUY", 70.0, 1.0, 70.0, "combined", 0.035, "o-t2"))
        return LegacyIds(snap, snapRecord, manual, sold, soldOld, correction, recordOnly, twins, twinRecords)
    }

    /** V41 의 옛 체결 옮기기 부분. 마이그레이션 파일의 표시 사이를 그대로 쓴다. */
    private fun backfillSql(): String =
        RepoFiles.file(V41).readText().substringAfter("-- backfill:begin").substringBefore("-- backfill:end")

    private fun migrate(schema: String, target: String) {
        Flyway.configure()
            .dataSource(jdbcUrl, user, password)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .locations("classpath:db/migration")
            .target(target)
            .load()
            .migrate()
    }

    private fun <T> inSchema(schema: String, block: (Connection) -> T): T =
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            conn.createStatement().use { it.execute("SET search_path TO $schema") }
            block(conn)
        }

    private fun Connection.update(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.strings(sql: String): List<String?> =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    private fun Connection.rows(sql: String): List<Map<String, String?>> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
                buildList { while (rs.next()) add(columns.associateWith { rs.getString(it) }) }
            }
        }

    private fun withScratchSchema(label: String, block: (String) -> Unit) {
        val schema = "it_${label}_$runId".replace('-', '_')
        try {
            block(schema)
        } finally {
            DriverManager.getConnection(jdbcUrl, user, password).use { it.update("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }
}
