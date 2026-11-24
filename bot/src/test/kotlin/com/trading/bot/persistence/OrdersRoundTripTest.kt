package com.trading.bot.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.api.TradeHistoryRow
import com.trading.bot.api.toHistoryRow
import com.trading.bot.ci.RepoFiles
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.FeeKind
import com.trading.bot.domain.FillSource
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.engine.LifecycleOrder
import com.trading.bot.engine.OrderLedger
import com.trading.bot.engine.OrderLifecycle
import com.trading.bot.engine.TradeExecutionService
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import java.sql.Connection
import java.sql.DriverManager
import java.time.ZoneId
import java.time.ZoneOffset
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
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator

/**
 * 실제 Postgres 로 `orders` 표와 V41 의 옛 체결 옮기기를 본다. 접속 방식은 [TradingStateRoundTripTest] 와 같다.
 * 마이그레이션 시험은 실행마다 만든 스키마에서 JDBC 로 돈다 — 공유 스키마에서 돌리면 다른 시험의 행이 섞인다.
 */
@DataR2dbcTest
class OrdersRoundTripTest {

    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var outbox: TradeNotificationOutboxRepository
    @Autowired private lateinit var states: TradingStateRepository
    @Autowired private lateinit var orderRows: OrderRowRepository
    @Autowired private lateinit var transactionManager: ReactiveTransactionManager

    private val stateService by lazy { TradingStateService(states, jacksonObjectMapper()) }
    private val service by lazy {
        TradeExecutionService(
            outbox, OrderLedger(orderRows),
            TransactionalOperator.create(transactionManager), TradingProperties(),
        )
    }

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

        private const val V41 = "bot/src/main/resources/db/migration/V41__orders.sql"
        private const val V42 = "bot/src/main/resources/db/migration/V42__orders_read.sql"
        private const val V43 = "bot/src/main/resources/db/migration/V43__orders_read_switch.sql"
        private const val MANUAL_CATCHUP = "deploy/vultr/sql/orders_catchup.sql"
    }

    private val runId = UUID.randomUUID().toString().take(8)

    @AfterEach
    fun cleanUp() = runTest {
        val owned = "SELECT id FROM users WHERE username LIKE 'it-%-$runId'"
        for (sql in listOf(
            // 알림 대기열이 orders 를 가리키므로(V42) 먼저 지운다.
            "DELETE FROM trade_notification_outbox WHERE user_id IN ($owned)",
            "DELETE FROM orders WHERE user_id IN ($owned)",
            "DELETE FROM trade_executions WHERE user_id IN ($owned)",
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

    private suspend fun count(table: String, userId: Long): Long =
        databaseClient.sql("SELECT COUNT(*) AS n FROM $table WHERE user_id = :u").bind("u", userId)
            .map { row -> row.get("n", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()

    private suspend fun orderRow(userId: Long): Map<String, Any?> =
        databaseClient.sql("SELECT * FROM orders WHERE user_id = :u").bind("u", userId).fetch().one().awaitSingle()

    private fun sellFill(userId: Long, market: String = "KRW-BTC") = TradeRecord(
        ticker = market, side = TradeSide.SELL, price = 50_000_000.0, volume = 0.002, totalAmount = 100_000.0,
        pnlPercent = 1.5, pnlAmount = 1_500.0, strategy = "combined", reason = "TAKE_PROFIT",
        exchangeOrderId = "s-$runId", userId = userId, fee = FeeBasis.Measured(50.0), orderAmount = 99_950.0,
        executedVwap = 49_975_000.0, orderVolume = 0.002, positionVolumeAfter = 0.0, bookAvgPrice = 49_000_000.0,
        clientOrderId = "ctb-$runId",
    )

    // --- 화면 읽기: FILLED 만, 사용자·거래소별, 체결 시각 내림차순(같으면 id), 쪽 나누기는 SQL ---

    /** 체결 시각이 [minute] 분인 orders 줄을 넣고 id 를 돌려준다. [minute] 이 null 이면 체결 시각이 없다. */
    private suspend fun filledRow(userId: Long, minute: Int?, exchange: String = "UPBIT", status: String = "FILLED", orderId: String? = null): Long =
        databaseClient.sql(
            "INSERT INTO orders (user_id, exchange, market, side, status, exchange_order_id, decision_price, filled_volume, fill_source, fee_basis, completed_at) " +
                "VALUES (:u, :e, 'KRW-BTC', 'SELL', :s, :o, 100, 1, 'ORDER', 'MEASURED', " +
                "CASE WHEN CAST(:m AS int) IS NULL THEN NULL ELSE TIMESTAMPTZ '2026-11-23 00:00:00+00' + make_interval(mins => CAST(:m AS int)) END) RETURNING id",
        )
            .bind("u", userId).bind("e", exchange).bind("s", status)
            .let { if (orderId == null) it.bindNull("o", String::class.java) else it.bind("o", orderId) }
            .let { if (minute == null) it.bindNull("m", Integer::class.java) else it.bind("m", minute) }
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    @Test
    fun `the history page reads only this user's FILLED rows on this exchange, newest first, ties by id, rows without a time last`() = runTest {
        val userId = insertUser("page")
        val other = insertUser("page-other")
        val noTime = filledRow(userId, null)
        val older = filledRow(userId, 1)
        val tieA = filledRow(userId, 5)
        val tieB = filledRow(userId, 5)
        val newest = filledRow(userId, 9)
        filledRow(userId, 20, status = "OPEN")
        filledRow(userId, 30, exchange = "KIS_PAPER")
        filledRow(other, 40)

        val all = orderRows.findFilledPage(userId, "UPBIT", 100, 0).collectList().awaitSingle().map { it.id }
        assertThat(all).containsExactly(newest, tieB, tieA, older, noTime)
        assertThat(orderRows.countFilled(userId, "UPBIT").awaitSingle()).isEqualTo(5L)
        assertThat(orderRows.findFilledPage(userId, "UPBIT", 2, 2).collectList().awaitSingle().map { it.id }).containsExactly(tieA, older)
        assertThat(orderRows.findFilledPage(userId, "UPBIT", 2, 5).collectList().awaitSingle()).isEmpty()
        assertThat(orderRows.findFilledPage(userId, "KIS_PAPER", 100, 0).collectList().awaitSingle()).hasSize(1)
    }

    @Test
    fun `the round-trip source is the newest rows in ascending order, so a cut drops the oldest`() = runTest {
        val userId = insertUser("recent")
        val ids = (1..4).map { filledRow(userId, it) }

        assertThat(orderRows.findFilledRecentAscending(userId, "UPBIT", 3).collectList().awaitSingle().map { it.id })
            .containsExactlyElementsOf(ids.drop(1))
        assertThat(orderRows.findFilledRecentAscending(userId, "UPBIT", 10).collectList().awaitSingle().map { it.id })
            .containsExactlyElementsOf(ids)
    }

    // --- 매일 대조: 같은 거래소의 FILLED 줄에 있는 주문번호만 기록된 것이다 ---

    @Test
    fun `an order number counts as recorded only for a FILLED row on the same exchange`() = runTest {
        val userId = insertUser("recorded")
        filledRow(userId, 1, orderId = "u-filled-$runId")
        filledRow(userId, 2, orderId = "u-open-$runId", status = "OPEN")
        filledRow(userId, 3, orderId = "u-shared-$runId", exchange = "KIS_PAPER")

        val asked = listOf("u-filled-$runId", "u-open-$runId", "u-shared-$runId", "u-none-$runId")
        assertThat(orderRows.findRecordedOrderIds(userId, "UPBIT", asked).collectList().awaitSingle()).containsExactly("u-filled-$runId")
    }

    // --- 체결 기록 트랜잭션 안의 orders 쓰기 ---

    // 옛 화면은 trade_records.created_at(같은 LocalDateTime 을 timestamp 로 넣은 값)을 보였다. orders 의 체결 시각이 그 값과 같아야
    // 화면 시각이 그대로다 — 마이크로초 반올림까지(초 경계·날 경계의 나노초 포함).
    @Test
    fun `a fill's orders time is its record time stored as a timestamp, including nanosecond rounding at a day boundary`() = runTest {
        val userId = insertUser("time")
        val times = listOf(
            java.time.LocalDateTime.of(2026, 11, 23, 23, 59, 59, 999_999_600),
            java.time.LocalDateTime.of(2026, 11, 24, 0, 0, 0, 500),
            java.time.LocalDateTime.of(2026, 11, 24, 9, 0, 0, 123_456_789),
        )
        times.forEachIndexed { i, at -> service.commitFill({}, sellFill(userId).copy(exchangeOrderId = "t-$runId-$i", clientOrderId = null, createdAt = at)) }

        val same = times.mapIndexed { i, at ->
            databaseClient.sql("SELECT completed_at = CAST(:at AS timestamp) AS same FROM orders WHERE exchange_order_id = :o")
                .bind("at", at).bind("o", "t-$runId-$i")
                .map { r -> r.get("same", java.lang.Boolean::class.java)!!.booleanValue() }.one().awaitSingle()
        }
        assertThat(same).containsExactly(true, true, true)
    }

    // 옛 trade_records 는 TradeRecord 의 화면 값(가격·수량·총액·주문 대금·손익·사유·전략·시각)을 그대로 적었다. 네 종류의 실제 경로
    // 줄이 orders 에서 그 값으로 다시 만들어지는지 본다 — 옛 표를 지운 뒤에도 화면 값이 그대로인 근거다.
    @Test
    fun `fills written through the real path give the screen values the old trade records held`() = runTest {
        val userId = insertUser("parity")
        val buy = TradeRecord(
            ticker = "KRW-BTC", side = TradeSide.BUY, price = 100.0, volume = 2.0, totalAmount = 200.0, pnlPercent = null, pnlAmount = null,
            strategy = "combined", exchangeOrderId = "pb-$runId", userId = userId, fee = FeeBasis.Measured(0.1), orderAmount = 50.0,
            executedVwap = 100.0, orderVolume = 0.5, positionVolumeAfter = 2.0, bookAvgPrice = 100.0, clientOrderId = "ctb-pb-$runId",
            createdAt = java.time.LocalDateTime.of(2026, 11, 23, 9, 0, 0, 111_111_111),
        )
        val balanceBuy = buy.copy(
            exchangeOrderId = "pbb-$runId", clientOrderId = null, fee = FeeBasis.Unrecorded, orderAmount = null, executedVwap = null,
            orderVolume = null, price = 51_000_000.0, volume = 0.0011, totalAmount = 51_000_000.0 * 0.0011, positionVolumeAfter = 0.0011,
            bookAvgPrice = 51_000_000.0, fillSource = FillSource.BALANCE, createdAt = buy.createdAt.plusMinutes(1),
        )
        // 부분 체결 매도: 장부가 남는다.
        val sell = sellFill(userId).copy(
            exchangeOrderId = "ps-$runId", clientOrderId = "ctb-ps-$runId", positionVolumeAfter = 0.3, createdAt = buy.createdAt.plusMinutes(2),
        )
        val balanceSell = sellFill(userId).copy(
            exchangeOrderId = "psb-$runId", clientOrderId = null, fee = FeeBasis.Estimate, orderAmount = null, executedVwap = null,
            orderVolume = null, volume = 0.7, totalAmount = 50_000_000.0 * 0.7, fillSource = FillSource.BALANCE, createdAt = buy.createdAt.plusMinutes(3),
        )
        for (record in listOf(buy, balanceBuy, sell, balanceSell)) assertThat(service.commitFill({}, record)).isTrue()

        val oldScreen = listOf(buy, balanceBuy, sell, balanceSell).map {
            TradeHistoryRow(
                id = null, ticker = it.ticker, side = it.side.name, price = it.price, volume = it.volume, totalAmount = it.totalAmount,
                orderAmount = it.orderAmount, pnlPercent = it.pnlPercent, pnlAmount = it.pnlAmount, reason = it.reason, strategy = it.strategy,
                userId = it.userId, createdAt = it.createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
            )
        }

        val fromOrders = orderRows.findFilledRecentAscending(userId, "UPBIT", 10).collectList().awaitSingle()
            .map { it.toHistoryRow(ZoneId.systemDefault()) }
        assertThat(fromOrders.map { it.incomplete }).containsOnly(false)
        assertThat(fromOrders.map { it.copy(id = null, fillSource = null) }).containsExactlyElementsOf(oldScreen)
    }

    // 생애 줄(접수된 주문)이 먼저 있으면 체결은 그 줄을 FILLED 로 바꾼다. 바꾼 줄의 화면 값이 넣은 줄과 같아야 화면이 그대로다.
    @Test
    fun `fills over open lifecycle rows update those rows and give the same screen values as inserted fills`() = runTest {
        val inserted = insertUser("parity-insert")
        val updated = insertUser("parity-update")
        fun fills(userId: Long, tag: String): List<TradeRecord> {
            val buy = TradeRecord(
                ticker = "KRW-BTC", side = TradeSide.BUY, price = 100.0, volume = 2.0, totalAmount = 200.0, pnlPercent = null, pnlAmount = null,
                strategy = "combined", exchangeOrderId = "ub-$tag-$runId", userId = userId, fee = FeeBasis.Measured(0.1), orderAmount = 50.0,
                executedVwap = 100.0, orderVolume = 0.5, positionVolumeAfter = 2.0, bookAvgPrice = 100.0, clientOrderId = "ctb-ub-$tag-$runId",
                createdAt = java.time.LocalDateTime.of(2026, 11, 24, 9, 0, 0),
            )
            return listOf(
                buy,
                buy.copy(
                    exchangeOrderId = "ubb-$tag-$runId", clientOrderId = null, fee = FeeBasis.Unrecorded, orderAmount = null, executedVwap = null,
                    orderVolume = null, price = 51_000_000.0, volume = 0.0011, totalAmount = 51_000_000.0 * 0.0011, positionVolumeAfter = 0.0011,
                    bookAvgPrice = 51_000_000.0, fillSource = FillSource.BALANCE, createdAt = buy.createdAt.plusMinutes(1),
                ),
                sellFill(userId).copy(exchangeOrderId = "us-$tag-$runId", clientOrderId = "ctb-us-$tag-$runId", positionVolumeAfter = 0.3, createdAt = buy.createdAt.plusMinutes(2)),
                sellFill(userId).copy(
                    exchangeOrderId = "usb-$tag-$runId", clientOrderId = null, fee = FeeBasis.Estimate, orderAmount = null, executedVwap = null,
                    orderVolume = null, volume = 0.7, totalAmount = 50_000_000.0 * 0.7, fillSource = FillSource.BALANCE, createdAt = buy.createdAt.plusMinutes(3),
                ),
            )
        }
        for (record in fills(inserted, "i")) assertThat(service.commitFill({}, record)).isTrue()
        for (record in fills(updated, "u")) {
            // 접수 때 남는 줄: 판단 가격·요청 수량이 있는 OPEN 줄. 잔고 확정 체결도 주문번호로 찾는다.
            databaseClient.sql(
                "INSERT INTO orders (user_id, exchange, market, side, client_order_id, exchange_order_id, status, decision_price, requested_volume, requested_funds) " +
                    "VALUES (:u, 'UPBIT', :m, :s, :c, :o, 'OPEN', 1.0, 9.0, 9.0)",
            ).bind("u", updated).bind("m", record.ticker).bind("s", record.side.name).bind("o", record.exchangeOrderId!!)
                .let { if (record.clientOrderId == null) it.bindNull("c", String::class.java) else it.bind("c", record.clientOrderId!!) }
                .fetch().rowsUpdated().awaitSingle()
            assertThat(service.commitFill({}, record)).isTrue()
        }

        fun screen(userId: Long) = orderRows.findFilledRecentAscending(userId, "UPBIT", 10).collectList().block()!!
            .map { it.toHistoryRow(ZoneId.systemDefault()).copy(id = null, userId = null) }
        assertThat(count("orders", updated)).isEqualTo(4)
        assertThat(screen(updated)).isEqualTo(screen(inserted))
        assertThat(databaseClient.sql("SELECT count(*) AS n FROM orders WHERE user_id = :u AND requested_funds = 9.0 AND updated_at > created_at")
            .bind("u", updated).map { r -> r.get("n", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()).isEqualTo(4L)
    }

    // 보내기 전에 남는 매수 줄(식별자만)은 체결이 주문번호를 채우며 FILLED 로 바꾼다.
    @Test
    fun `a fill finds a pending row by its client order id and fills in the order number`() = runTest {
        val userId = insertUser("pending")
        existingOrder(userId, "PENDING", orderId = null, clientOrderId = "ctb-$runId")

        assertThat(service.commitFill({}, sellFill(userId))).isTrue()

        assertThat(orderRow(userId)).containsEntry("status", "FILLED").containsEntry("exchange_order_id", "s-$runId")
            .containsEntry("client_order_id", "ctb-$runId")
    }

    @Test
    fun `a balance-confirmed sell keeps its order-time volume and the notification row points at its orders row`() = runTest {
        val userId = insertUser("balsell")
        val record = sellFill(userId).copy(
            fee = FeeBasis.Estimate, orderAmount = null, executedVwap = null, orderVolume = null, clientOrderId = null,
            fillSource = FillSource.BALANCE, volume = 0.7,
        )

        assertThat(service.commitFill({}, record)).isTrue()

        val row = orderRow(userId)
        assertThat(row["requested_volume"]).isEqualTo(0.7)
        assertThat(row["filled_volume"]).isNull()
        val linked = databaseClient.sql("SELECT order_id FROM trade_notification_outbox WHERE user_id = :u").bind("u", userId)
            .map { r -> r.get("order_id", java.lang.Long::class.java)?.toLong() }.one().awaitSingle()
        assertThat(linked).isEqualTo(row["id"])
    }

    @Test
    fun `a committed fill leaves one FILLED orders row with this order's values and nothing in the old trade tables`() = runTest {
        val userId = insertUser("fill")

        assertThat(service.commitFill({ stateService.upsert(userId, TradingState("KRW-BTC")) }, sellFill(userId))).isTrue()

        val row = orderRow(userId)
        assertThat(row["status"]).isEqualTo("FILLED")
        assertThat(row["trade_execution_id"]).isNull()
        assertThat(row["trade_record_id"]).isNull()
        assertThat(count("trade_executions", userId)).isZero()
        assertThat(count("trade_records", userId)).isZero()
        assertThat(count("trading_states", userId)).isEqualTo(1)
        val notification = databaseClient.sql("SELECT order_id, trade_execution_id FROM trade_notification_outbox WHERE user_id = :u").bind("u", userId)
            .fetch().one().awaitSingle()
        assertThat(notification).containsEntry("order_id", row["id"]).containsEntry("trade_execution_id", null)
        assertThat(row).containsEntry("exchange", "UPBIT").containsEntry("market", "KRW-BTC").containsEntry("side", "SELL")
            .containsEntry("exchange_order_id", "s-$runId").containsEntry("client_order_id", "ctb-$runId")
            .containsEntry("filled_volume", 0.002).containsEntry("filled_funds", 99_950.0).containsEntry("avg_fill_price", 49_975_000.0)
            .containsEntry("fee", 50.0).containsEntry("fee_basis", "MEASURED").containsEntry("fill_source", "ORDER")
            .containsEntry("decision_price", 50_000_000.0).containsEntry("requested_volume", null)
            .containsEntry("position_volume_after", 0.0).containsEntry("book_avg_price", 49_000_000.0)
            .containsEntry("pnl_percent", 1.5).containsEntry("pnl_amount", 1_500.0)
            .containsEntry("reason", "TAKE_PROFIT").containsEntry("strategy", "combined")
        assertThat(row["completed_at"]).isNotNull()
    }

    @Test
    fun `a fill whose state save conflicts leaves no orders row`() = runTest {
        val userId = insertUser("conflict")

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                service.commitFill({ throw TradingStateConflictException("KRW-BTC", "test") }, sellFill(userId))
            }
        }.isInstanceOf(TradingStateConflictException::class.java)

        assertThat(count("orders", userId)).isZero()
    }

    // orders 쓰기의 SQL 오류는 잡지 않는다 — 잡아도 Postgres 가 트랜잭션을 중단해 뒤의 알림 행이 실패한다. 체결 전체가 되돌아가 pending 이 남는다.
    @Test
    fun `an SQL error in the orders write rolls back the whole fill`() = runTest {
        val userId = insertUser("sqlerror")
        val market = "KRW-FAIL$runId"
        databaseClient.sql(
            "CREATE FUNCTION it_fail_orders_$runId() RETURNS trigger AS \$\$ BEGIN " +
                "IF NEW.market = '$market' THEN RAISE EXCEPTION 'it orders failure'; END IF; RETURN NEW; END \$\$ LANGUAGE plpgsql",
        ).fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("CREATE TRIGGER it_fail_orders_$runId BEFORE INSERT ON orders FOR EACH ROW EXECUTE FUNCTION it_fail_orders_$runId()")
            .fetch().rowsUpdated().awaitSingle()
        try {
            assertThatThrownBy {
                kotlinx.coroutines.runBlocking {
                    service.commitFill({ stateService.upsert(userId, TradingState(market)) }, sellFill(userId, market))
                }
            }.hasStackTraceContaining("it orders failure")
        } finally {
            databaseClient.sql("DROP TRIGGER it_fail_orders_$runId ON orders").fetch().rowsUpdated().awaitSingle()
            databaseClient.sql("DROP FUNCTION it_fail_orders_$runId()").fetch().rowsUpdated().awaitSingle()
        }

        for (table in listOf("orders", "trade_notification_outbox", "trading_states")) {
            assertThat(count(table, userId)).describedAs(table).isZero()
        }
    }

    private suspend fun existingOrder(userId: Long, status: String, exchange: String = "UPBIT", orderId: String? = "s-$runId", clientOrderId: String? = null) {
        databaseClient.sql(
            "INSERT INTO orders (user_id, exchange, market, side, exchange_order_id, client_order_id, status) VALUES (:u, :e, 'KRW-BTC', 'SELL', :o, :c, :s)",
        ).bind("u", userId).bind("e", exchange).bind("s", status)
            .let { if (orderId == null) it.bindNull("o", String::class.java) else it.bind("o", orderId) }
            .let { if (clientOrderId == null) it.bindNull("c", String::class.java) else it.bind("c", clientOrderId) }
            .fetch().rowsUpdated().awaitSingle()
    }

    // 재시작 뒤 같은 주문을 다시 확정하면 기록·알림 없이 건너뛰고 상태는 커밋한다(pending 해제).
    @Test
    fun `a fill whose order number already has a FILLED row on this exchange is skipped and only the state commits`() = runTest {
        val userId = insertUser("dupfilled")
        existingOrder(userId, "FILLED")

        assertThat(service.commitFill({ stateService.upsert(userId, TradingState("KRW-BTC")) }, sellFill(userId))).isFalse()

        assertThat(count("orders", userId)).isEqualTo(1)
        assertThat(count("trade_notification_outbox", userId)).isZero()
        assertThat(count("trading_states", userId)).isEqualTo(1)
    }

    @Test
    fun `the same order number on another exchange does not count as recorded`() = runTest {
        val userId = insertUser("otherexchange")
        existingOrder(userId, "FILLED", exchange = "KIS_PAPER")

        assertThat(service.commitFill({}, sellFill(userId))).isTrue()

        assertThat(count("orders", userId)).isEqualTo(2)
    }

    // 생애 줄을 FILLED 로 바꾸지 못하면(예상 밖) 체결 기록이 어디에도 남지 않으므로 상태 저장까지 되돌린다.
    @Test
    fun `a fill whose row update comes back empty rolls back the whole fill`() = runTest {
        val userId = insertUser("emptyupdate")
        existingOrder(userId, "OPEN")
        databaseClient.sql(
            "CREATE FUNCTION it_skip_fill_$runId() RETURNS trigger AS \$\$ BEGIN " +
                "IF NEW.user_id = $userId THEN RETURN NULL; END IF; RETURN NEW; END \$\$ LANGUAGE plpgsql",
        ).fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("CREATE TRIGGER it_skip_fill_$runId BEFORE UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION it_skip_fill_$runId()")
            .fetch().rowsUpdated().awaitSingle()
        try {
            assertThatThrownBy {
                kotlinx.coroutines.runBlocking { service.commitFill({ stateService.upsert(userId, TradingState("KRW-BTC")) }, sellFill(userId)) }
            }.hasStackTraceContaining("orders row not written")
        } finally {
            databaseClient.sql("DROP TRIGGER it_skip_fill_$runId ON orders").fetch().rowsUpdated().awaitSingle()
            databaseClient.sql("DROP FUNCTION it_skip_fill_$runId()").fetch().rowsUpdated().awaitSingle()
        }

        assertThat(orderRow(userId)["status"]).isEqualTo("OPEN")
        assertThat(count("trade_notification_outbox", userId)).isZero()
        assertThat(count("trading_states", userId)).isZero()
    }

    // 끝난 줄(취소·거절·안 나감)에 체결이 오면 체결이 사실이라 FILLED 로 바꾼다. 엔진에서는 생기지 않아 생기면 장부를 봐야 한다.
    @Test
    fun `a fill over a closed lifecycle row turns it into FILLED with the state`() = runTest {
        for (status in listOf("CANCELLED", "REJECTED", "NOT_PLACED")) {
            val userId = insertUser("closed-${status.lowercase()}")
            // 거절·안 나감은 주문번호가 없다 — 식별자로 찾아 주문번호를 채운다.
            if (status == "CANCELLED") existingOrder(userId, status) else existingOrder(userId, status, orderId = null, clientOrderId = "ctb-$runId")

            assertThat(service.commitFill({ stateService.upsert(userId, TradingState("KRW-BTC")) }, sellFill(userId))).describedAs(status).isTrue()

            assertThat(count("orders", userId)).describedAs(status).isEqualTo(1)
            assertThat(orderRow(userId)).describedAs(status).containsEntry("status", "FILLED").containsEntry("exchange_order_id", "s-$runId")
            assertThat(count("trade_notification_outbox", userId)).describedAs(status).isEqualTo(1)
            assertThat(count("trading_states", userId)).describedAs(status).isEqualTo(1)
        }
    }

    // 매수의 판단 가격·요청 수량은 생애 줄에만 있다. 체결이 값을 주지 않으면 덮지 않는다(화면은 읽지 않는다).
    @Test
    fun `a buy fill keeps the decision price and requested volume its lifecycle row carried`() = runTest {
        val userId = insertUser("keepdecision")
        databaseClient.sql(
            "INSERT INTO orders (user_id, exchange, market, side, client_order_id, status, decision_price, requested_volume) " +
                "VALUES (:u, 'UPBIT', 'KRW-BTC', 'BUY', :c, 'PENDING', 50000000, 0.002)",
        ).bind("u", userId).bind("c", "ctb-$runId").fetch().rowsUpdated().awaitSingle()
        val buy = sellFill(userId).copy(side = TradeSide.BUY, pnlPercent = null, pnlAmount = null, reason = null)

        assertThat(service.commitFill({}, buy)).isTrue()

        assertThat(orderRow(userId)).containsEntry("status", "FILLED").containsEntry("decision_price", 50_000_000.0).containsEntry("requested_volume", 0.002)
    }

    // 두 확인을 지난 뒤에도 넣기가 비면(동시 쓰기 같은 예상 밖) 체결 기록이 어디에도 남지 않으므로 상태 저장까지 되돌린다.
    @Test
    fun `a fill whose insert comes back empty rolls back the whole fill`() = runTest {
        val userId = insertUser("emptyinsert")
        val market = "KRW-SKIP$runId"
        databaseClient.sql(
            "CREATE FUNCTION it_skip_orders_$runId() RETURNS trigger AS \$\$ BEGIN " +
                "IF NEW.market = '$market' THEN RETURN NULL; END IF; RETURN NEW; END \$\$ LANGUAGE plpgsql",
        ).fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("CREATE TRIGGER it_skip_orders_$runId BEFORE INSERT ON orders FOR EACH ROW EXECUTE FUNCTION it_skip_orders_$runId()")
            .fetch().rowsUpdated().awaitSingle()
        try {
            assertThatThrownBy {
                kotlinx.coroutines.runBlocking {
                    service.commitFill({ stateService.upsert(userId, TradingState(market)) }, sellFill(userId, market))
                }
            }.hasStackTraceContaining("orders row not written")
        } finally {
            databaseClient.sql("DROP TRIGGER it_skip_orders_$runId ON orders").fetch().rowsUpdated().awaitSingle()
            databaseClient.sql("DROP FUNCTION it_skip_orders_$runId()").fetch().rowsUpdated().awaitSingle()
        }

        for (table in listOf("orders", "trade_notification_outbox", "trading_states")) {
            assertThat(count(table, userId)).describedAs(table).isZero()
        }
    }

    // 같은 ctb- 식별자는 같은 주문이다(주문마다 새로 만든다). 주문번호가 비었으면 그 줄이 이 주문의 기록이다.
    @Test
    fun `a fill whose client order id already has a FILLED row without an order number is skipped`() = runTest {
        val userId = insertUser("dupclient-same")
        existingOrder(userId, "FILLED", orderId = null, clientOrderId = "ctb-$runId")

        assertThat(service.commitFill({}, sellFill(userId))).isFalse()
        assertThat(count("orders", userId)).isEqualTo(1)
    }

    // 식별자만 같고 주문번호가 다른 줄은 다른 주문이다. 덮으면 그 주문의 번호를 바꾸고, 건너뛰면 이 체결이 화면에서 빠진다.
    // 되돌리면 그 종목이 확정에서 멈춰 손절이 돌지 않으므로 그 식별자 없이 기록한다.
    @Test
    fun `a fill whose client order id belongs to another order number is recorded without it`() = runTest {
        val userId = insertUser("dupclient")
        existingOrder(userId, "FILLED", orderId = "other-$runId", clientOrderId = "ctb-$runId")

        assertThat(service.commitFill({}, sellFill(userId))).isTrue()

        val withoutClientId = databaseClient.sql("SELECT client_order_id IS NULL AS missing FROM orders WHERE user_id = :u AND exchange_order_id = :o")
            .bind("u", userId).bind("o", "s-$runId").map { r -> r.get("missing", java.lang.Boolean::class.java)!!.booleanValue() }.all().collectList().awaitSingle()
        assertThat(withoutClientId).containsExactly(true)
        assertThat(count("trade_notification_outbox", userId)).isEqualTo(1)
    }

    // --- 생애 줄: 대기 → 접수 → 끝남, 한 주문에 한 줄, 앞으로만 ---

    // 생애 쓰기의 시한은 실제 시간이다. runTest 의 가상 시계는 DB 를 기다리는 동안 시한을 바로 넘긴다.
    private val ledger by lazy { OrderLedger(orderRows) }

    private fun lifecycleBuy(userId: Long, orderId: String? = null) = LifecycleOrder(
        userId, Exchange.UPBIT, "KRW-BTC", TradeSide.BUY, clientOrderId = "ctb-$runId", exchangeOrderId = orderId,
        decisionPrice = 50_000_000.0, requestedFunds = 100_000.0,
    )

    @Test
    fun `a buy's lifecycle keeps one row from pending through open to cancelled, with its order values`(): Unit = runBlocking {
        val userId = insertUser("lifecycle")

        ledger.pending(lifecycleBuy(userId))
        ledger.pending(lifecycleBuy(userId))
        assertThat(orderRow(userId)).containsEntry("status", "PENDING").containsEntry("exchange_order_id", null).containsEntry("completed_at", null)

        ledger.open(lifecycleBuy(userId, "b-$runId"))
        assertThat(orderRow(userId)).containsEntry("status", "OPEN").containsEntry("exchange_order_id", "b-$runId")

        ledger.closed(lifecycleBuy(userId, "b-$runId"), OrderLifecycle.Outcome.CANCELLED, exchangeState = "cancel")
        assertThat(count("orders", userId)).isEqualTo(1)
        val row = orderRow(userId)
        assertThat(row).containsEntry("status", "CANCELLED").containsEntry("exchange_state", "cancel")
            .containsEntry("decision_price", 50_000_000.0).containsEntry("requested_funds", 100_000.0)
        assertThat(row["completed_at"]).isNotNull()
    }

    // 접수 쓰기가 실패하면 대기 줄에 주문번호가 없다. 끝남은 식별자로 그 줄을 찾아 주문번호를 채운다.
    @Test
    fun `a pending row whose open write was lost is still closed by its identifier`(): Unit = runBlocking {
        val userId = insertUser("lostopen")
        ledger.pending(lifecycleBuy(userId))

        ledger.closed(lifecycleBuy(userId, "b-$runId"), OrderLifecycle.Outcome.CANCELLED)

        assertThat(count("orders", userId)).isEqualTo(1)
        assertThat(orderRow(userId)).containsEntry("status", "CANCELLED").containsEntry("exchange_order_id", "b-$runId")
    }

    // 매도는 대기 줄이 없고, 거절·안 나감은 줄이 없을 수 있다 — 그때는 그 상태로 넣는다.
    @Test
    fun `open and closed insert the row when no pending row exists`(): Unit = runBlocking {
        val opened = insertUser("openinsert")
        ledger.open(lifecycleBuy(opened, "b-$runId"))
        assertThat(orderRow(opened)).containsEntry("status", "OPEN").containsEntry("completed_at", null)

        val rejected = insertUser("closedinsert")
        ledger.closed(lifecycleBuy(rejected), OrderLifecycle.Outcome.REJECTED, note = "insufficient_funds_bid")
        val row = orderRow(rejected)
        assertThat(row).containsEntry("status", "REJECTED").containsEntry("note", "insufficient_funds_bid").containsEntry("exchange_order_id", null)
        assertThat(row["completed_at"]).isNotNull()
    }

    // 늦게 온 쓰기가 체결·끝난 줄을 되돌리거나 같은 주문의 줄을 하나 더 만들지 않는다.
    @Test
    fun `lifecycle writes never move a filled or closed row backwards or add a second row`(): Unit = runBlocking {
        val filled = insertUser("fwdfilled")
        existingOrder(filled, "FILLED", orderId = "b-$runId", clientOrderId = "ctb-$runId")
        ledger.open(lifecycleBuy(filled, "b-$runId"))
        ledger.closed(lifecycleBuy(filled, "b-$runId"), OrderLifecycle.Outcome.CANCELLED)
        ledger.pending(lifecycleBuy(filled))
        assertThat(count("orders", filled)).isEqualTo(1)
        assertThat(orderRow(filled)).containsEntry("status", "FILLED")

        val cancelled = insertUser("fwdclosed")
        existingOrder(cancelled, "CANCELLED", orderId = "b-$runId", clientOrderId = "ctb-$runId")
        ledger.open(lifecycleBuy(cancelled, "b-$runId"))
        ledger.closed(lifecycleBuy(cancelled, "b-$runId"), OrderLifecycle.Outcome.NOT_PLACED)
        assertThat(count("orders", cancelled)).isEqualTo(1)
        assertThat(orderRow(cancelled)).containsEntry("status", "CANCELLED")
    }

    // 손으로 옛 표를 맞추는 파일은 옛 표를 거쳐 들어온 체결만 옮긴다 — 이 이미지가 쓴 줄은 건드리지 않는다.
    @Test
    fun `the manual catch-up file leaves fills written only to orders untouched`() = runTest {
        val userId = insertUser("catchup")
        assertThat(service.commitFill({}, sellFill(userId))).isTrue()
        val before = databaseClient.sql("SELECT * FROM orders WHERE user_id = :u").bind("u", userId).fetch().all().collectList().awaitSingle()

        DriverManager.getConnection(jdbcUrl, user, password).use { it.update(RepoFiles.file(MANUAL_CATCHUP).readText()) }

        assertThat(databaseClient.sql("SELECT * FROM orders WHERE user_id = :u").bind("u", userId).fetch().all().collectList().awaitSingle())
            .isEqualTo(before)
        assertThat(count("orders", userId)).isEqualTo(1)
    }

    // 코드의 enum 이름이 V41 의 CHECK 목록에 없으면 그 값의 체결마다 기록 트랜잭션이 실패해 pending 이 남는다. 모든 조합을 실제로 넣어 본다.
    @Test
    fun `every fill source and fee kind the code writes passes the orders CHECK constraints`() = runTest {
        val userId = insertUser("enums")
        var n = 0L
        for (source in FillSource.entries) {
            for (kind in FeeKind.entries) {
                n++
                val id = orderRows.insertFilled(
                    userId = userId, exchange = "UPBIT", market = "KRW-BTC", side = TradeSide.entries[(n % 2).toInt()].name,
                    clientOrderId = null, exchangeOrderId = "e-$runId-$n", decisionPrice = null, filledVolume = null, filledFunds = null,
                    avgFillPrice = null, fee = null, feeBasis = kind.name, positionVolumeAfter = null, bookAvgPrice = null,
                    pnlPercent = null, pnlAmount = null, reason = null, strategy = null, fillSource = source.name,
                    requestedVolume = null, recordCreatedAt = java.time.LocalDateTime.now(),
                ).awaitSingle()
                assertThat(id).isNotNull()
            }
        }
        assertThat(count("orders", userId)).isEqualTo(n)
    }

    @Test
    fun `a fill confirmed from the balance leaves a row with unknown fee and volume marked as balance`() = runTest {
        val userId = insertUser("balance")
        val record = sellFill(userId).copy(
            fee = FeeBasis.Unrecorded, orderAmount = null, executedVwap = null, orderVolume = null, clientOrderId = null,
            fillSource = FillSource.BALANCE,
        )

        assertThat(service.commitFill({}, record)).isTrue()

        assertThat(orderRow(userId)).containsEntry("fee", null).containsEntry("fee_basis", "UNRECORDED")
            .containsEntry("fill_source", "BALANCE").containsEntry("filled_volume", null).containsEntry("filled_funds", null)
            .containsEntry("avg_fill_price", null).containsEntry("client_order_id", null)
    }

    // --- V41: 옛 체결 옮기기 ---

    @Test
    fun `V41 moves every trade execution into orders, takes order amount and id from its paired trade record, and marks legacy buys`() =
        withScratchSchema("v41") { schema ->
            migrate(schema, "40")
            val ids = inSchema(schema) { conn -> seedLegacyTrades(conn) }

            migrate(schema, "41")
            inSchema(schema) { conn ->
                val rows = conn.rows(
                    "SELECT trade_execution_id, trade_record_id, side, status, fill_source, exchange_order_id, filled_volume, filled_funds, " +
                        "avg_fill_price, fee, fee_basis, position_volume_after, book_avg_price, decision_price, requested_volume, " +
                        "pnl_percent, reason, strategy, client_order_id FROM orders ORDER BY trade_execution_id",
                )
                assertThat(rows.map { it["trade_execution_id"] }).containsExactlyElementsOf(ids.executions.map { it.toString() })
                assertThat(rows.map { it["status"] }.toSet()).containsExactly("FILLED")
                assertThat(rows.map { it["client_order_id"] }.toSet()).containsExactly(null)

                val snapshot = rows.single { it["trade_execution_id"] == ids.snapshotBuy.toString() }
                assertThat(snapshot).containsEntry("fill_source", "BACKFILL_SNAPSHOT")
                    .containsEntry("trade_record_id", ids.snapshotBuyRecord.toString())
                    .containsEntry("exchange_order_id", "o-snap")
                    .containsEntry("position_volume_after", "2").containsEntry("book_avg_price", "100")
                    .containsEntry("filled_volume", null).containsEntry("filled_funds", "150")
                    .containsEntry("fee", "0.075").containsEntry("fee_basis", "LEGACY")

                val manual = rows.single { it["trade_execution_id"] == ids.manualBuy.toString() }
                assertThat(manual).containsEntry("fill_source", "BACKFILL_MANUAL")
                    .containsEntry("filled_volume", "1").containsEntry("filled_funds", "90")
                    .containsEntry("position_volume_after", null).containsEntry("fee", null).containsEntry("exchange_order_id", null)

                val sold = rows.single { it["trade_execution_id"] == ids.sellWithAmount.toString() }
                assertThat(sold).containsEntry("fill_source", "BACKFILL")
                    .containsEntry("filled_volume", "2").containsEntry("requested_volume", null)
                    .containsEntry("decision_price", "110").containsEntry("filled_funds", "218").containsEntry("avg_fill_price", "109")
                    .containsEntry("pnl_percent", "9.5").containsEntry("reason", "TAKE_PROFIT").containsEntry("strategy", "combined")

                val soldOld = rows.single { it["trade_execution_id"] == ids.sellWithoutAmount.toString() }
                assertThat(soldOld).containsEntry("filled_funds", null).containsEntry("avg_fill_price", null)

                // trade_executions 만 있는 정정 줄은 옮기고, trade_records 만 있는 줄은 옮기지 않는다.
                assertThat(rows.single { it["trade_execution_id"] == ids.correctionOnly.toString() }).containsEntry("trade_record_id", null)
                assertThat(rows.map { it["trade_record_id"] }).doesNotContain(ids.recordOnly.toString())

                // 값이 같은 두 쌍은 id 순서대로 짝짓는다.
                assertThat(ids.twinExecutions.map { te -> rows.single { it["trade_execution_id"] == te.toString() }["trade_record_id"] })
                    .containsExactlyElementsOf(ids.twinRecords.map { it.toString() })

                // 같은 옮기기를 다시 돌려도 늘지 않는다(2-2 의 따라잡기가 이 부분을 다시 쓴다).
                conn.update(backfillSql())
                assertThat(conn.strings("SELECT count(*)::text FROM orders")).containsExactly(ids.executions.size.toString())
            }
        }

    @Test
    fun `the V41 down sql refuses unless V41 is last, then drops the table and its history row`() = withScratchSchema("v41down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V41_down.sql").readText()
        migrate(schema, "40")
        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V41 이 적용돼 있지 않다")

        migrate(schema, "41")
        inSchema(schema) { conn ->
            conn.update(downSql)
            assertThat(conn.strings("SELECT to_regclass('orders')::text")).containsExactly(null)
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '41'")).isEmpty()
        }
        migrate(schema, "41")
        inSchema(schema) { conn -> assertThat(conn.strings("SELECT to_regclass('orders')::text")).containsExactly("orders") }
    }

    // --- V42: 읽기 전환 준비 — 시각·값 맞추기, 빠진 체결 따라잡기, 알림 대기열 연결 ---

    @Test
    fun `V42 catches up fills written without an orders row, aligns times and missing values with trade records, and links notifications`() =
        withScratchSchema("v42") { schema ->
            migrate(schema, "40")
            val legacy = inSchema(schema) { conn -> seedLegacyTrades(conn) }
            migrate(schema, "41")
            val ids = inSchema(schema) { conn -> seedAfterV41(conn) }

            migrate(schema, "42")
            inSchema(schema) { conn -> assertCaughtUp(conn, legacy, ids) }
        }

    // --- V43: 읽기 전환 직전의 따라잡기 다시 돌리기 ---

    @Test
    fun `V43 catches up fills an image without orders wrote after V42, and the manual catch-up file changes nothing after it`() =
        withScratchSchema("v43") { schema ->
            migrate(schema, "40")
            val legacy = inSchema(schema) { conn -> seedLegacyTrades(conn) }
            migrate(schema, "42")
            // V42 아래 이미지로 손으로 롤백한 동안의 쓰기.
            val ids = inSchema(schema) { conn -> seedAfterV41(conn) }

            migrate(schema, "43")
            inSchema(schema) { conn ->
                assertCaughtUp(conn, legacy, ids)
                // 손 따라잡기 파일은 운영 JVM 과 같은 UTC 로 시각을 바꾼다 — Flyway(JVM 시간대)와 같을 때만 비교가 뜻이 있다.
                assumeTrue(ZoneId.systemDefault().normalized() == ZoneOffset.UTC, "JVM 시간대가 UTC 가 아니다")
                val before = conn.rows("SELECT * FROM orders ORDER BY id") to conn.rows("SELECT * FROM trade_notification_outbox ORDER BY id")
                conn.update(RepoFiles.file(MANUAL_CATCHUP).readText())
                assertThat(conn.rows("SELECT * FROM orders ORDER BY id") to conn.rows("SELECT * FROM trade_notification_outbox ORDER BY id"))
                    .isEqualTo(before)
            }
        }

    @Test
    fun `V42, V43 and the manual catch-up file run the same catch-up, and V43 and the manual file report the same counts`() {
        val blocks = listOf(V42, V43, MANUAL_CATCHUP).map { marked(it, "catchup") }
        assertThat(blocks[0]).contains("INSERT INTO orders")
        assertThat(blocks).containsOnly(blocks[0])
        assertThat(marked(MANUAL_CATCHUP, "report")).isEqualTo(marked(V43, "report").replace("'V43 orders:", "'orders catchup:"))
    }

    @Test
    fun `the V43 report counts filled rows without a time, without a trade record, or with empty history columns`() =
        withScratchSchema("v43report") { schema ->
            migrate(schema, "43")
            inSchema(schema) { conn ->
                conn.update("INSERT INTO users (username, password) VALUES ('it-report-$runId', 'x')")
                val userId = conn.strings("SELECT id::text FROM users WHERE username = 'it-report-$runId'").single()!!.toLong()
                conn.update(
                    "INSERT INTO orders (user_id, exchange, market, side, status, decision_price, filled_volume, fill_source, fee_basis) VALUES " +
                        "($userId, 'UPBIT', 'KRW-BTC', 'SELL', 'FILLED', 100, 1, 'ORDER', 'MEASURED'), " +
                        "($userId, 'UPBIT', 'KRW-BTC', 'BUY', 'OPEN', NULL, NULL, 'ORDER', 'MEASURED')",
                )
                conn.update(
                    "INSERT INTO orders (user_id, exchange, market, side, status, book_avg_price, fill_source, fee_basis, completed_at) VALUES " +
                        "($userId, 'UPBIT', 'KRW-BTC', 'BUY', 'FILLED', 100, 'ORDER', 'MEASURED', now())",
                )

                assertThat(conn.notices(marked(V43, "report"))).singleElement().asString()
                    .contains("filled without time=1,", "filled without record=2,", "incomplete history rows=2,")
            }
        }

    @Test
    fun `the V43 down sql refuses unless V43 is last, then removes only its history row`() = withScratchSchema("v43down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V43_down.sql").readText()
        migrate(schema, "42")
        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V43 이 적용돼 있지 않다")

        migrate(schema, "43")
        inSchema(schema) { conn ->
            conn.update(downSql)
            assertThat(conn.strings("SELECT max(version::int)::text FROM flyway_schema_history WHERE version IS NOT NULL")).containsExactly("42")
        }
    }

    // --- V44: 알림 대기열이 옛 체결 표 없이 ---

    @Test
    fun `after V44 a notification row needs no trade execution, and the previous image's fill statements still work`() =
        withScratchSchema("v44") { schema ->
            migrate(schema, "44")
            inSchema(schema) { conn ->
                conn.update("INSERT INTO users (username, password) VALUES ('it-v44-$runId', 'x')")
                val userId = conn.strings("SELECT id::text FROM users WHERE username = 'it-v44-$runId'").single()!!.toLong()
                val order = conn.strings(
                    "INSERT INTO orders (user_id, exchange, market, side, exchange_order_id, status, fill_source, fee_basis) " +
                        "VALUES ($userId, 'UPBIT', 'KRW-BTC', 'BUY', 'o-new', 'FILLED', 'ORDER', 'MEASURED') RETURNING id::text",
                ).single()!!.toLong()
                conn.update("INSERT INTO trade_notification_outbox (order_id, user_id, exchange, payload) VALUES ($order, $userId, 'UPBIT', '{}')")

                // 26bb5ad4 의 체결 쓰기: 옛 두 표 → orders(옛 id 와 함께) → 대기열(trade_execution_id 와 order_id).
                val te = conn.strings(
                    "INSERT INTO trade_executions (user_id, exchange, market, side, price, volume, total_amount, exchange_order_id) " +
                        "VALUES ($userId, 'UPBIT', 'KRW-BTC', 'SELL', 1, 1, 1, 'o-old') RETURNING id::text",
                ).single()!!.toLong()
                val tr = conn.strings(
                    "INSERT INTO trade_records (user_id, ticker, side, price, volume, total_amount) VALUES ($userId, 'KRW-BTC', 'SELL', 1, 1, 1) RETURNING id::text",
                ).single()!!.toLong()
                val oldOrder = conn.strings(
                    "INSERT INTO orders (user_id, exchange, market, side, exchange_order_id, status, fill_source, fee_basis, trade_execution_id, trade_record_id, completed_at) " +
                        "VALUES ($userId, 'UPBIT', 'KRW-BTC', 'SELL', 'o-old', 'FILLED', 'ORDER', 'MEASURED', $te, $tr, CAST('2026-11-23 12:00:00' AS timestamp)) " +
                        "ON CONFLICT DO NOTHING RETURNING id::text",
                ).single()!!.toLong()
                conn.update(
                    "INSERT INTO trade_notification_outbox (trade_execution_id, order_id, user_id, exchange, payload) VALUES ($te, $oldOrder, $userId, 'UPBIT', '{}')",
                )

                assertThat(conn.strings("SELECT (trade_execution_id IS NULL)::text FROM trade_notification_outbox ORDER BY id")).containsExactly("true", "false")
            }
        }

    @Test
    fun `the V44 down sql refuses while notification rows without a trade execution exist`() = withScratchSchema("v44down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V44_down.sql").readText()
        migrate(schema, "44")
        inSchema(schema) { conn ->
            conn.update("INSERT INTO users (username, password) VALUES ('it-v44down-$runId', 'x')")
            val userId = conn.strings("SELECT id::text FROM users WHERE username = 'it-v44down-$runId'").single()!!.toLong()
            conn.update("INSERT INTO trade_notification_outbox (user_id, exchange, payload) VALUES ($userId, 'UPBIT', '{}')")
        }
        // 멈춘 down 은 그 연결의 트랜잭션을 중단 상태로 남기므로 연결을 따로 쓴다.
        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("trade_execution_id 없는 알림 줄 1")

        inSchema(schema) { conn ->
            conn.update("DELETE FROM trade_notification_outbox")
            conn.update(downSql)
            assertThat(conn.strings("SELECT is_nullable FROM information_schema.columns WHERE table_schema = '$schema' AND table_name = 'trade_notification_outbox' AND column_name = 'trade_execution_id'"))
                .containsExactly("NO")
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '44'")).isEmpty()
        }
    }

    /** 옛 이미지가 orders 없이 쓴 체결은 따라잡고, 같은 거래소 주문번호 줄이 이미 있는 체결은 건너뛴다. 값·시각·알림 연결이 맞는다. */
    private fun assertCaughtUp(conn: Connection, legacy: LegacyIds, ids: AfterV41Ids) {
        assertThat(conn.strings("SELECT count(*)::text FROM orders WHERE trade_execution_id = ${ids.lateExecution}")).containsExactly("1")
        assertThat(conn.strings("SELECT count(*)::text FROM orders WHERE exchange_order_id = 'o-orph'")).containsExactly("1")
        assertThat(conn.strings("SELECT count(*)::text FROM orders WHERE trade_execution_id = ${ids.orphanExecution}")).containsExactly("0")

        // 연결된 모든 체결의 시각이 화면 기록과 같다.
        assertThat(
            conn.strings(
                "SELECT count(*)::text FROM orders o JOIN trade_records tr ON tr.id = o.trade_record_id " +
                    "WHERE o.completed_at IS DISTINCT FROM tr.created_at",
            ),
        ).containsExactly("0")
        assertThat(conn.strings("SELECT requested_volume::text FROM orders WHERE trade_execution_id = ${ids.balanceSellExecution}"))
            .containsExactly("0.7")
        assertThat(conn.strings("SELECT decision_price::text FROM orders WHERE trade_execution_id = ${legacy.manualBuy}"))
            .containsExactly("90")
        assertThat(
            conn.strings(
                "SELECT (n.order_id = o.id)::text FROM trade_notification_outbox n JOIN orders o ON o.trade_execution_id = n.trade_execution_id",
            ),
        ).containsExactly("true", "true")

        // 다시 돌려도 바뀌는 것이 없다.
        val before = conn.rows("SELECT * FROM orders ORDER BY id") to conn.rows("SELECT * FROM trade_notification_outbox ORDER BY id")
        conn.update(marked(V42, "catchup"))
        assertThat(conn.rows("SELECT * FROM orders ORDER BY id") to conn.rows("SELECT * FROM trade_notification_outbox ORDER BY id"))
            .isEqualTo(before)
    }

    @Test
    fun `the previous image's orders insert and an outbox row without order id still work on V42`() = withScratchSchema("v42compat") { schema ->
        migrate(schema, "42")
        inSchema(schema) { conn ->
            conn.update("INSERT INTO users (username, password) VALUES ('it-compat-$runId', 'x')")
            val userId = conn.strings("SELECT id::text FROM users WHERE username = 'it-compat-$runId'").single()!!.toLong()
            val te = conn.strings(
                "INSERT INTO trade_executions (user_id, exchange, market, side, price, volume, total_amount, exchange_order_id) " +
                    "VALUES ($userId, 'UPBIT', 'KRW-BTC', 'BUY', 1, 1, 1, 'o-compat') RETURNING id::text",
            ).single()!!.toLong()
            // 74fe47b4 의 OrderRowRepository.insertFilled 문(완료 시각 now()).
            conn.update(
                "INSERT INTO orders (user_id, exchange, market, side, client_order_id, exchange_order_id, status, decision_price, " +
                    "filled_volume, filled_funds, avg_fill_price, fee, fee_basis, position_volume_after, book_avg_price, " +
                    "pnl_percent, pnl_amount, reason, strategy, fill_source, trade_execution_id, trade_record_id, completed_at) VALUES (" +
                    "$userId, 'UPBIT', 'KRW-BTC', 'BUY', NULL, 'o-compat', 'FILLED', NULL, 1, 1, 1, NULL, 'UNRECORDED', 1, 1, " +
                    "NULL, NULL, NULL, 'combined', 'ORDER', $te, NULL, now()) ON CONFLICT DO NOTHING RETURNING id",
            )
            conn.update("INSERT INTO trade_notification_outbox (trade_execution_id, user_id, exchange, payload) VALUES ($te, $userId, 'UPBIT', '{}')")
            assertThat(conn.strings("SELECT count(*)::text FROM trade_notification_outbox WHERE order_id IS NULL")).containsExactly("1")
        }
    }

    @Test
    fun `the V42 down sql refuses unless V42 is last, then drops the notification link`() = withScratchSchema("v42down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V42_down.sql").readText()
        migrate(schema, "41")
        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V42 이 적용돼 있지 않다")

        migrate(schema, "42")
        inSchema(schema) { conn ->
            conn.update(downSql)
            assertThat(conn.strings("SELECT column_name FROM information_schema.columns WHERE table_schema = '$schema' AND column_name = 'order_id'")).isEmpty()
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '42'")).isEmpty()
        }
    }

    private class AfterV41Ids(val lateExecution: Long, val balanceSellExecution: Long, val orphanExecution: Long)

    /**
     * V41 뒤에 생긴 체결: orders 를 모르는 옛 이미지가 쓴 것, 74fe47b4 가 now() 시각으로 쓴 것(주문 응답 매도와 수량 없는 잔고 매도),
     * 알림 대기열, 같은 거래소 주문번호의 orders 줄이 이미 있는 것.
     */
    private fun seedAfterV41(conn: Connection): AfterV41Ids {
        val userId = conn.strings("SELECT id::text FROM users WHERE username = 'it-legacy-$runId'").single()!!.toLong()

        fun fill(side: String, price: Double, volume: Double, orderAmount: Double?, orderId: String, createdAt: String): Pair<Long, Long> {
            val tr = conn.strings(
                "INSERT INTO trade_records (user_id, ticker, side, price, volume, total_amount, strategy, order_amount, created_at) " +
                    "VALUES ($userId, 'KRW-BTC', '$side', $price, $volume, ${price * volume}, 'combined', $orderAmount, '$createdAt') RETURNING id::text",
            ).single()!!.toLong()
            val te = conn.strings(
                "INSERT INTO trade_executions (user_id, exchange, market, side, price, volume, total_amount, strategy, fee, exchange_order_id) " +
                    "VALUES ($userId, 'UPBIT', 'KRW-BTC', '$side', $price, $volume, ${price * volume}, 'combined', 0.1, '$orderId') RETURNING id::text",
            ).single()!!.toLong()
            return te to tr
        }

        val (late, _) = fill("SELL", 130.0, 1.0, 129.0, "o-late", "2026-11-23 10:00:00.123456")
        val (sold, soldRecord) = fill("SELL", 140.0, 2.0, 279.0, "o-new", "2026-11-23 11:00:00.654321")
        conn.update(
            "INSERT INTO orders (user_id, exchange, market, side, exchange_order_id, status, decision_price, filled_volume, filled_funds, " +
                "fill_source, fee_basis, trade_execution_id, trade_record_id, completed_at) VALUES " +
                "($userId, 'UPBIT', 'KRW-BTC', 'SELL', 'o-new', 'FILLED', 140, 2, 279, 'ORDER', 'MEASURED', $sold, $soldRecord, now())",
        )
        val (balance, balanceRecord) = fill("SELL", 150.0, 0.7, null, "o-bal", "2026-11-23 12:00:00.000001")
        conn.update(
            "INSERT INTO orders (user_id, exchange, market, side, exchange_order_id, status, decision_price, fill_source, fee_basis, " +
                "trade_execution_id, trade_record_id, completed_at) VALUES " +
                "($userId, 'UPBIT', 'KRW-BTC', 'SELL', 'o-bal', 'FILLED', 150, 'BALANCE', 'ESTIMATED', $balance, $balanceRecord, now())",
        )
        for (te in listOf(sold, balance)) {
            conn.update("INSERT INTO trade_notification_outbox (trade_execution_id, user_id, exchange, payload) VALUES ($te, $userId, 'UPBIT', '{}')")
        }
        val (orphan, _) = fill("BUY", 60.0, 1.0, 60.0, "o-orph", "2026-11-23 13:00:00")
        conn.update(
            "INSERT INTO orders (user_id, exchange, market, side, exchange_order_id, status) VALUES ($userId, 'UPBIT', 'KRW-BTC', 'BUY', 'o-orph', 'OPEN')",
        )
        return AfterV41Ids(late, balance, orphan)
    }

    /** SQL 파일의 `-- <label>:begin`·`-- <label>:end` 표시 사이를 그대로 쓴다. */
    private fun marked(path: String, label: String): String {
        val text = RepoFiles.file(path).readText()
        check(text.contains("-- $label:begin") && text.contains("-- $label:end")) { "$path 에 $label 표시가 없다" }
        return text.substringAfter("-- $label:begin").substringBefore("-- $label:end")
    }

    private class LegacyIds(
        val snapshotBuy: Long,
        val snapshotBuyRecord: Long,
        val manualBuy: Long,
        val sellWithAmount: Long,
        val sellWithoutAmount: Long,
        val correctionOnly: Long,
        val recordOnly: Long,
        val twinExecutions: List<Long>,
        val twinRecords: List<Long>,
    ) {
        val executions get() = (listOf(snapshotBuy, manualBuy, sellWithAmount, sellWithoutAmount, correctionOnly) + twinExecutions).sorted()
    }

    /** 운영에 있던 모양의 옛 기록을 심는다. trade_records 와 trade_executions 는 같은 체결을 같은 값으로 두 번 적었다. */
    private fun seedLegacyTrades(conn: Connection): LegacyIds {
        conn.update("INSERT INTO users (username, password) VALUES ('it-legacy-$runId', 'x')")
        val userId = conn.strings("SELECT id::text FROM users WHERE username = 'it-legacy-$runId'").single()!!.toLong()

        fun record(side: String, price: Double, volume: Double, total: Double, strategy: String?, orderAmount: Double?, pnl: Double? = null, reason: String? = null): Long =
            conn.strings(
                "INSERT INTO trade_records (user_id, ticker, side, price, volume, total_amount, strategy, order_amount, pnl_percent, reason) " +
                    "VALUES ($userId, 'KRW-BTC', '$side', $price, $volume, $total, ${strategy?.let { "'$it'" }}, $orderAmount, $pnl, ${reason?.let { "'$it'" }}) RETURNING id::text",
            ).single()!!.toLong()

        fun execution(side: String, price: Double, volume: Double, total: Double, strategy: String?, fee: Double, orderId: String?, pnl: Double? = null, reason: String? = null): Long =
            conn.strings(
                "INSERT INTO trade_executions (user_id, exchange, market, side, price, volume, total_amount, strategy, fee, exchange_order_id, pnl_percent, reason) " +
                    "VALUES ($userId, 'UPBIT', 'KRW-BTC', '$side', $price, $volume, $total, ${strategy?.let { "'$it'" }}, $fee, ${orderId?.let { "'$it'" }}, $pnl, ${reason?.let { "'$it'" }}) RETURNING id::text",
            ).single()!!.toLong()

        val snapRecord = record("BUY", 100.0, 2.0, 200.0, "combined", 150.0)
        val snap = execution("BUY", 100.0, 2.0, 200.0, "combined", 0.075, "o-snap")
        record("BUY", 90.0, 1.0, 90.0, "manual", null)
        val manual = execution("BUY", 90.0, 1.0, 90.0, "manual", 0.0, null)
        record("SELL", 110.0, 2.0, 220.0, "combined", 218.0, 9.5, "TAKE_PROFIT")
        val sold = execution("SELL", 110.0, 2.0, 220.0, "combined", 0.11, "o-sell", 9.5, "TAKE_PROFIT")
        record("SELL", 95.0, 1.0, 95.0, "combined", null, -5.2, "STOP_LOSS")
        val soldOld = execution("SELL", 95.0, 1.0, 95.0, "combined", 0.0, null, -5.2, "STOP_LOSS")
        val correction = execution("SELL", 120.0, 0.5, 60.0, "combined", 0.03, "o-fix")
        val recordOnly = record("BUY", 80.0, 3.0, 240.0, "combined", 240.0)
        val twinRecords = listOf(record("BUY", 70.0, 1.0, 70.0, "combined", 70.0), record("BUY", 70.0, 1.0, 70.0, "combined", 70.0))
        val twins = listOf(execution("BUY", 70.0, 1.0, 70.0, "combined", 0.035, "o-t1"), execution("BUY", 70.0, 1.0, 70.0, "combined", 0.035, "o-t2"))
        return LegacyIds(snap, snapRecord, manual, sold, soldOld, correction, recordOnly, twins, twinRecords)
    }

    /** V41 의 옛 체결 옮기기 부분. 마이그레이션 파일의 표시 사이를 그대로 쓴다. */
    private fun backfillSql(): String =
        RepoFiles.file(V41).readText().substringAfter("-- backfill:begin").substringBefore("-- backfill:end")

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

    /** 문이 RAISE NOTICE 로 남긴 메시지들. */
    private fun Connection.notices(sql: String): List<String> =
        createStatement().use { st ->
            st.execute(sql)
            generateSequence(st.warnings) { it.nextWarning }.mapNotNull { it.message }.toList()
        }

    private fun Connection.strings(sql: String): List<String?> =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    private fun Connection.rows(sql: String): List<Map<String, String?>> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
                buildList { while (rs.next()) add(columns.associateWith { rs.getString(it) }) }
            }
        }

    private fun withScratchSchema(label: String, block: (String) -> Unit) {
        val schema = "it_${label}_$runId".replace('-', '_')
        try {
            block(schema)
        } finally {
            DriverManager.getConnection(jdbcUrl, user, password).use { it.update("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }
}
