package com.trading.bot.persistence

import com.trading.bot.ci.RepoFiles
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.entity.ManualInterventionEntity
import io.mockk.every
import io.mockk.mockk
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
import reactor.core.publisher.Mono

/**
 * 실제 Postgres 의 R2DBC 트랜잭션으로 수동 개입 기록이 상태 저장과 함께 커밋되고 함께 되돌아가는지 본다.
 * 트랜잭션을 mock 하면 저장소 호출이 같은 트랜잭션에 드는지 증명할 수 없다. 접속 방식은 [TradingStateRoundTripTest] 와 같다.
 */
@DataR2dbcTest
class ManualInterventionRoundTripTest {

    @Autowired private lateinit var interventions: ManualInterventionRepository
    @Autowired private lateinit var states: TradingStateRepository
    @Autowired private lateinit var transactionManager: ReactiveTransactionManager
    @Autowired private lateinit var databaseClient: DatabaseClient

    private val mapper = productionObjectMapper()
    private val transactionalOperator by lazy { TransactionalOperator.create(transactionManager) }
    private val stateService by lazy { TradingStateService(states, mapper) }

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

    // manual_interventions 는 users FK 가 없어 직접 지우지 않으면 남는다.
    @AfterEach
    fun cleanUp() = runTest {
        val owned = "SELECT id FROM users WHERE username LIKE 'it-%-$runId'"
        for (sql in listOf(
            "DELETE FROM manual_interventions WHERE user_id IN ($owned)",
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

    @Test
    fun `a clear commits the new state and one record holding the row before it`() = runTest {
        val userId = insertUser("commit")
        stateService.upsert(userId, TradingState("KRW-BTC", pendingSellIdentifier = "ctb-s", reviewReason = "unknown order"))
        val service = ManualInterventionService(interventions, stateService, transactionalOperator, mapper)

        service.saveWithRecord(userId, TradingState("KRW-BTC"), ManualInterventionEntity.PENDING_CLEAR, mapOf("side" to "SELL"))

        assertThat(stateService.loadState(userId, "KRW-BTC")!!.pendingSellIdentifier).isNull()
        val row = interventions.findAll().collectList().awaitSingle().single { it.userId == userId }
        assertThat(row.action).isEqualTo("PENDING_CLEAR")
        val before = mapper.readTree(row.detail)["before"]
        assertThat(before["pending_sell_identifier"].asText()).isEqualTo("ctb-s")
        assertThat(before["review_reason"].asText()).isEqualTo("unknown order")
    }

    @Test
    fun `a record that cannot be inserted rolls the state change back`() = runTest {
        val userId = insertUser("rollback")
        stateService.upsert(userId, TradingState("KRW-BTC", pendingSellIdentifier = "ctb-s"))
        val failing = mockk<ManualInterventionRepository> { every { save(any()) } returns Mono.error(IllegalStateException("insert failed")) }
        val service = ManualInterventionService(failing, stateService, transactionalOperator, mapper)

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                service.saveWithRecord(userId, TradingState("KRW-BTC"), ManualInterventionEntity.PENDING_CLEAR, emptyMap())
            }
        }.hasMessageContaining("insert failed")

        assertThat(stateService.loadState(userId, "KRW-BTC")!!.pendingSellIdentifier).isEqualTo("ctb-s")
        assertThat(count("manual_interventions", userId)).isZero()
    }

    // --- V34 → V35 경로와 되돌리기 SQL. 실행마다 만든 스키마에서 JDBC 로 돈다. ---

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

    private fun Connection.interventionTable(schema: String): List<String> =
        strings("SELECT table_name FROM information_schema.tables WHERE table_schema = '$schema' AND table_name = 'manual_interventions'")

    @Test
    fun `the down sql refuses unless V35 is applied, then drops the table and V35 applies again`() = withScratchSchema("v35down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V35_down.sql").readText()
        migrate(schema, "34")

        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V35 가 적용돼 있지 않다")

        migrate(schema, "35")
        inSchema(schema) { conn ->
            assertThat(conn.interventionTable(schema)).containsExactly("manual_interventions")
            conn.update(downSql)
            assertThat(conn.interventionTable(schema)).isEmpty()
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '35'")).isEmpty()
        }

        migrate(schema, "35")
        inSchema(schema) { conn -> assertThat(conn.interventionTable(schema)).containsExactly("manual_interventions") }
    }
}
