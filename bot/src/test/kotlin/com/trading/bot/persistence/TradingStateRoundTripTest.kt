package com.trading.bot.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.ci.RepoFiles
import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 실제 Postgres 로 `trading_states` 매핑과 제약을 본다. mock 으로는 컬럼명·타입·제약을 검증할 수 없다.
 *
 * DB 는 띄우지 않고 `TEST_DB_*` 로 주어진 Postgres 에 접속한다. CI 는 `services: postgres`, 로컬은 `scripts/run-db-tests.sh` 를 쓴다.
 * docker-java 가 협상하는 API 버전(1.32)이 Docker 29 의 최소 지원(1.40)보다 낮아 Testcontainers 는 쓰지 않는다.
 * 접속 정보가 없으면 skip 하고, CI 는 `DB_TESTS_REQUIRED=true` 로 skip 을 실패로 바꾼다.
 */
@DataR2dbcTest
class TradingStateRoundTripTest {

    @Autowired
    private lateinit var repository: TradingStateRepository

    @Autowired
    private lateinit var databaseClient: DatabaseClient

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

            // 빈 DB(CI service)면 전체 적용, 이미 적용된 DB 면 no-op.
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
            registry.add("spring.flyway.enabled") { "false" } // 위 requireDatabase 가 JDBC 로 돌렸다
        }
    }

    /** 외부 DB 에 기존 데이터가 있을 수 있어 픽스처를 실행마다 유니크하게 만든다. */
    private val runId = UUID.randomUUID().toString().take(8)

    /** FK 가 cascade 가 아니라 자식(trade_executions·trading_states) → 부모(users) 순서로 지운다. */
    @AfterEach
    fun cleanUp() = runTest {
        val owned = "SELECT id FROM users WHERE username LIKE 'it-%-$runId'"
        for (sql in listOf(
            "DELETE FROM trade_executions WHERE user_id IN ($owned)",
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

    /** 전부 null 이면 매핑이 깨져도 통과하므로 모든 타입의 필드를 값으로 채운다. */
    private fun fullyPopulated(ticker: String) = TradingState(
        ticker = ticker,
        peakPrice = 71_234.5,
        buyDate = LocalDate.of(2026, 8, 20),
        boughtToday = true,
        boughtDate = LocalDate.of(2026, 8, 26),
        entryStrategy = "rsi_bounce",
        pendingBuyUuid = "buy-uuid-$runId",
        pendingBuyStrategy = "combined",
        pendingSellUuid = "sell-uuid-$runId",
        pendingSellReason = SellReason.TRAILING_STOP,
        pendingSellVolume = 0.00123456,
        pendingSellAvgPrice = 70_000.25,
        pendingSellSince = Instant.parse("2026-08-26T01:02:03Z"),
        pendingSellAlerted = true,
        reconcileFailureCount = 3,
        pendingBuyPriorVolume = 0.00098765,
        pendingSellTriggerPrice = 71_000.0,
        pendingSellPriorVolume = 0.00456789,
        pendingBuyIdentifier = "ctb-buy-$runId",
        pendingSellIdentifier = "ctb-sell-$runId",
        exitParams = ExitParamsSnapshot(
            takeProfitPct = 5.0,
            maxLossPct = 3.0,
            trailingStopPct = 2.0,
            trailingArmPct = 1.5,
            maxHoldDays = 1,
        ),
    )

    @Test
    fun `every persisted field survives a round trip through real postgres`() = runTest {
        val userId = insertUser("roundtrip")
        val original = fullyPopulated("KRW-BTC")

        service.upsert(userId, original)
        val restored = service.loadStates(userId)["KRW-BTC"]

        assertThat(restored).isNotNull
        assertThat(restored!!.peakPrice).isEqualTo(original.peakPrice)
        assertThat(restored.buyDate).isEqualTo(original.buyDate)
        assertThat(restored.boughtToday).isEqualTo(original.boughtToday)
        assertThat(restored.boughtDate).isEqualTo(original.boughtDate)
        assertThat(restored.entryStrategy).isEqualTo(original.entryStrategy)
        assertThat(restored.pendingBuyUuid).isEqualTo(original.pendingBuyUuid)
        assertThat(restored.pendingBuyStrategy).isEqualTo(original.pendingBuyStrategy)
        assertThat(restored.pendingSellUuid).isEqualTo(original.pendingSellUuid)
        assertThat(restored.pendingSellReason).isEqualTo(original.pendingSellReason)
        assertThat(restored.pendingSellVolume).isEqualTo(original.pendingSellVolume)
        assertThat(restored.pendingSellAvgPrice).isEqualTo(original.pendingSellAvgPrice)
        assertThat(restored.pendingSellSince).isEqualTo(original.pendingSellSince)
        assertThat(restored.pendingSellAlerted).isEqualTo(original.pendingSellAlerted)
        assertThat(restored.reconcileFailureCount).isEqualTo(original.reconcileFailureCount)
        assertThat(restored.exitParams).isEqualTo(original.exitParams)
        assertThat(restored.pendingBuyPriorVolume).isEqualTo(original.pendingBuyPriorVolume)
        assertThat(restored.pendingSellTriggerPrice).isEqualTo(original.pendingSellTriggerPrice)
        assertThat(restored.pendingSellPriorVolume).isEqualTo(original.pendingSellPriorVolume)
        assertThat(restored.pendingBuyIdentifier).isEqualTo(original.pendingBuyIdentifier)
        assertThat(restored.pendingSellIdentifier).isEqualTo(original.pendingSellIdentifier)
    }

    /** 앱이 읽고 쓰지 않는 칼럼(halt·확인 필요 사유·pending 해제 기준값). */
    private suspend fun retiredColumns(userId: Long, ticker: String): List<Any?> =
        databaseClient.sql(
            "SELECT halted, halt_reason, review_reason, adopt_baseline_volume, sell_clear_prior_volume, sell_clear_volume " +
                "FROM trading_states WHERE user_id = :u AND ticker = :t",
        )
            .bind("u", userId).bind("t", ticker)
            .map { row ->
                listOf(
                    row.get("halted", java.lang.Boolean::class.java)!!.booleanValue(),
                    row.get("halt_reason", String::class.java),
                    row.get("review_reason", String::class.java),
                    row.get("adopt_baseline_volume", java.lang.Double::class.java)?.toDouble(),
                    row.get("sell_clear_prior_volume", java.lang.Double::class.java)?.toDouble(),
                    row.get("sell_clear_volume", java.lang.Double::class.java)?.toDouble(),
                )
            }
            .one().awaitSingle()

    // 앱은 이 칼럼들을 읽고 쓰지 않는다. 직전 이미지로 되돌려도 그 칼럼이 남긴 값 그대로 읽히고, 새 행은 기본값이다.
    @Test
    fun `a save leaves the retired columns of an existing row as they were and a new row gets the defaults`() = runTest {
        val userId = insertUser("retired-cols")
        service.upsert(userId, TradingState("KRW-BTC", peakPrice = 1.0))
        assertThat(retiredColumns(userId, "KRW-BTC")).containsExactly(false, null, null, null, null, null)
        databaseClient.sql(
            "UPDATE trading_states SET halted = TRUE, halt_reason = 'old halt', review_reason = 'old review', " +
                "adopt_baseline_volume = 0.1, sell_clear_prior_volume = 0.2, sell_clear_volume = 0.3 WHERE user_id = :u AND ticker = 'KRW-BTC'",
        )
            .bind("u", userId).fetch().rowsUpdated().awaitSingle()

        val loaded = service.loadState(userId, "KRW-BTC")!!
        loaded.peakPrice = 2.0
        service.upsert(userId, loaded)

        assertThat(retiredColumns(userId, "KRW-BTC")).containsExactly(true, "old halt", "old review", 0.1, 0.2, 0.3)
        assertThat(service.loadState(userId, "KRW-BTC")!!.peakPrice).isEqualTo(2.0)
    }

    /** upsert 는 (user, ticker) 유니크를 위반하지 않고 갱신돼 두 번 불러도 행이 하나다. 두 번째 저장은 첫 저장의 버전을 들고 온다. */
    @Test
    fun `upsert updates in place instead of violating the user ticker unique constraint`() = runTest {
        val userId = insertUser("upsert")
        val state = fullyPopulated("KRW-ETH")

        state.rowVersion = service.upsert(userId, state)
        state.peakPrice = 99_999.0
        state.rowVersion = service.upsert(userId, state)

        val rows = repository.findByUserId(userId).collectList().awaitSingle()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().peakPrice).isEqualTo(99_999.0)
        assertThat(rows.single().version).isEqualTo(1L)
    }

    // 메모리가 마지막으로 읽거나 저장한 뒤 다른 쓰기가 들어온 행은 덮지 않는다(#365).
    @Test
    fun `a save with a version the row no longer has changes nothing`() = runTest {
        val userId = insertUser("version")
        assertThat(service.upsert(userId, TradingState("KRW-BTC", peakPrice = 1.0))).isEqualTo(0L)
        val mine = service.loadState(userId, "KRW-BTC")!!
        val other = service.loadState(userId, "KRW-BTC")!!
        assertThat(mine.rowVersion).isEqualTo(0L)

        other.peakPrice = 2.0
        other.rowVersion = service.upsert(userId, other)
        mine.peakPrice = 3.0

        assertThatThrownBy { kotlinx.coroutines.runBlocking { service.upsert(userId, mine) } }
            .isInstanceOf(TradingStateConflictException::class.java)
        assertThatThrownBy { kotlinx.coroutines.runBlocking { service.upsert(userId, TradingState("KRW-BTC", peakPrice = 4.0)) } }
            .isInstanceOf(TradingStateConflictException::class.java)
        val row = repository.findByUserIdAndTicker(userId, "KRW-BTC").awaitSingle()
        assertThat(row.peakPrice).isEqualTo(2.0)
        assertThat(row.version).isEqualTo(1L)

        // 읽은 뒤 저장 사이에 들어온 쓰기는 사전 비교를 지나므로, Spring Data 의 조건부 UPDATE 가 0행으로 막아야 한다.
        databaseClient.sql("UPDATE trading_states SET version = version + 1 WHERE id = :id").bind("id", row.id!!)
            .fetch().rowsUpdated().awaitSingle()
        assertThatThrownBy { kotlinx.coroutines.runBlocking { repository.save(row.copy(peakPrice = 5.0)).awaitSingle() } }
            .isInstanceOf(OptimisticLockingFailureException::class.java)
        assertThat(repository.findByUserIdAndTicker(userId, "KRW-BTC").awaitSingle().peakPrice).isEqualTo(2.0)

        databaseClient.sql("DELETE FROM trading_states WHERE id = :id").bind("id", row.id!!).fetch().rowsUpdated().awaitSingle()
        assertThatThrownBy { kotlinx.coroutines.runBlocking { service.upsert(userId, other) } }
            .isInstanceOf(TradingStateConflictException::class.java)
    }

    /**
     * `exchange_order_id IS NOT NULL` 부분 unique index 는 reconcile 이 같은 주문을 두 번 기록하는 것을 막고,
     * `exchange_order_id` 가 NULL 인 기록은 여러 건 받는다.
     */
    @Test
    fun `partial unique index blocks duplicate order ids but allows many nulls`() = runTest {
        val userId = insertUser("partial")
        val orderId = "order-$runId"

        insertExecution(userId, orderId = null)
        insertExecution(userId, orderId = null)
        insertExecution(userId, orderId = orderId)

        val nulls = databaseClient.sql(
            "SELECT count(*) c FROM trade_executions WHERE user_id = :u AND exchange_order_id IS NULL",
        ).bind("u", userId).map { row -> row.get("c", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
        assertThat(nulls).isEqualTo(2)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { insertExecution(userId, orderId = orderId) } }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private suspend fun insertExecution(userId: Long, orderId: String?) {
        databaseClient.sql(
            """
            INSERT INTO trade_executions (user_id, exchange, market, side, price, volume, total_amount, exchange_order_id)
            VALUES (:u, 'UPBIT', 'KRW-BTC', 'BUY', 100.0, 1.0, 100.0, :o)
            """.trimIndent(),
        ).bind("u", userId)
            .let { spec -> if (orderId == null) spec.bindNull("o", String::class.java) else spec.bind("o", orderId) }
            .fetch().rowsUpdated().awaitSingle()
    }

    // --- V36 → V37 경로와 되돌리기 SQL. 실행마다 만든 스키마에서 JDBC 로 돈다 — 공유 스키마에서 돌리면 다른 시험의 칼럼이 사라진다. ---

    @Test
    fun `existing rows get version zero, and the down sql refuses unless V37 is last, then drops the column`() = withScratchSchema("v37down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V37_down.sql").readText()
        migrate(schema, "36")
        inSchema(schema) { conn ->
            conn.update("INSERT INTO users (username, password) VALUES ('it-v37-$runId', 'x')")
            conn.update("INSERT INTO trading_states (user_id, ticker) SELECT id, 'KRW-BTC' FROM users WHERE username = 'it-v37-$runId'")
        }
        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V37 이 적용돼 있지 않다")

        migrate(schema, "37")
        inSchema(schema) { conn ->
            assertThat(conn.strings("SELECT version::text FROM trading_states")).containsExactly("0")
            conn.update(downSql)
            assertThat(conn.versionColumn(schema)).isEmpty()
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '37'")).isEmpty()
        }

        migrate(schema, "37")
        inSchema(schema) { conn -> assertThat(conn.versionColumn(schema)).containsExactly("version") }
    }

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

    private fun Connection.versionColumn(schema: String): List<String> = strings(
        "SELECT column_name FROM information_schema.columns " +
            "WHERE table_schema = '$schema' AND table_name = 'trading_states' AND column_name = 'version'",
    )

    private fun withScratchSchema(label: String, block: (String) -> Unit) {
        val schema = "it_${label}_$runId".replace('-', '_')
        try {
            block(schema)
        } finally {
            DriverManager.getConnection(jdbcUrl, user, password).use { it.update("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }
}
