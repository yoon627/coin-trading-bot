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
 * 실제 Postgres 로 `trade_records.order_amount` 매핑을 본다. 컬럼명 오타나 타입 불일치는 DB 에서만 드러난다.
 * 접속·skip 규약은 [TradingStateRoundTripTest] 와 같다.
 */
@DataR2dbcTest
class TradeRecordRoundTripTest {

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

        // 실행마다 테스트 사용자 이름에 붙이는 접미사 길이. 다른 실행의 행을 지우지 않게 한다.
        private const val RUN_ID_LENGTH = 8

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

    private val runId = UUID.randomUUID().toString().take(RUN_ID_LENGTH)

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

    private fun record(userId: Long, side: TradeSide, totalAmount: Double, orderAmount: Double?) =
        TradeRecord(
            ticker = "KRW-BTC", side = side, price = 50_000_000.0, volume = 0.02, totalAmount = totalAmount,
            pnlPercent = if (side == TradeSide.SELL) 1.0 else null, pnlAmount = if (side == TradeSide.SELL) 500.0 else null,
            strategy = "combined", userId = userId, fee = FeeBasis.Estimate, orderAmount = orderAmount,
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
}
