package com.trading.bot.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.ci.RepoFiles
import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.entity.TradeExecutionEntity
import com.trading.bot.persistence.entity.UserEntity
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

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var tradeExecutionRepository: TradeExecutionRepository

    private val service by lazy { TradingStateService(repository, jacksonObjectMapper()) }

    companion object {
        private val host = System.getenv("TEST_DB_HOST")
        private val port = System.getenv("TEST_DB_PORT") ?: "5432"
        private val name = System.getenv("TEST_DB_NAME") ?: "trading"
        private val user = System.getenv("TEST_DB_USER") ?: "trading"
        private val password = System.getenv("TEST_DB_PASSWORD") ?: "trading"
        private val required = System.getenv("DB_TESTS_REQUIRED")?.toBoolean() ?: false

        private val available = host != null

        private val RETIRED_STATE_COLUMNS = listOf(
            "halted", "halt_reason", "reconcile_failure_count", "review_reason",
            "adopt_baseline_volume", "sell_clear_prior_volume", "sell_clear_volume",
        )
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
        pendingBuySince = Instant.parse("2026-08-26T00:59:00Z"),
        pendingBuyAlerted = true,
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
        assertThat(restored.pendingBuySince).isEqualTo(original.pendingBuySince)
        assertThat(restored.pendingBuyAlerted).isEqualTo(original.pendingBuyAlerted)
        assertThat(restored.exitParams).isEqualTo(original.exitParams)
        assertThat(restored.pendingBuyPriorVolume).isEqualTo(original.pendingBuyPriorVolume)
        assertThat(restored.pendingSellTriggerPrice).isEqualTo(original.pendingSellTriggerPrice)
        assertThat(restored.pendingSellPriorVolume).isEqualTo(original.pendingSellPriorVolume)
        assertThat(restored.pendingBuyIdentifier).isEqualTo(original.pendingBuyIdentifier)
        assertThat(restored.pendingSellIdentifier).isEqualTo(original.pendingSellIdentifier)
    }

    // 가입·체결 기록은 저장소의 save 로 들어간다. 엔티티와 스키마가 어긋나면 여기서 깨진다.
    @Test
    fun `users and trade executions save through the repositories on the current schema`() = runTest {
        val userId = userRepository.save(UserEntity(username = "it-save-$runId", password = "x")).awaitSingle().id!!
        tradeExecutionRepository.save(
            TradeExecutionEntity(userId = userId, exchange = "UPBIT", market = "KRW-BTC", side = "BUY", price = 100.0, volume = 1.0, totalAmount = 100.0),
        ).awaitSingle()

        assertThat(userRepository.findById(userId).awaitSingle().username).isEqualTo("it-save-$runId")
        assertThat(tradeExecutionRepository.findByUserId(userId, 10, 0).collectList().awaitSingle().map { it.market }).containsExactly("KRW-BTC")
        val unmapped = databaseClient.sql(
            "SELECT table_name || '.' || column_name AS c FROM information_schema.columns WHERE table_schema = current_schema() " +
                "AND ((table_name = 'users' AND column_name IN ('public_profile', 'public_strategy', 'admin')) " +
                "OR (table_name = 'trade_executions' AND column_name IN ('order_type', 'status')))",
        ).map { r -> r.get("c", String::class.java)!! }.all().collectList().awaitSingle()
        assertThat(unmapped).isEmpty()
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

    // 연결을 잡은 쿼리가 끝나지 않으면 그 종목 작업자(손절 판단 포함)가 같이 멈추므로 서버가 끊게 한다.
    @Test
    fun `the app's connections ask the server to cancel a statement after 10 seconds`() = runTest {
        val timeout = databaseClient.sql("SHOW statement_timeout").map { row -> row.get(0, String::class.java)!! }.one().awaitSingle()

        assertThat(timeout).isEqualTo("10s")
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

    @Test
    fun `existing rows get no buy start time, and the down sql refuses unless V38 is last, then drops the columns`() = withScratchSchema("v38down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V38_down.sql").readText()
        migrate(schema, "37")
        inSchema(schema) { conn ->
            conn.update("INSERT INTO users (username, password) VALUES ('it-v38-$runId', 'x')")
            conn.update("INSERT INTO trading_states (user_id, ticker) SELECT id, 'KRW-BTC' FROM users WHERE username = 'it-v38-$runId'")
        }
        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V38 이 적용돼 있지 않다")

        migrate(schema, "38")
        inSchema(schema) { conn ->
            assertThat(conn.strings("SELECT coalesce(pending_buy_since::text, 'null') || ',' || pending_buy_alerted::text FROM trading_states"))
                .containsExactly("null,false")
            conn.update(downSql)
            assertThat(conn.buyStuckColumns(schema)).isEmpty()
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '38'")).isEmpty()
        }

        migrate(schema, "38")
        inSchema(schema) { conn -> assertThat(conn.buyStuckColumns(schema)).containsExactlyInAnyOrder("pending_buy_since", "pending_buy_alerted") }
    }

    @Test
    fun `V39 drops the unused tables and columns, and the down sql refuses unless V39 is last, then restores them as they were`() = withScratchSchema("v39down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V39_down.sql").readText()
        migrate(schema, "38")
        val before = inSchema(schema) { conn ->
            // 운영에만 있는 손으로 만든 백업 테이블
            conn.update("CREATE TABLE users_bak_20260602 AS SELECT * FROM users")
            conn.update("INSERT INTO users (username, password) VALUES ('it-v39-$runId', 'x')")
            conn.update(
                "INSERT INTO trading_states (user_id, ticker, halted, review_reason) " +
                    "SELECT id, 'KRW-BTC', TRUE, 'old' FROM users WHERE username = 'it-v39-$runId'",
            )
            conn.retiredDefinitions(schema)
        }
        assertThat(before.filter { it.startsWith("trading_states.") }).hasSize(RETIRED_STATE_COLUMNS.size)
        assertThat(before).anyMatch { it.contains("ix_manual_interventions_user_created") }
        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V39 이 적용돼 있지 않다")

        migrate(schema, "39")
        inSchema(schema) { conn ->
            assertThat(conn.retiredDefinitions(schema)).isEmpty()
            assertThat(conn.droppedTables(schema)).isEmpty()
            assertThat(conn.strings("SELECT ticker FROM trading_states")).containsExactly("KRW-BTC")

            conn.update(downSql)
            assertThat(conn.retiredDefinitions(schema)).containsExactlyElementsOf(before)
            assertThat(conn.strings("SELECT halted::text || ',' || coalesce(review_reason, 'null') FROM trading_states")).containsExactly("false,null")
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '39'")).isEmpty()
        }

        migrate(schema, "39")
        inSchema(schema) { conn -> assertThat(conn.retiredDefinitions(schema)).isEmpty() }
    }

    @Test
    fun `V40 drops the user and trade execution columns no entity maps, and the down sql restores them as they were`() = withScratchSchema("v40down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V40_down.sql").readText()
        migrate(schema, "39")
        val before = inSchema(schema) { conn ->
            conn.update("INSERT INTO users (username, password, admin) VALUES ('it-v40-$runId', 'x', TRUE)")
            conn.update(
                "INSERT INTO trade_executions (user_id, exchange, market, side, price, volume, total_amount) " +
                    "SELECT id, 'UPBIT', 'KRW-BTC', 'BUY', 100.0, 1.0, 100.0 FROM users WHERE username = 'it-v40-$runId'",
            )
            conn.unmappedDefinitions(schema)
        }
        assertThat(before).hasSize(5)
        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V40 이 적용돼 있지 않다")

        migrate(schema, "40")
        inSchema(schema) { conn ->
            assertThat(conn.unmappedDefinitions(schema)).isEmpty()
            assertThat(conn.strings("SELECT market FROM trade_executions")).containsExactly("KRW-BTC")

            conn.update(downSql)
            assertThat(conn.unmappedDefinitions(schema)).containsExactlyElementsOf(before)
            assertThat(conn.strings("SELECT u.admin::text || ',' || e.order_type || ',' || e.status FROM users u JOIN trade_executions e ON e.user_id = u.id"))
                .containsExactly("false,MARKET,EXECUTED")
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '40'")).isEmpty()
        }

        migrate(schema, "40")
        inSchema(schema) { conn -> assertThat(conn.unmappedDefinitions(schema)).isEmpty() }
    }

    // 각 down 이 되살린 정의를 그 앞의 down 이 다시 지울 수 있어야 더 옛 이미지로 내려갈 수 있다(V42_down → … → V34_down).
    @Test
    fun `the down sqls from the latest version run one after another`() = withScratchSchema("downchain") { schema ->
        migrate(schema, "42")
        inSchema(schema) { conn ->
            for (v in 42 downTo 34) conn.update(RepoFiles.file("deploy/vultr/sql/V${v}_down.sql").readText())
            assertThat(conn.strings("SELECT max(version::int)::text FROM flyway_schema_history WHERE version IS NOT NULL")).containsExactly("33")
            // V34 의 세 칼럼과 V35 의 테이블은 지워지고, V14·V30 의 칼럼은 그 버전의 down 까지 남는다.
            assertThat(conn.retiredDefinitions(schema).map { it.substringBefore(' ') }).containsExactly(
                "trading_states.halt_reason",
                "trading_states.halted",
                "trading_states.reconcile_failure_count",
                "trading_states.review_reason",
            )
        }
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

    private fun Connection.buyStuckColumns(schema: String): List<String> = strings(
        "SELECT column_name FROM information_schema.columns " +
            "WHERE table_schema = '$schema' AND table_name = 'trading_states' AND column_name IN ('pending_buy_since', 'pending_buy_alerted')",
    )

    /** V39 가 지우는 trading_states 칼럼과 manual_interventions 의 정의(타입·NULL·기본값·identity), 그리고 그 테이블의 인덱스. */
    private fun Connection.retiredDefinitions(schema: String): List<String> {
        val columns = RETIRED_STATE_COLUMNS.joinToString(",") { "'$it'" }
        return strings(
            "SELECT table_name || '.' || column_name || ' ' || data_type || coalesce('(' || character_maximum_length || ')', '') || " +
                "' null=' || is_nullable || ' default=' || coalesce(column_default, '-') || ' identity=' || coalesce(identity_generation, '-') " +
                "FROM information_schema.columns WHERE table_schema = '$schema' " +
                "AND ((table_name = 'trading_states' AND column_name IN ($columns)) OR table_name = 'manual_interventions') " +
                "ORDER BY table_name, column_name",
        ) + strings(
            "SELECT replace(indexdef, '$schema.', '') FROM pg_indexes WHERE schemaname = '$schema' AND tablename = 'manual_interventions' ORDER BY indexname",
        )
    }

    /** V40 이 지우는 users·trade_executions 칼럼의 정의(타입·NULL·기본값). */
    private fun Connection.unmappedDefinitions(schema: String): List<String> = strings(
        "SELECT table_name || '.' || column_name || ' ' || data_type || coalesce('(' || character_maximum_length || ')', '') || " +
            "' null=' || is_nullable || ' default=' || coalesce(column_default, '-') " +
            "FROM information_schema.columns WHERE table_schema = '$schema' " +
            "AND ((table_name = 'users' AND column_name IN ('public_profile', 'public_strategy', 'admin')) " +
            "OR (table_name = 'trade_executions' AND column_name IN ('order_type', 'status'))) " +
            "ORDER BY table_name, column_name",
    )

    private fun Connection.droppedTables(schema: String): List<String> = strings(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = '$schema' " +
            "AND table_name IN ('trade_records_v21_backup', 'trade_executions_v21_backup', 'users_bak_20260602', 'manual_interventions')",
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
