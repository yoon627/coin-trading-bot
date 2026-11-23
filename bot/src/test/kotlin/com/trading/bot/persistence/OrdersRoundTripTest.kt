package com.trading.bot.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.ci.RepoFiles
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.FillSource
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.engine.OrderLedger
import com.trading.bot.engine.TradeExecutionService
import com.trading.common.config.TradingProperties
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
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator

/**
 * 실제 Postgres 로 `orders` 표와 V41 의 옛 체결 옮기기를 본다. 접속 방식은 [TradingStateRoundTripTest] 와 같다.
 * 마이그레이션 시험은 실행마다 만든 스키마에서 JDBC 로 돈다 — 공유 스키마에서 돌리면 다른 시험의 행이 섞인다.
 */
@DataR2dbcTest
class OrdersRoundTripTest {

    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var tradeRecords: TradeRecordR2dbcRepository
    @Autowired private lateinit var executions: TradeExecutionRepository
    @Autowired private lateinit var outbox: TradeNotificationOutboxRepository
    @Autowired private lateinit var states: TradingStateRepository
    @Autowired private lateinit var orderRows: OrderRowRepository
    @Autowired private lateinit var transactionManager: ReactiveTransactionManager

    private val stateService by lazy { TradingStateService(states, jacksonObjectMapper()) }
    private val service by lazy {
        TradeExecutionService(
            TradeRecordRepository(tradeRecords), executions, outbox, OrderLedger(orderRows),
            TransactionalOperator.create(transactionManager), TradingProperties(),
        )
    }

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
            "DELETE FROM trade_notification_outbox WHERE user_id IN ($owned)",
            "DELETE FROM trade_executions WHERE user_id IN ($owned)",
            "DELETE FROM trade_records WHERE user_id IN ($owned)",
            "DELETE FROM trading_states WHERE user_id IN ($owned)",
            "DELETE FROM users WHERE username LIKE 'it-%-$runId'",
        )) {
            databaseClient.sql(sql).fetch().rowsUpdated().awaitSingle()
        }
    }

    private suspend fun insertUser(label: String): Long =
        databaseClient.sql("INSERT INTO users (username, password) VALUES (:u, 'x') RETURNING id")
            .bind("u", "it-$label-$runId")
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    private suspend fun count(table: String, userId: Long): Long =
        databaseClient.sql("SELECT COUNT(*) AS n FROM $table WHERE user_id = :u").bind("u", userId)
            .map { row -> row.get("n", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()

    private suspend fun orderRow(userId: Long): Map<String, Any?> =
        databaseClient.sql("SELECT * FROM orders WHERE user_id = :u").bind("u", userId).fetch().one().awaitSingle()

    private fun sellFill(userId: Long, market: String = "KRW-BTC") = TradeRecord(
        ticker = market, side = TradeSide.SELL, price = 50_000_000.0, volume = 0.002, totalAmount = 100_000.0,
        pnlPercent = 1.5, pnlAmount = 1_500.0, strategy = "combined", reason = "TAKE_PROFIT",
        exchangeOrderId = "s-$runId", userId = userId, fee = FeeBasis.Measured(50.0), orderAmount = 99_950.0,
        executedVwap = 49_975_000.0, orderVolume = 0.002, positionVolumeAfter = 0.0, bookAvgPrice = 49_000_000.0,
        clientOrderId = "ctb-$runId",
    )

    // --- 체결 기록 트랜잭션 안의 orders 쓰기 ---

    @Test
    fun `a committed fill leaves one FILLED orders row with this order's values, linked to its execution and record`() = runTest {
        val userId = insertUser("fill")

        assertThat(service.commitFill({ stateService.upsert(userId, TradingState("KRW-BTC")) }, sellFill(userId))).isTrue()

        val row = orderRow(userId)
        val executionId = databaseClient.sql("SELECT id FROM trade_executions WHERE user_id = :u").bind("u", userId)
            .map { r -> r.get("id", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
        val recordId = databaseClient.sql("SELECT id FROM trade_records WHERE user_id = :u").bind("u", userId)
            .map { r -> r.get("id", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
        assertThat(row["status"]).isEqualTo("FILLED")
        assertThat(row["trade_execution_id"]).isEqualTo(executionId)
        assertThat(row["trade_record_id"]).isEqualTo(recordId)
        assertThat(row).containsEntry("exchange", "UPBIT").containsEntry("market", "KRW-BTC").containsEntry("side", "SELL")
            .containsEntry("exchange_order_id", "s-$runId").containsEntry("client_order_id", "ctb-$runId")
            .containsEntry("filled_volume", 0.002).containsEntry("filled_funds", 99_950.0).containsEntry("avg_fill_price", 49_975_000.0)
            .containsEntry("fee", 50.0).containsEntry("fee_basis", "MEASURED").containsEntry("fill_source", "ORDER")
            .containsEntry("decision_price", 50_000_000.0).containsEntry("requested_volume", null)
            .containsEntry("position_volume_after", 0.0).containsEntry("book_avg_price", 49_000_000.0)
            .containsEntry("pnl_percent", 1.5).containsEntry("pnl_amount", 1_500.0)
            .containsEntry("reason", "TAKE_PROFIT").containsEntry("strategy", "combined")
        assertThat(row["completed_at"]).isNotNull()
    }

    @Test
    fun `a fill whose state save conflicts leaves no orders row`() = runTest {
        val userId = insertUser("conflict")

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                service.commitFill({ throw TradingStateConflictException("KRW-BTC", "test") }, sellFill(userId))
            }
        }.isInstanceOf(TradingStateConflictException::class.java)

        assertThat(count("orders", userId)).isZero()
        assertThat(count("trade_executions", userId)).isZero()
    }

    // orders 쓰기의 SQL 오류는 잡지 않는다 — 잡아도 Postgres 가 트랜잭션을 중단해 뒤의 알림 행이 실패한다. 체결 전체가 되돌아가 pending 이 남는다.
    @Test
    fun `an SQL error in the orders write rolls back the whole fill`() = runTest {
        val userId = insertUser("sqlerror")
        val market = "KRW-FAIL$runId"
        databaseClient.sql(
            "CREATE FUNCTION it_fail_orders_$runId() RETURNS trigger AS \$\$ BEGIN " +
                "IF NEW.market = '$market' THEN RAISE EXCEPTION 'it orders failure'; END IF; RETURN NEW; END \$\$ LANGUAGE plpgsql",
        ).fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("CREATE TRIGGER it_fail_orders_$runId BEFORE INSERT ON orders FOR EACH ROW EXECUTE FUNCTION it_fail_orders_$runId()")
            .fetch().rowsUpdated().awaitSingle()
        try {
            assertThatThrownBy {
                kotlinx.coroutines.runBlocking {
                    service.commitFill({ stateService.upsert(userId, TradingState(market)) }, sellFill(userId, market))
                }
            }.hasStackTraceContaining("it orders failure")
        } finally {
            databaseClient.sql("DROP TRIGGER it_fail_orders_$runId ON orders").fetch().rowsUpdated().awaitSingle()
            databaseClient.sql("DROP FUNCTION it_fail_orders_$runId()").fetch().rowsUpdated().awaitSingle()
        }

        for (table in listOf("orders", "trade_executions", "trade_records", "trade_notification_outbox", "trading_states")) {
            assertThat(count(table, userId)).describedAs(table).isZero()
        }
    }

    // trade_executions 의 중복 확인을 지난 체결인데 같은 주문 키의 줄이 이미 있으면 넣지 않고 ERROR 만 남긴다 — 체결 기록은 막지 않는다.
    @Test
    fun `a fill whose order key already has an orders row commits without a second row`() = runTest {
        val userId = insertUser("dupkey")
        databaseClient.sql(
            "INSERT INTO orders (user_id, exchange, market, side, exchange_order_id, status) VALUES (:u, 'UPBIT', 'KRW-BTC', 'SELL', :o, 'OPEN')",
        ).bind("u", userId).bind("o", "s-$runId").fetch().rowsUpdated().awaitSingle()

        assertThat(service.commitFill({}, sellFill(userId))).isTrue()

        assertThat(count("orders", userId)).isEqualTo(1)
        assertThat(orderRow(userId)["status"]).isEqualTo("OPEN")
        assertThat(count("trade_executions", userId)).isEqualTo(1)
        assertThat(count("trade_notification_outbox", userId)).isEqualTo(1)
    }

    // 코드의 enum 이름이 V41 의 CHECK 목록에 없으면 그 값의 체결마다 기록 트랜잭션이 실패해 pending 이 남는다. 모든 조합을 실제로 넣어 본다.
    @Test
    fun `every fill source and fee kind the code writes passes the orders CHECK constraints`() = runTest {
        val userId = insertUser("enums")
        var n = 0L
        for (source in FillSource.entries) {
            for (kind in OrderLedger.FeeKind.entries) {
                n++
                val id = orderRows.insertFilled(
                    userId = userId, exchange = "UPBIT", market = "KRW-BTC", side = TradeSide.entries[(n % 2).toInt()].name,
                    clientOrderId = null, exchangeOrderId = "e-$runId-$n", decisionPrice = null, filledVolume = null, filledFunds = null,
                    avgFillPrice = null, fee = null, feeBasis = kind.name, positionVolumeAfter = null, bookAvgPrice = null,
                    pnlPercent = null, pnlAmount = null, reason = null, strategy = null, fillSource = source.name,
                    tradeExecutionId = -n, tradeRecordId = null,
                ).awaitSingle()
                assertThat(id).isNotNull()
            }
        }
        assertThat(count("orders", userId)).isEqualTo(n)
    }

    @Test
    fun `a fill confirmed from the balance leaves a row with unknown fee and volume marked as balance`() = runTest {
        val userId = insertUser("balance")
        val record = sellFill(userId).copy(
            fee = FeeBasis.Unrecorded, orderAmount = null, executedVwap = null, orderVolume = null, clientOrderId = null,
            fillSource = FillSource.BALANCE,
        )

        assertThat(service.commitFill({}, record)).isTrue()

        assertThat(orderRow(userId)).containsEntry("fee", null).containsEntry("fee_basis", "UNRECORDED")
            .containsEntry("fill_source", "BALANCE").containsEntry("filled_volume", null).containsEntry("filled_funds", null)
            .containsEntry("avg_fill_price", null).containsEntry("client_order_id", null)
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
                    .containsEntry("filled_volume", "2").containsEntry("requested_volume", null)
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
