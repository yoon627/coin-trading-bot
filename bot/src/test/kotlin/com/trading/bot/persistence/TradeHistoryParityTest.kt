package com.trading.bot.persistence

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.ZoneId

/**
 * 화면을 orders 로 바꾸기 전의 같은 결과 검증. 복원한 운영 덤프에만 돌린다(운영 DB 에는 돌리지 않는다):
 *   PARITY=true TEST_DB_HOST=<임시 postgres> … ./gradlew :bot:test --tests '*TradeHistoryParityTest*'
 * 마이그레이션을 적용한 뒤 모든 사용자·거래소에서 trade_records 와 orders 의 화면 값이 같은지 본다. 앱의 시간대(UTC)로 돌린다.
 */
@DataR2dbcTest
class TradeHistoryParityTest {

    @Autowired private lateinit var tradeRecords: TradeRecordR2dbcRepository
    @Autowired private lateinit var orderRows: OrderRowRepository

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
            check(ZoneId.systemDefault().normalized() == java.time.ZoneOffset.UTC) { "운영 앱처럼 UTC 로 돌린다 — TZ=UTC" }
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

    @Test
    fun `every trade record has an orders row with the same screen values`() = runTest {
        val records = tradeRecords.findAll().collectList().awaitSingle()
        val orders = orderRows.findAll().collectList().awaitSingle()

        val result = TradeHistoryParity.compare(records, orders, ZoneId.systemDefault())

        println("parity: compared=${result.compared} differences=${result.differences.size} sameTimeGroups=${result.sameTimeGroups} incomplete=${result.incomplete}")
        result.differences.take(20).forEach { println(it) }
        assertThat(result.differences).isEmpty()
        assertThat(result.incomplete).isZero()
    }
}
