package com.trading.bot.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.ci.RepoFiles
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.common.domain.Exchange
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingle
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
 * V30 거래소 축을 실제 Postgres 로 본다. 접속·skip 규약은 [TradingStateRoundTripTest] 와 같다.
 * V29 에서 V30 으로 올리는 경로와 되돌리기 SQL 은 실행마다 새로 만든 스키마에서 돌려 공용 스키마를 건드리지 않는다.
 */
@DataR2dbcTest
class ExchangeDimensionRoundTripTest {

    @Autowired
    private lateinit var stateRepository: TradingStateRepository

    @Autowired
    private lateinit var recordRepository: TradeRecordR2dbcRepository

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private val service by lazy { TradingStateService(stateRepository, jacksonObjectMapper()) }
    private val records by lazy { TradeRecordRepository(recordRepository) }

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
        val owned = "SELECT id FROM users WHERE username LIKE 'it-%-$runId'"
        for (sql in listOf(
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

    private suspend fun insertState(userId: Long, exchange: String, ticker: String) {
        databaseClient.sql("INSERT INTO trading_states (user_id, exchange, ticker) VALUES (:u, :e, :t)")
            .bind("u", userId).bind("e", exchange).bind("t", ticker)
            .fetch().rowsUpdated().awaitSingle()
    }

    @Test
    fun `the unique key is per exchange`() = runTest {
        val userId = insertUser("unique")

        insertState(userId, "KIS_PAPER", "KRX-005930")
        insertState(userId, "KIS", "KRX-005930")

        assertThatThrownBy { runBlocking { insertState(userId, "KIS_PAPER", "KRX-005930") } }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `the ticker check rejects KRX tickers on UPBIT and other tickers on KIS but keeps non-KRW upbit markets`() = runTest {
        val userId = insertUser("check")

        insertState(userId, "UPBIT", "BTC-ETH")

        assertThatThrownBy { runBlocking { insertState(userId, "UPBIT", "KRX-005930") } }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { runBlocking { insertState(userId, "KIS", "KRW-BTC") } }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `exchange and review reason survive a round trip and stay out of the upbit view`() = runTest {
        val userId = insertUser("roundtrip")
        val review = "주문 확인 필요(SELL KIS_PAPER:20261020:0001): 후보 2건"

        service.upsert(userId, TradingState("KRW-BTC", halted = true, haltReason = review, reviewReason = review))
        service.upsert(userId, TradingState("KRX-005930", exchange = Exchange.KIS_PAPER, pendingSellUuid = "KIS_PAPER:20261020:0001", reviewReason = review))
        service.upsert(userId, TradingState("KRX-005930", exchange = Exchange.KIS_PAPER, pendingSellUuid = "KIS_PAPER:20261020:0001", reviewReason = null))

        assertThat(service.loadStates(userId).keys).containsExactly("KRW-BTC")
        assertThat(service.loadState(userId, "KRW-BTC")?.reviewReason).isEqualTo(review)
        val kis = service.loadState(userId, "KRX-005930", Exchange.KIS_PAPER)
        assertThat(kis?.exchange).isEqualTo(Exchange.KIS_PAPER)
        assertThat(kis?.reviewReason).isNull()
        assertThat(stateRepository.findByUserIdAndExchange(userId, "KIS_PAPER").collectList().awaitSingle()).hasSize(1)
    }

    @Test
    fun `trade records keep their exchange`() = runTest {
        val userId = insertUser("records")

        records.save(
            TradeRecord(
                ticker = "KRX-005930", side = TradeSide.BUY, price = 70_000.0, volume = 3.0, totalAmount = 210_000.0,
                pnlPercent = null, pnlAmount = null, strategy = "combined", fee = FeeBasis.Estimate, orderAmount = 210_000.0,
                userId = userId, exchange = Exchange.KIS_PAPER,
            ),
        )
        records.save(
            TradeRecord(
                ticker = "KRW-BTC", side = TradeSide.BUY, price = 1.0, volume = 1.0, totalAmount = 1.0,
                pnlPercent = null, pnlAmount = null, strategy = "combined", fee = FeeBasis.Estimate, orderAmount = 1.0, userId = userId,
            ),
        )

        val written = Exchange.entries.flatMap { records.findByUserIdAndExchange(userId, it, limit = 100, offset = 0) }
        assertThat(written.associate { it.ticker to it.exchange })
            .isEqualTo(mapOf("KRX-005930" to "KIS_PAPER", "KRW-BTC" to "UPBIT"))
    }

    @Test
    fun `trade record queries return only the requested exchange of the user`() = runTest {
        val userId = insertUser("filter")
        val otherUser = insertUser("filter-other")
        val start = LocalDateTime.parse("2026-10-20T09:00")
        fun record(user: Long, ticker: String, side: TradeSide, exchange: Exchange, minute: Long) = TradeRecord(
            ticker = ticker, side = side, price = 1.0, volume = 1.0, totalAmount = 1.0, pnlPercent = null, pnlAmount = null,
            strategy = "combined", fee = FeeBasis.Estimate, orderAmount = 1.0, userId = user, exchange = exchange,
            createdAt = start.plusMinutes(minute),
        )
        records.save(record(userId, "KRW-BTC", TradeSide.BUY, Exchange.UPBIT, 0))
        records.save(record(userId, "KRX-005930", TradeSide.BUY, Exchange.KIS_PAPER, 1))
        records.save(record(userId, "KRW-BTC", TradeSide.SELL, Exchange.UPBIT, 2))
        records.save(record(otherUser, "KRW-ETH", TradeSide.BUY, Exchange.UPBIT, 3))

        val upbit = records.findByUserIdAndExchange(userId, Exchange.UPBIT, limit = 100, offset = 0)
        assertThat(upbit.map { it.side }).containsExactly("SELL", "BUY")
        assertThat(upbit.map { it.exchange }.distinct()).containsExactly("UPBIT")
        assertThat(records.findByUserIdAndExchange(userId, Exchange.KIS_PAPER, limit = 100, offset = 0).map { it.ticker })
            .containsExactly("KRX-005930")
        assertThat(records.findByUserIdAndExchange(userId, Exchange.UPBIT, limit = 1, offset = 1).map { it.side })
            .containsExactly("BUY")
        assertThat(records.countByUserIdAndExchange(userId, Exchange.UPBIT)).isEqualTo(2L)
        assertThat(records.countByUserIdAndExchange(userId, Exchange.KIS_PAPER)).isEqualTo(1L)
        assertThat(records.countByUserIdAndExchange(userId, Exchange.KIS)).isEqualTo(0L)
        assertThat(records.findRecentAscending(userId, Exchange.UPBIT, max = 10).map { it.side }).containsExactly("BUY", "SELL")
        assertThat(records.findRecentAscending(userId, Exchange.UPBIT, max = 1).map { it.side }).containsExactly("SELL")
        assertThat(records.findRecentAscending(userId, Exchange.KIS_PAPER, max = 10).map { it.ticker }).containsExactly("KRX-005930")
    }

    // --- V29 → V30 경로와 되돌리기 SQL. 실행마다 만든 스키마에서 JDBC 로 돈다. ---

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

    private fun seedV29(conn: Connection) {
        conn.update("INSERT INTO users (username, password) VALUES ('it-seed-$runId', 'x')")
        conn.update("INSERT INTO trading_states (user_id, ticker) SELECT id, 'KRW-BTC' FROM users WHERE username = 'it-seed-$runId'")
        conn.update(
            "INSERT INTO trade_records (ticker, side, price, volume, total_amount, user_id) " +
                "SELECT 'KRW-BTC', 'BUY', 1.0, 1.0, 1.0, id FROM users WHERE username = 'it-seed-$runId'",
        )
    }

    @Test
    fun `rows written before V30 become UPBIT`() = withScratchSchema("v30up") { schema ->
        migrate(schema, "29")
        inSchema(schema) { seedV29(it) }

        migrate(schema, "30")

        inSchema(schema) { conn ->
            assertThat(conn.strings("SELECT exchange FROM trading_states")).containsExactly("UPBIT")
            assertThat(conn.strings("SELECT exchange FROM trade_records")).containsExactly("UPBIT")
            assertThat(conn.strings("SELECT coalesce(review_reason, '<null>') FROM trading_states")).containsExactly("<null>")
        }
    }

    @Test
    fun `the down sql refuses while KIS rows or later migrations remain, then restores V29 and V30 applies again`() = withScratchSchema("v30down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V30_down.sql").readText()
        migrate(schema, "29")
        inSchema(schema) { seedV29(it) }
        migrate(schema, "30")
        inSchema(schema) { conn ->
            conn.update("INSERT INTO trading_states (user_id, exchange, ticker) SELECT id, 'KIS_PAPER', 'KRX-005930' FROM users WHERE username = 'it-seed-$runId'")
        }

        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V30_down")

        inSchema(schema) { conn ->
            conn.update("DELETE FROM trading_states WHERE exchange <> 'UPBIT'")
            conn.update(
                "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, installed_by, execution_time, success) " +
                    "SELECT max(installed_rank) + 1, '31', 'later', 'SQL', 'V31__later.sql', current_user, 0, true FROM flyway_schema_history",
            )
        }

        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V30 이후")

        inSchema(schema) { conn ->
            conn.update("DELETE FROM flyway_schema_history WHERE version = '31'")
            conn.update(downSql)
            assertThat(
                conn.strings(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = '$schema' " +
                        "AND table_name IN ('trading_states', 'trade_records') AND column_name IN ('exchange', 'review_reason')",
                ),
            ).isEmpty()
            assertThat(conn.strings("SELECT conname FROM pg_constraint WHERE conrelid = 'trading_states'::regclass AND contype = 'u'"))
                .containsExactly("uq_trading_states_user_ticker")
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '30'")).isEmpty()
        }

        migrate(schema, "30")

        inSchema(schema) { conn ->
            assertThat(conn.strings("SELECT exchange FROM trading_states")).containsExactly("UPBIT")
        }
    }
}
