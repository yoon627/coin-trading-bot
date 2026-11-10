package com.trading.bot.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.ci.RepoFiles
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.engine.TradeExecutionService
import com.trading.bot.notification.TradeNotificationPayload
import com.trading.common.config.TradingProperties
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
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
 * 실제 Postgres 의 R2DBC 트랜잭션으로 체결 알림 outbox 가 체결 기록과 함께 커밋되고 함께 되돌아가는지 본다.
 * 트랜잭션을 mock 하면 저장소 호출이 같은 트랜잭션에 드는지 증명할 수 없다. 접속 방식은 [TradingStateRoundTripTest] 와 같다.
 */
@DataR2dbcTest
class TradeNotificationOutboxRoundTripTest {

    @Autowired private lateinit var tradeRecords: TradeRecordR2dbcRepository
    @Autowired private lateinit var executions: TradeExecutionRepository
    @Autowired private lateinit var outbox: TradeNotificationOutboxRepository
    @Autowired private lateinit var states: TradingStateRepository
    @Autowired private lateinit var transactionManager: ReactiveTransactionManager
    @Autowired private lateinit var databaseClient: DatabaseClient

    private val transactionalOperator by lazy { TransactionalOperator.create(transactionManager) }
    private val stateService by lazy { TradingStateService(states, jacksonObjectMapper()) }
    private val service by lazy {
        TradeExecutionService(TradeRecordRepository(tradeRecords), executions, outbox, transactionalOperator, TradingProperties())
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
    }

    private val runId = UUID.randomUUID().toString().take(8)

    /** FK 가 cascade 가 아니라 자식 → 부모 순서로 지운다. */
    @AfterEach
    fun cleanUp() = runTest {
        val owned = "SELECT id FROM users WHERE username LIKE 'it-%-$runId'"
        for (sql in listOf(
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

    private fun fill(userId: Long, orderId: String?) = TradeRecord(
        ticker = "KRW-BTC", side = TradeSide.BUY, price = 50_000_000.0, volume = 0.001, totalAmount = 50_000.0,
        pnlPercent = null, pnlAmount = null, strategy = "combined", exchangeOrderId = orderId, userId = userId,
        fee = FeeBasis.Estimate, orderAmount = 50_000.0,
    )

    private suspend fun count(table: String, userId: Long): Long =
        databaseClient.sql("SELECT COUNT(*) AS n FROM $table WHERE user_id = :u").bind("u", userId)
            .map { row -> row.get("n", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()

    @Test
    fun `a committed fill leaves exactly one notification row pointing at its execution`() = runTest {
        val userId = insertUser("commit")

        assertThat(service.commitFill({ stateService.upsert(userId, TradingState("KRW-BTC")) }, fill(userId, "it-o-$runId"))).isTrue()
        // 같은 주문을 다시 확정해도 알림 행은 늘지 않는다.
        assertThat(service.commitFill({}, fill(userId, "it-o-$runId"))).isFalse()

        val row = outbox.findDue(java.time.Instant.now().plusSeconds(1), 100).collectList().awaitSingle().single { it.userId == userId }
        val executionId = databaseClient.sql("SELECT id FROM trade_executions WHERE user_id = :u").bind("u", userId)
            .map { r -> r.get("id", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
        assertThat(row.tradeExecutionId).isEqualTo(executionId)
        assertThat(TradeNotificationPayload.fromJson(row.payload).exchangeOrderId).isEqualTo("it-o-$runId")
        assertThat(count("trade_notification_outbox", userId)).isEqualTo(1)
    }

    // 체결 커밋과 같은 조합(상태 저장 → 거래 기록·알림 행)이 한 트랜잭션이라 중간에 실패하면 모두 되돌아간다.
    @Test
    fun `a failure after the notification row rolls back the state, the trade and the notification together`() = runTest {
        val userId = insertUser("rollback")

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                transactionalOperator.transactional(
                    mono {
                        stateService.upsert(userId, TradingState("KRW-BTC"))
                        service.saveAudit(fill(userId, "it-r-$runId"))
                        error("fails after the outbox insert")
                    }
                ).awaitSingle()
            }
        }.hasMessageContaining("after the outbox insert")

        for (table in listOf("trading_states", "trade_records", "trade_executions", "trade_notification_outbox")) {
            assertThat(count(table, userId)).describedAs(table).isZero()
        }
    }

    @Test
    fun `a fill without an order number still gets its notification row`() = runTest {
        val userId = insertUser("noorder")

        assertThat(service.commitFill({}, fill(userId, null))).isTrue()

        assertThat(count("trade_notification_outbox", userId)).isEqualTo(1)
    }

    // --- V32 → V33 경로와 되돌리기 SQL. 실행마다 만든 스키마에서 JDBC 로 돈다. ---

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

    private fun Connection.strings(sql: String): List<String> =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    private fun withScratchSchema(label: String, block: (String) -> Unit) {
        val schema = "it_${label}_$runId".replace('-', '_')
        try {
            block(schema)
        } finally {
            DriverManager.getConnection(jdbcUrl, user, password).use { it.update("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

    private fun Connection.outboxTable(schema: String): List<String> =
        strings("SELECT table_name FROM information_schema.tables WHERE table_schema = '$schema' AND table_name = 'trade_notification_outbox'")

    @Test
    fun `the down sql refuses unless V33 is applied, then drops the table and V33 applies again`() = withScratchSchema("v33down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V33_down.sql").readText()
        migrate(schema, "32")

        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V33 이 적용돼 있지 않다")

        migrate(schema, "33")
        inSchema(schema) { conn ->
            assertThat(conn.outboxTable(schema)).containsExactly("trade_notification_outbox")
            conn.update(downSql)
            assertThat(conn.outboxTable(schema)).isEmpty()
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '33'")).isEmpty()
        }

        migrate(schema, "33")
        inSchema(schema) { conn -> assertThat(conn.outboxTable(schema)).containsExactly("trade_notification_outbox") }
    }
}
