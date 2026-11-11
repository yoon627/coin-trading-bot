package com.trading.bot.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.ci.RepoFiles
import com.trading.bot.domain.TradingState
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
 * 실제 Postgres 로 봇 장부(V34)를 본다. 옛 버전 감지는 TIMESTAMPTZ 를 거친 두 시각이 같게 돌아오는지와, 장부 칼럼을 모르는
 * 저장이 그 칼럼을 건드리지 않는지에 달려 있어 mock 으로는 증명할 수 없다. 접속 방식은 [TradingStateRoundTripTest] 와 같다.
 */
@DataR2dbcTest
class BotBookRoundTripTest {

    @Autowired private lateinit var repository: TradingStateRepository
    @Autowired private lateinit var databaseClient: DatabaseClient

    private val service by lazy { TradingStateService(repository, jacksonObjectMapper()) }

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

    private val V34_COLUMNS = listOf(
        "book_volume", "book_avg_price", "book_written_at", "pending_buy_book_volume", "pending_buy_book_avg",
        "adopt_baseline_volume", "sell_clear_prior_volume", "sell_clear_volume",
    ).joinToString { "'$it'" }

    @AfterEach
    fun cleanUp() = runTest {
        val owned = "SELECT id FROM users WHERE username LIKE 'it-%-$runId'"
        for (sql in listOf("DELETE FROM trading_states WHERE user_id IN ($owned)", "DELETE FROM users WHERE username LIKE 'it-%-$runId'")) {
            databaseClient.sql(sql).fetch().rowsUpdated().awaitSingle()
        }
    }

    private suspend fun insertUser(label: String): Long =
        databaseClient.sql("INSERT INTO users (username, password) VALUES (:u, 'x') RETURNING id")
            .bind("u", "it-$label-$runId")
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    private fun known(volume: Double) =
        TradingState("KRW-BTC", position = volume > 0, holdVolume = volume, avgBuyPrice = 50_000_000.0, bookKnown = true)

    // 장부 칼럼을 모르는 옛 버전의 저장은 updated_at 만 바꾼다.
    private suspend fun oldVersionSaves(userId: Long) {
        databaseClient.sql("UPDATE trading_states SET updated_at = now() + interval '1 second' WHERE user_id = :u")
            .bind("u", userId).fetch().rowsUpdated().awaitSingle()
    }

    @Test
    fun `a known book round-trips through real postgres`() = runTest {
        val userId = insertUser("known")

        service.upsert(
            userId,
            known(0.01).copy(
                pendingBuyUuid = "b-1", pendingBuyBookVolume = 0.002, pendingBuyBookAvg = 4.0,
                adoptBaselineVolume = 0.003, sellClearPriorVolume = 0.004, sellClearVolume = 0.005,
            ),
        )

        val loaded = service.loadState(userId, "KRW-BTC")!!
        assertThat(loaded.storedBook?.volume).isEqualTo(0.01)
        assertThat(loaded.storedBook?.avgPrice).isEqualTo(50_000_000.0)
        assertThat(listOf(loaded.pendingBuyBookVolume, loaded.pendingBuyBookAvg, loaded.adoptBaselineVolume, loaded.sellClearPriorVolume, loaded.sellClearVolume))
            .containsExactly(0.002, 4.0, 0.003, 0.004, 0.005)
    }

    @Test
    fun `a book last saved by an older version reads as no book, even after a save before the first sync`() = runTest {
        val userId = insertUser("stale")
        service.upsert(userId, known(0.01))
        oldVersionSaves(userId)

        val loaded = service.loadState(userId, "KRW-BTC")!!
        assertThat(loaded.storedBook).isNull()
        // 새 버전이 동기화 전에 저장해도(일일 리셋·clearHalt 등) 낡은 표시를 지우지 않는다.
        service.upsert(userId, loaded)
        assertThat(service.loadState(userId, "KRW-BTC")!!.storedBook).isNull()
    }

    @Test
    fun `a state saved before its first sync keeps the stored book`() = runTest {
        val userId = insertUser("carry")
        service.upsert(userId, known(0.01))

        service.upsert(userId, service.loadState(userId, "KRW-BTC")!!)

        assertThat(service.loadState(userId, "KRW-BTC")!!.storedBook?.volume).isEqualTo(0.01)
    }

    // --- V33 → V34 경로와 되돌리기 SQL. 실행마다 만든 스키마에서 JDBC 로 돈다. ---

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

    private fun Connection.bookColumns(schema: String): List<String> = strings(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = '$schema' AND table_name = 'trading_states' " +
            "AND column_name IN ($V34_COLUMNS) ORDER BY column_name",
    )

    @Test
    fun `the down sql refuses unless V34 is applied, then drops the columns and V34 applies again`() = withScratchSchema("v34down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V34_down.sql").readText()
        migrate(schema, "33")

        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V34 가 적용돼 있지 않다")

        migrate(schema, "34")
        inSchema(schema) { conn ->
            assertThat(conn.bookColumns(schema)).hasSize(8)
            conn.update(downSql)
            assertThat(conn.bookColumns(schema)).isEmpty()
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '34'")).isEmpty()
        }

        migrate(schema, "34")
        inSchema(schema) { conn -> assertThat(conn.bookColumns(schema)).hasSize(8) }
    }
}
