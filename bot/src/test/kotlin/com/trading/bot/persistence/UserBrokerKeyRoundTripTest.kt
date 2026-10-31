package com.trading.bot.persistence

import com.trading.bot.ci.RepoFiles
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * V31 증권사 키 테이블을 실제 Postgres 로 본다. 접속·skip 규약은 [TradingStateRoundTripTest] 와 같다.
 * V30 에서 V31 로 올리는 경로와 되돌리기 SQL 은 실행마다 새로 만든 스키마에서 돌려 공용 스키마를 건드리지 않는다.
 * 키 값은 모두 가짜다.
 */
@DataR2dbcTest
class UserBrokerKeyRoundTripTest {

    @Autowired
    private lateinit var keys: UserBrokerKeyRepository

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
        private val jdbcUrl get() = "jdbc:postgresql://$host:$port/$name"

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
        for (sql in listOf(
            "DELETE FROM user_broker_keys WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'it-%-$runId')",
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

    private suspend fun rowCount(userId: Long): Long =
        databaseClient.sql("SELECT count(*) AS n FROM user_broker_keys WHERE user_id = :u")
            .bind("u", userId)
            .map { row -> row.get("n", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    @Test
    fun `upsert inserts once and then updates the same row`() = runTest {
        val userId = insertUser("upsert")

        assertThat(keys.upsert(userId, "KIS_PAPER", "enc:fake-key-1", "enc:fake-app-1", "enc:fake-acct-1").awaitSingle()).isEqualTo(1)
        val first = keys.findByUserIdAndExchange(userId, "KIS_PAPER").awaitSingle()
        assertThat(first.appKey).isEqualTo("enc:fake-key-1")
        assertThat(first.appSecret).isEqualTo("enc:fake-app-1")
        assertThat(first.accountNo).isEqualTo("enc:fake-acct-1")

        val old = Instant.parse("2000-01-01T00:00:00Z")
        databaseClient.sql("UPDATE user_broker_keys SET created_at = :t, updated_at = :t WHERE id = :id")
            .bind("t", old).bind("id", first.id!!)
            .fetch().rowsUpdated().awaitSingle()

        assertThat(keys.upsert(userId, "KIS_PAPER", "enc:fake-key-2", "enc:fake-app-2", "enc:fake-acct-2").awaitSingle()).isEqualTo(1)

        val second = keys.findByUserIdAndExchange(userId, "KIS_PAPER").awaitSingle()
        assertThat(rowCount(userId)).isEqualTo(1)
        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.appKey).isEqualTo("enc:fake-key-2")
        assertThat(second.appSecret).isEqualTo("enc:fake-app-2")
        assertThat(second.accountNo).isEqualTo("enc:fake-acct-2")
        assertThat(second.createdAt).isEqualTo(old)
        assertThat(second.updatedAt).isAfter(old)
    }

    @Test
    fun `the unique key allows one row per exchange and findByUserId returns both`() = runTest {
        val userId = insertUser("unique")
        val insert = "INSERT INTO user_broker_keys (user_id, exchange, app_key, app_secret, account_no) " +
            "VALUES (:u, 'KIS', 'enc:a', 'enc:b', 'enc:c')"

        keys.upsert(userId, "KIS_PAPER", "enc:a", "enc:b", "enc:c").awaitSingle()
        databaseClient.sql(insert).bind("u", userId).fetch().rowsUpdated().awaitSingle()

        assertThatThrownBy { runBlocking { databaseClient.sql(insert).bind("u", userId).fetch().rowsUpdated().awaitSingle() } }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(keys.findByUserId(userId).collectList().awaitSingle().map { it.exchange })
            .containsExactlyInAnyOrder("KIS_PAPER", "KIS")
    }

    @Test
    fun `the exchange check rejects UPBIT`() = runTest {
        val userId = insertUser("check")

        assertThatThrownBy { runBlocking { keys.upsert(userId, "UPBIT", "enc:a", "enc:b", "enc:c").awaitSingle() } }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(rowCount(userId)).isZero()
    }

    @Test
    fun `the user foreign key rejects an unknown user`() = runTest {
        val unknown = insertUser("fk").also { id ->
            databaseClient.sql("DELETE FROM users WHERE id = :id").bind("id", id).fetch().rowsUpdated().awaitSingle()
        }

        assertThatThrownBy { runBlocking { keys.upsert(unknown, "KIS_PAPER", "enc:a", "enc:b", "enc:c").awaitSingle() } }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `delete removes only the given exchange`() = runTest {
        val userId = insertUser("delete")
        keys.upsert(userId, "KIS_PAPER", "enc:a", "enc:b", "enc:c").awaitSingle()
        keys.upsert(userId, "KIS", "enc:d", "enc:e", "enc:f").awaitSingle()

        assertThat(keys.deleteByUserIdAndExchange(userId, "KIS_PAPER").awaitSingle()).isEqualTo(1)
        assertThat(keys.deleteByUserIdAndExchange(userId, "KIS_PAPER").awaitSingle()).isZero()

        assertThat(keys.findByUserIdAndExchange(userId, "KIS_PAPER").awaitSingleOrNull()).isNull()
        assertThat(keys.findByUserIdAndExchange(userId, "KIS").awaitSingle().appKey).isEqualTo("enc:d")
    }

    @Test
    fun `upsert and delete touch only the given user's row`() = runTest {
        val a = insertUser("scope-a")
        val b = insertUser("scope-b")
        keys.upsert(a, "KIS_PAPER", "enc:a-1", "enc:a-2", "enc:a-3").awaitSingle()
        keys.upsert(b, "KIS_PAPER", "enc:b-1", "enc:b-2", "enc:b-3").awaitSingle()
        val before = keys.findByUserIdAndExchange(b, "KIS_PAPER").awaitSingle()

        keys.upsert(a, "KIS_PAPER", "enc:a-4", "enc:a-5", "enc:a-6").awaitSingle()
        assertThat(keys.deleteByUserIdAndExchange(a, "KIS_PAPER").awaitSingle()).isEqualTo(1)

        val after = keys.findByUserIdAndExchange(b, "KIS_PAPER").awaitSingle()
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.appKey).isEqualTo("enc:b-1")
        assertThat(after.appSecret).isEqualTo("enc:b-2")
        assertThat(after.accountNo).isEqualTo("enc:b-3")
        assertThat(after.updatedAt).isEqualTo(before.updatedAt)
        assertThat(rowCount(a)).isZero()
    }

    // --- V30 → V31 경로와 되돌리기 SQL. 실행마다 만든 스키마에서 JDBC 로 돈다. ---

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

    private fun Connection.tables(schema: String): List<String> =
        strings("SELECT table_name FROM information_schema.tables WHERE table_schema = '$schema' AND table_name = 'user_broker_keys'")

    @Test
    fun `the down sql refuses unless V31 is the last migration, then drops the table and V31 applies again`() = withScratchSchema("v31down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V31_down.sql").readText()
        migrate(schema, "30")
        inSchema(schema) { it.update("INSERT INTO users (username, password) VALUES ('it-seed-$runId', 'x')") }

        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V31 이 적용돼 있지 않다")

        migrate(schema, "31")
        inSchema(schema) { conn ->
            assertThat(conn.tables(schema)).containsExactly("user_broker_keys")
            conn.update(
                "INSERT INTO user_broker_keys (user_id, exchange, app_key, app_secret, account_no) " +
                    "SELECT id, 'KIS_PAPER', 'enc:a', 'enc:b', 'enc:c' FROM users WHERE username = 'it-seed-$runId'",
            )
            conn.update(
                "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, installed_by, execution_time, success) " +
                    "SELECT max(installed_rank) + 1, '32', 'later', 'SQL', 'V32__later.sql', current_user, 0, true FROM flyway_schema_history",
            )
        }

        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V31 이후")
        inSchema(schema) { conn -> assertThat(conn.tables(schema)).containsExactly("user_broker_keys") }

        inSchema(schema) { conn ->
            conn.update("DELETE FROM flyway_schema_history WHERE version = '32'")
            conn.update(downSql)
            assertThat(conn.tables(schema)).isEmpty()
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '31'")).isEmpty()
            assertThat(conn.strings("SELECT username FROM users WHERE username = 'it-seed-$runId'")).hasSize(1)
        }

        migrate(schema, "31")

        inSchema(schema) { conn ->
            assertThat(conn.tables(schema)).containsExactly("user_broker_keys")
            assertThat(conn.strings("SELECT count(*) FROM user_broker_keys")).containsExactly("0")
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '31'")).containsExactly("31")
        }
    }
}
