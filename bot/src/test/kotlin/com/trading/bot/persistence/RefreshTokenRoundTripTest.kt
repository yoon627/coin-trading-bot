package com.trading.bot.persistence

import com.trading.bot.auth.RefreshTokenService
import com.trading.bot.auth.RefreshTokenService.Refresh
import com.trading.bot.ci.RepoFiles
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
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
 * 실제 Postgres 로 리프레시 토큰의 회전·재사용 감지·만료를 본다. 거절해야 할 것이 통과하면 훔친 토큰으로 계속 들어올 수 있어
 * 거절 경우를 먼저 둔다. 회전이 원자적인지는 같은 토큰 동시 갱신으로 본다(mock 으로는 볼 수 없다).
 */
@DataR2dbcTest
class RefreshTokenRoundTripTest {

    @Autowired private lateinit var repository: RefreshTokenRepository
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var transactionManager: ReactiveTransactionManager

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val clock = MutableClock(Instant.parse("2026-11-24T00:00:00Z"))
    private val service by lazy { RefreshTokenService(repository, TransactionalOperator.create(transactionManager), clock) }

    companion object {
        private val host = System.getenv("TEST_DB_HOST")
        private val port = System.getenv("TEST_DB_PORT") ?: "5432"
        private val name = System.getenv("TEST_DB_NAME") ?: "trading"
        private val user = System.getenv("TEST_DB_USER") ?: "trading"
        private val password = System.getenv("TEST_DB_PASSWORD") ?: "trading"
        private val required = System.getenv("DB_TESTS_REQUIRED")?.toBoolean() ?: false
        private val available = host != null

        @JvmStatic
        @BeforeAll
        fun requireDatabase() {
            check(available || !required) {
                "DB_TESTS_REQUIRED=true 인데 TEST_DB_HOST 가 없다 — DB 통합테스트가 조용히 건너뛰어질 뻔했다."
            }
            assumeTrue(available, "TEST_DB_HOST 미설정 — DB 통합테스트 skip (scripts/run-db-tests.sh 로 실행)")
            Flyway.configure()
                .dataSource("jdbc:postgresql://$host:$port/$name", user, password)
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

    // refresh_tokens 는 users 에 ON DELETE CASCADE 라 사용자만 지우면 된다.
    @AfterEach
    fun cleanUp(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM users WHERE username LIKE 'it-%-$runId'").fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun insertUser(label: String): Long =
        databaseClient.sql("INSERT INTO users (username, password) VALUES (:u, 'x') RETURNING id")
            .bind("u", "it-$label-$runId")
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    private suspend fun rows(userId: Long): List<Map<String, Any>> =
        databaseClient.sql("SELECT token_hash, replaced_at, revoked_at FROM refresh_tokens WHERE user_id = :u ORDER BY id")
            .bind("u", userId).fetch().all().collectList().awaitSingle()

    // 사용자를 읽는 쪽은 id 를 그대로 돌려준다(사용자 표 조회는 컨트롤러 시험이 본다).
    private suspend fun refresh(token: String): Refresh<Long> = service.refresh(token) { it }

    private fun advance(duration: Duration) {
        clock.now = clock.now.plus(duration)
    }

    @Test
    fun `an unknown token is rejected`(): Unit = runBlocking {
        assertThat(refresh("not-a-token")).isEqualTo(Refresh.Rejected)
    }

    @Test
    fun `only the hash is stored and the token rotates into a new one in the same family`(): Unit = runBlocking {
        val userId = insertUser("rotate")
        val issued = service.issue(userId, remember = true)

        assertThat(rows(userId).single()["token_hash"]).isEqualTo(RefreshTokenService.hash(issued.token)).isNotEqualTo(issued.token)

        val rotated = refresh(issued.token) as Refresh.Rotated
        assertThat(rotated.user).isEqualTo(userId)
        assertThat(rotated.token).isNotEqualTo(issued.token)
        assertThat(refresh(rotated.token)).isInstanceOf(Refresh.Rotated::class.java)
    }

    // 다른 탭이 방금 바꿨다면 끊지 않는다. 유예를 넘겨 옛 토큰이 오면 탈취로 보고 계열 전체(새 토큰 포함)를 끊는다.
    @Test
    fun `a replaced token within the grace is only refused, after it the whole family is revoked`(): Unit = runBlocking {
        val userId = insertUser("reuse")
        val old = service.issue(userId, remember = true).token
        val next = (refresh(old) as Refresh.Rotated<Long>).token

        advance(Duration.ofSeconds(10))
        assertThat(refresh(old)).isEqualTo(Refresh.Concurrent)

        advance(Duration.ofSeconds(1))
        assertThat(refresh(old)).isEqualTo(Refresh.Rejected)
        assertThat(refresh(next)).isEqualTo(Refresh.Rejected)
        assertThat(rows(userId)).allSatisfy { assertThat(it["revoked_at"]).isNotNull() }
    }

    @Test
    fun `two refreshes with the same token at once rotate it exactly once`(): Unit = runBlocking {
        val userId = insertUser("race")
        val token = service.issue(userId, remember = true).token

        val results = (1..2).map { async(Dispatchers.IO) { refresh(token) } }.awaitAll()

        assertThat(results.filterIsInstance<Refresh.Rotated<Long>>()).hasSize(1)
        assertThat(results).contains(Refresh.Concurrent)
        assertThat(rows(userId)).hasSize(2)
    }

    // 유지 안 함은 첫 로그인부터 24시간 — 갱신해도 끝이 밀리지 않는다. 유지는 마지막 사용부터 30일씩 밀린다.
    @Test
    fun `a session login ends 24 hours after login however often it refreshes, a remembered one slides 30 days`(): Unit = runBlocking {
        val session = insertUser("session")
        var token = service.issue(session, remember = false).token
        repeat(3) {
            advance(Duration.ofHours(7))
            token = (refresh(token) as Refresh.Rotated<Long>).token
        }
        advance(Duration.ofHours(3) + Duration.ofSeconds(1))
        assertThat(refresh(token)).isEqualTo(Refresh.Rejected)

        val remembered = insertUser("remember")
        var kept = service.issue(remembered, remember = true).token
        repeat(3) {
            advance(Duration.ofDays(29))
            kept = (refresh(kept) as Refresh.Rotated<Long>).token
        }
        advance(Duration.ofDays(30) + Duration.ofSeconds(1))
        assertThat(refresh(kept)).isEqualTo(Refresh.Rejected)
    }

    // 바꾸기·새 토큰 넣기·사용자 읽기는 한 트랜잭션 — 사용자를 못 읽거나 읽다 실패하면 옛 토큰이 그대로 남는다.
    @Test
    fun `a failed user lookup rolls the rotation back and the old token still works`(): Unit = runBlocking {
        val userId = insertUser("rollback")
        val token = service.issue(userId, remember = true).token

        assertThat(service.refresh(token) { null }).isEqualTo(Refresh.Rejected)
        assertThatThrownBy { runBlocking { service.refresh<Long>(token) { throw IllegalStateException("db blip") } } }.hasMessageContaining("db blip")

        assertThat(refresh(token)).isInstanceOf(Refresh.Rotated::class.java)
        assertThat(rows(userId)).hasSize(2)
    }

    // 로그아웃이 진행 중인 갱신과 겹치면 한 문장으로는 갱신이 막 넣은 새 토큰을 못 본다(READ COMMITTED). 다른 연결이 바꾸기를
    // 연 채로 두고 끊기를 시작해 잠금에서 기다리게 한 뒤 바꾸기를 커밋한다 — 새 토큰도 끊겨야 한다.
    @Test
    fun `a logout that waits on an in-flight rotation also revokes the token that rotation inserted`(): Unit = runBlocking {
        val userId = insertUser("logoutrace")
        val old = service.issue(userId, remember = true).token
        val newHash = RefreshTokenService.hash("rotated-$runId")

        DriverManager.getConnection(jdbcUrl, user, password).use { rotation ->
            rotation.autoCommit = false
            rotation.createStatement().use {
                it.execute("UPDATE refresh_tokens SET replaced_at = now() WHERE token_hash = '${RefreshTokenService.hash(old)}'")
                it.execute(
                    "INSERT INTO refresh_tokens (user_id, family_id, token_hash, remember, expires_at) " +
                        "SELECT user_id, family_id, '$newHash', remember, now() + interval '30 days' FROM refresh_tokens WHERE token_hash = '${RefreshTokenService.hash(old)}'",
                )
            }
            val logout = async(Dispatchers.IO) { service.revoke(old) }
            // 끊기가 바꾸기의 행 잠금에서 기다릴 때까지.
            DriverManager.getConnection(jdbcUrl, user, password).use { probe ->
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline &&
                    probe.strings("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'UPDATE refresh_tokens SET revoked_at%'") == listOf("0")
                ) {
                    Thread.sleep(20)
                }
            }
            rotation.commit()
            logout.await()
        }

        assertThat(rows(userId)).hasSize(2).allSatisfy { assertThat(it["revoked_at"]).isNotNull() }
    }

    @Test
    fun `logout revokes the family`(): Unit = runBlocking {
        val userId = insertUser("logout")
        val token = service.issue(userId, remember = true).token

        service.revoke(token)

        assertThat(refresh(token)).isEqualTo(Refresh.Rejected)
    }

    @Test
    fun `a deleted user's tokens go with it`(): Unit = runBlocking {
        val userId = insertUser("deleted")
        val token = service.issue(userId, remember = true).token

        databaseClient.sql("DELETE FROM users WHERE id = :u").bind("u", userId).fetch().rowsUpdated().awaitSingle()

        assertThat(refresh(token)).isEqualTo(Refresh.Rejected)
    }

    // --- V45_down: V45 가 마지막일 때만 표를 지우고, 그 뒤 V45 가 다시 적용된다 ---

    private val jdbcUrl = "jdbc:postgresql://$host:$port/$name"

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

    private fun Connection.tables(schema: String): List<String> =
        strings("SELECT table_name FROM information_schema.tables WHERE table_schema = '$schema' AND table_name = 'refresh_tokens'")

    @Test
    fun `the down sql refuses unless V45 is the last migration, then drops the table and V45 applies again`() {
        val schema = "it_v45down_$runId".replace('-', '_')
        val downSql = RepoFiles.file("deploy/vultr/sql/V45_down.sql").readText()
        try {
            migrate(schema, "44")
            assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V45 가 적용돼 있지 않다")

            migrate(schema, "45")
            inSchema(schema) { conn ->
                conn.update(
                    "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, installed_by, execution_time, success) " +
                        "SELECT max(installed_rank) + 1, '46', 'later', 'SQL', 'V46__later.sql', current_user, 0, true FROM flyway_schema_history",
                )
            }
            assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V45 이후")

            inSchema(schema) { conn ->
                conn.update("DELETE FROM flyway_schema_history WHERE version = '46'")
                conn.update(downSql)
                assertThat(conn.tables(schema)).isEmpty()
                assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '45'")).isEmpty()
            }

            migrate(schema, "45")
            inSchema(schema) { conn -> assertThat(conn.tables(schema)).containsExactly("refresh_tokens") }
        } finally {
            DriverManager.getConnection(jdbcUrl, user, password).use { it.update("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }
}
