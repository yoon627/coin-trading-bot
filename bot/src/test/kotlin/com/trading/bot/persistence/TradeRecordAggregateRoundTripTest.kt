package com.trading.bot.persistence

import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
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
 * 실제 Postgres 로 V26 `trade_records.order_amount` 매핑과 `aggregateByStrategy` 의 집계 SQL 을 검증한다(#146).
 *
 * mock 기반 `TradeRecordRepositoryTest` 는 `save()` 가 필드를 실었는지만 본다 — 컬럼명 오타·타입 불일치·
 * `SUM`/`FILTER` 의미는 DB 에서만 드러난다. 접속·skip·CI 강제 규약은 [TradingStateRoundTripTest] 와 같다.
 */
@DataR2dbcTest
class TradeRecordAggregateRoundTripTest {

    @Autowired
    private lateinit var r2dbc: TradeRecordR2dbcRepository

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private val repository by lazy { TradeRecordRepository(r2dbc) }

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

    @AfterEach
    fun cleanUp() = runTest {
        val owned = "SELECT id FROM users WHERE username LIKE 'it-%-$runId'"
        for (sql in listOf(
            "DELETE FROM trade_records WHERE user_id IN ($owned)",
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

    private fun record(userId: Long, side: TradeSide, totalAmount: Double, orderAmount: Double?, strategy: String = "combined") =
        TradeRecord(
            ticker = "KRW-BTC", side = side, price = 50_000_000.0, volume = 0.02, totalAmount = totalAmount,
            pnlPercent = if (side == TradeSide.SELL) 1.0 else null, pnlAmount = if (side == TradeSide.SELL) 500.0 else null,
            strategy = strategy, userId = userId, fee = FeeBasis.Estimate, orderAmount = orderAmount,
        )

    @Test
    fun `order_amount survives a round trip and null stays null`() = runTest {
        val userId = insertUser("roundtrip")

        val known = repository.save(record(userId, TradeSide.BUY, totalAmount = 1_000_000.0, orderAmount = 50_000.0))
        val unknown = repository.save(record(userId, TradeSide.BUY, totalAmount = 1_000_000.0, orderAmount = null))

        val rows = repository.findByUserId(userId).associateBy { it.id }
        assertThat(rows.getValue(known.id!!).orderAmount).isCloseTo(50_000.0, within(1e-9))
        assertThat(rows.getValue(unknown.id!!).orderAmount).isNull()
    }

    /**
     * 집계의 `total_amount` 는 실체결 대금이 있는 행만 더하고, 미상 행은 건수로 따로 센다 — 엔진 BUY 스냅샷을 더하던
     * 부풀림(#146)이 사라지는 대신 축소로 오독되지 않게 두 값을 같이 낸다.
     */
    @Test
    fun `aggregate sums only measured order amounts and counts the unknown rows`() = runTest {
        val userId = insertUser("aggregate")
        repository.save(record(userId, TradeSide.BUY, totalAmount = 1_000_000.0, orderAmount = null)) // V26 이전 스냅샷 행
        repository.save(record(userId, TradeSide.BUY, totalAmount = 1_050_000.0, orderAmount = 50_000.0))
        repository.save(record(userId, TradeSide.SELL, totalAmount = 1_100_000.0, orderAmount = 1_098_000.0))
        repository.save(record(userId, TradeSide.BUY, totalAmount = 30_000.0, orderAmount = null, strategy = "manual"))

        val rows = repository.aggregateByStrategy(userId).associateBy { it.strategy }

        val combined = rows.getValue("combined")
        assertThat(combined.totalTrades).isEqualTo(3)
        assertThat(combined.totalAmount).isCloseTo(1_148_000.0, within(1e-6))
        assertThat(combined.amountUnknownTrades).isEqualTo(1)
        val manual = rows.getValue("manual")
        assertThat(manual.totalAmount).isEqualTo(0.0)
        assertThat(manual.amountUnknownTrades).isEqualTo(1)
    }
}
