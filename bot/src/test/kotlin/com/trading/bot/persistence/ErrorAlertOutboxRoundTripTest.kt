package com.trading.bot.persistence

import com.trading.bot.ci.RepoFiles
import com.trading.bot.persistence.entity.ErrorAlertOutboxEntity
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
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

/** 실제 Postgres 로 ERROR 알림 outbox 의 조회 순서·정리 쿼리와 V36_down 을 본다. 접속 방식은 [TradingStateRoundTripTest] 와 같다. */
@DataR2dbcTest
class ErrorAlertOutboxRoundTripTest {

    @Autowired private lateinit var outbox: ErrorAlertOutboxRepository
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
    }

    private val runId = UUID.randomUUID().toString().take(8)
    private val marker = "it-$runId"
    private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

    // 표가 사용자에 매이지 않아 이 실행이 넣은 행을 payload 표지로 지운다.
    @AfterEach
    fun cleanUp() = runTest {
        databaseClient.sql("DELETE FROM error_alert_outbox WHERE payload LIKE :m").bind("m", "%$marker%").fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun insert(status: String, createdAt: Instant, nextAttemptAt: Instant = createdAt): ErrorAlertOutboxEntity =
        outbox.save(ErrorAlertOutboxEntity(payload = """{"m":"$marker"}""", status = status, nextAttemptAt = nextAttemptAt, createdAt = createdAt)).awaitSingle()

    @Test
    fun `due pending rows come back in id order and finished rows older than the cutoff are deleted`() = runTest {
        val first = insert("PENDING", now.minusSeconds(10))
        val later = insert("PENDING", now.minusSeconds(20))
        insert("PENDING", now, nextAttemptAt = now.plusSeconds(60))
        val oldSent = insert("SENT", now.minus(Duration.ofDays(8)))
        val oldPending = insert("PENDING", now.minus(Duration.ofDays(8)), nextAttemptAt = now.plusSeconds(60))

        val due = outbox.findDue(now, 100).collectList().awaitSingle().filter { marker in it.payload }
        assertThat(due.map { it.id }).containsExactly(first.id, later.id)

        outbox.deleteFinishedBefore(now.minus(Duration.ofDays(7))).awaitSingle()
        val left = outbox.findAll().collectList().awaitSingle().filter { marker in it.payload }.map { it.id }
        assertThat(left).doesNotContain(oldSent.id).contains(oldPending.id)
    }

    // --- V35 → V36 경로와 되돌리기 SQL. 실행마다 만든 스키마에서 JDBC 로 돈다. ---

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
        strings("SELECT table_name FROM information_schema.tables WHERE table_schema = '$schema' AND table_name = 'error_alert_outbox'")

    @Test
    fun `the down sql refuses unless V36 is applied, then drops the table and V36 applies again`() = withScratchSchema("v36down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V36_down.sql").readText()
        migrate(schema, "35")

        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V36 이 적용돼 있지 않다")

        migrate(schema, "36")
        inSchema(schema) { conn ->
            assertThat(conn.outboxTable(schema)).containsExactly("error_alert_outbox")
            conn.update(downSql)
            assertThat(conn.outboxTable(schema)).isEmpty()
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '36'")).isEmpty()
        }

        migrate(schema, "36")
        inSchema(schema) { conn -> assertThat(conn.outboxTable(schema)).containsExactly("error_alert_outbox") }
    }
}
