package com.trading.bot.persistence

import com.trading.bot.ci.RepoFiles
import com.trading.bot.persistence.entity.BotStateEntity
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
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

/** 실제 Postgres 로 `bot_state` 의 신규 매수 중지 칼럼 매핑을 본다. 접속 방식은 [TradingStateRoundTripTest] 와 같다. */
@DataR2dbcTest
class BotStateEntriesPausedRoundTripTest {

    @Autowired
    private lateinit var repository: BotStateRepository

    @Autowired
    private lateinit var databaseClient: DatabaseClient

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

    @AfterEach
    fun cleanUp() = runTest {
        val owned = "SELECT id FROM users WHERE username LIKE 'it-%-$runId'"
        for (sql in listOf("DELETE FROM bot_state WHERE user_id IN ($owned)", "DELETE FROM users WHERE username LIKE 'it-%-$runId'")) {
            databaseClient.sql(sql).fetch().rowsUpdated().awaitSingle()
        }
    }

    private suspend fun insertUser(label: String): Long =
        databaseClient.sql("INSERT INTO users (username, password) VALUES (:u, 'x') RETURNING id")
            .bind("u", "it-$label-$runId")
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    // 마이그레이션 전부터 있던 행(새 칼럼을 모르는 INSERT)은 중지되지 않은 상태로 읽혀야 한다.
    @Test
    fun `a row written without the new columns reads as not paused`() = runTest {
        val userId = insertUser("legacy")
        databaseClient.sql("INSERT INTO bot_state (user_id, exchange, running, strategy, tickers) VALUES (:u, 'UPBIT', true, 'combined', 'KRW-BTC')")
            .bind("u", userId).fetch().rowsUpdated().awaitSingle()

        val row = repository.findByUserIdAndExchange(userId, "UPBIT").awaitSingle()

        assertThat(row.entriesPaused).isFalse()
        assertThat(row.entriesPausedChangedAt).isNull()
    }

    @Test
    fun `a saved pause survives a round trip and a later running-state save`() = runTest {
        val userId = insertUser("paused")
        val changedAt = Instant.parse("2026-11-07T01:02:03Z")
        val saved = repository.save(
            BotStateEntity(userId = userId, exchange = "UPBIT", running = false, entriesPaused = true, entriesPausedChangedAt = changedAt),
        ).awaitSingle()

        repository.save(saved.copy(running = true)).awaitSingle()
        val row = repository.findByUserIdAndExchange(userId, "UPBIT").awaitSingle()

        assertThat(row.running).isTrue()
        assertThat(row.entriesPaused).isTrue()
        assertThat(row.entriesPausedChangedAt).isEqualTo(changedAt)
    }

    // --- V31 → V32 경로와 되돌리기 SQL. 실행마다 만든 스키마에서 JDBC 로 돈다. ---

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

    private fun Connection.pauseColumns(schema: String): List<String> = strings(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = '$schema' AND table_name = 'bot_state' " +
            "AND column_name LIKE 'entries_paused%' ORDER BY column_name",
    )

    @Test
    fun `the down sql refuses unless V32 is applied, then drops the columns and keeps the rows`() = withScratchSchema("v32down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V32_down.sql").readText()
        migrate(schema, "31")
        inSchema(schema) { conn ->
            conn.update("INSERT INTO users (username, password) VALUES ('it-seed-$runId', 'x')")
            conn.update(
                "INSERT INTO bot_state (user_id, exchange, running, strategy, tickers) " +
                    "SELECT id, 'UPBIT', true, 'combined', 'KRW-BTC' FROM users WHERE username = 'it-seed-$runId'",
            )
        }

        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V32 가 적용돼 있지 않다")

        migrate(schema, "32")
        inSchema(schema) { conn ->
            assertThat(conn.pauseColumns(schema)).containsExactly("entries_paused", "entries_paused_changed_at")
            assertThat(conn.strings("SELECT entries_paused FROM bot_state")).containsExactly("f")
            conn.update(downSql)
            assertThat(conn.pauseColumns(schema)).isEmpty()
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '32'")).isEmpty()
            assertThat(conn.strings("SELECT running FROM bot_state")).containsExactly("t")
        }

        migrate(schema, "32")
        inSchema(schema) { conn -> assertThat(conn.pauseColumns(schema)).containsExactly("entries_paused", "entries_paused_changed_at") }
    }
}
