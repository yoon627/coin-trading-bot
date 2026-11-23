package com.trading.bot.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.common.domain.Exchange
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 화면을 orders 로 바꾸는 배포 전의 같은 결과 검증. 복원한 운영 덤프에만 돌린다(운영 DB 에는 돌리지 않는다):
 *   TZ=UTC PARITY=true TEST_DB_HOST=<임시 postgres> … ./gradlew :bot:test --tests '*TradeHistoryParityTest*'
 * 마이그레이션을 적용한 뒤 두 표에 기록이 있는 모든 사용자·거래소에서 [TradeHistoryParity] 로 응답을 비교하고,
 * V43 NOTICE 와 같은 수(체결 시각 없는 줄·짝 기록 없는 줄·같은 시각 묶음)를 찍는다.
 */
@DataR2dbcTest
@ImportAutoConfiguration(JacksonAutoConfiguration::class)
class TradeHistoryParityTest {

    @Autowired private lateinit var tradeRecords: TradeRecordR2dbcRepository
    @Autowired private lateinit var orderRows: OrderRowRepository
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper

    companion object {
        private val host = System.getenv("TEST_DB_HOST")
        private val port = System.getenv("TEST_DB_PORT") ?: "5432"
        private val name = System.getenv("TEST_DB_NAME") ?: "trading"
        private val user = System.getenv("TEST_DB_USER") ?: "trading"
        private val password = System.getenv("TEST_DB_PASSWORD") ?: "trading"

        @JvmStatic
        @BeforeAll
        fun requireDump() {
            assumeTrue(System.getenv("PARITY") == "true" && host != null, "PARITY=true 와 복원한 덤프의 TEST_DB_HOST 가 있을 때만 돈다")
            // 운영 앱과 같은 시간대여야 운영 화면과 같은 값을 본다(Flyway 세션과 투영이 이 시간대를 쓴다).
            check(ZoneId.systemDefault().normalized() == ZoneOffset.UTC) { "운영 앱처럼 UTC 로 돌린다 — TZ=UTC" }
            Flyway.configure().dataSource("jdbc:postgresql://$host:$port/$name", user, password).locations("classpath:db/migration").load().migrate()
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

    private suspend fun count(sql: String): Long =
        databaseClient.sql(sql).map { row -> row.get(0, java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()

    @Test
    fun `every user and exchange gets the same history and round-trip responses from orders as from trade records`() = runTest {
        val keys = databaseClient.sql(
            "SELECT DISTINCT user_id, exchange FROM trade_records WHERE user_id IS NOT NULL " +
                "UNION SELECT DISTINCT user_id, exchange FROM orders WHERE status = 'FILLED' ORDER BY 1, 2",
        ).map { row -> row.get("user_id", java.lang.Long::class.java)!!.toLong() to Exchange.valueOf(row.get("exchange", String::class.java)!!) }
            .all().collectList().awaitSingle()
        val parity = TradeHistoryParity(TradeRecordRepository(tradeRecords), orderRows, objectMapper, ZoneId.systemDefault())

        val results = keys.map { (userId, exchange) -> parity.compare(userId, exchange) }
        val differences = results.flatMap { it.differences }
        val withoutTime = count("SELECT count(*) FROM orders WHERE status = 'FILLED' AND completed_at IS NULL")
        val withoutRecord = count("SELECT count(*) FROM orders WHERE status = 'FILLED' AND trade_record_id IS NULL")
        val sameTime = count(
            "SELECT count(*) FROM (SELECT 1 FROM orders WHERE status = 'FILLED' GROUP BY user_id, exchange, completed_at HAVING count(*) > 1) s",
        )

        println(
            "parity: keys=${keys.size} responses=${results.sumOf { it.responses }} differences=${differences.size} " +
                "filledWithoutTime=$withoutTime filledWithoutRecord=$withoutRecord sameTimeGroups=$sameTime",
        )
        differences.take(20).forEach { println(it) }
        assertThat(keys).isNotEmpty()
        assertThat(differences).isEmpty()
        assertThat(listOf(withoutTime, withoutRecord, sameTime)).containsOnly(0L)
    }
}
