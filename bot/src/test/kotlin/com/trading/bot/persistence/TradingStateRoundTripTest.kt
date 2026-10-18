package com.trading.bot.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
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
        halted = true,
        haltReason = "unexplained locked balance",
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
        assertThat(restored.halted).isEqualTo(original.halted)
        assertThat(restored.haltReason).isEqualTo(original.haltReason)
        assertThat(restored.reconcileFailureCount).isEqualTo(original.reconcileFailureCount)
        assertThat(restored.exitParams).isEqualTo(original.exitParams)
        assertThat(restored.pendingBuyPriorVolume).isEqualTo(original.pendingBuyPriorVolume)
        assertThat(restored.pendingSellTriggerPrice).isEqualTo(original.pendingSellTriggerPrice)
        assertThat(restored.pendingSellPriorVolume).isEqualTo(original.pendingSellPriorVolume)
        assertThat(restored.pendingBuyIdentifier).isEqualTo(original.pendingBuyIdentifier)
        assertThat(restored.pendingSellIdentifier).isEqualTo(original.pendingSellIdentifier)
    }

    /** upsert 는 (user, ticker) 유니크를 위반하지 않고 갱신돼 두 번 불러도 행이 하나다. */
    @Test
    fun `upsert updates in place instead of violating the user ticker unique constraint`() = runTest {
        val userId = insertUser("upsert")

        service.upsert(userId, fullyPopulated("KRW-ETH"))
        service.upsert(userId, fullyPopulated("KRW-ETH").apply { peakPrice = 99_999.0 })

        val rows = repository.findByUserId(userId).collectList().awaitSingle()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().peakPrice).isEqualTo(99_999.0)
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
}
