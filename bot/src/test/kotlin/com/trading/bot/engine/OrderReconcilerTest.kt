package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.client.UpbitApiException
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Order
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.OrderRowRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.bot.persistence.entity.OrderRowEntity
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.domain.Exchange
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class OrderReconcilerTest {

    private val now = Instant.parse("2026-11-13T01:00:00Z")
    private val botStates = mockk<BotStateRepository>()
    private val users = mockk<UserRepository>()
    private val secrets = mockk<UserSecretsService>()
    private val client = mockk<UpbitClient>()
    private val states = mockk<TradingStateService>()
    private val orders = mockk<OrderRowRepository>()
    private val reconciler = OrderReconciler(botStates, users, secrets, { client }, states, orders, Clock.fixed(now, ZoneOffset.UTC))

    private val logger = LoggerFactory.getLogger(OrderReconciler::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setup() {
        logger.addAppender(logs)
        every { botStates.findByExchange("UPBIT") } returns Flux.just(BotStateEntity(userId = 1L, exchange = "UPBIT", running = false))
        val user = UserEntity(id = 1L, username = "u", password = "x", upbitAccessKey = "enc-a", upbitSecretKey = "enc-s")
        every { users.findById(1L) } returns Mono.just(user)
        every { secrets.decryptUserSecrets(any()) } answers { firstArg() }
        coEvery { states.loadStates(1L, Exchange.UPBIT) } returns emptyMap()
        coEvery { client.getClosedOrders("cancel", any(), any()) } returns emptyList()
        every { orders.findStaleUnfinished(1L, "UPBIT", any()) } returns Flux.empty()
        every { orders.closeStale(any(), any(), any(), any()) } answers { Mono.just(firstArg()) }
        every { orders.markUnresolved(any(), any()) } answers { Mono.just(firstArg()) }
    }

    @AfterEach
    fun teardown() = logger.detachAppender(logs).let { }

    private fun order(uuid: String, identifier: String? = "ctb-$uuid", executed: String? = "0.1") =
        Order(uuid = uuid, identifier = identifier, side = "ask", market = "KRW-BTC", state = "done", executedVolume = executed, createdAt = "2026-11-12T10:00:00+09:00")

    private fun logged(level: Level) = logs.list.filter { it.level == level }.map { it.formattedMessage }

    private fun recorded(vararg uuids: String) {
        every { orders.findRecordedOrderIds(1L, "UPBIT", any()) } returns Flux.fromArray(uuids)
    }

    @Test
    fun `a filled bot order without a trade record is reported once, within the settled window`() = runBlocking {
        coEvery { client.getClosedOrders("done", any(), any()) } returns listOf(order("o-1"), order("o-2"))
        recorded("o-2")

        reconciler.reconcileAll()
        reconciler.reconcileAll()

        val errors = logged(Level.ERROR)
        assertEquals(1, errors.size, errors.toString())
        assertTrue("1건" in errors.single() && "KRW-BTC 매도 0.1 2026-11-12T10:00:00+09:00 o-1" in errors.single() && "o-2" !in errors.single(), errors.single())
        coVerify { client.getClosedOrders("done", now.minusSeconds(48 * 3600), now.minusSeconds(3600)) }
    }

    // 소유자가 앱에서 낸 주문(다른 접두·identifier 없음)과 체결되지 않은 주문은 보지 않는다.
    @Test
    fun `owner orders, unfilled orders and pending orders are not candidates, and all recorded means one info line`() = runBlocking {
        coEvery { client.getClosedOrders("done", any(), any()) } returns
            listOf(order("o-1", identifier = null), order("o-2", identifier = "app-x"), order("o-3", executed = "0"), order("o-4", executed = null), order("o-5"), order("o-6"))
        coEvery { states.loadStates(1L, Exchange.UPBIT) } returns mapOf("KRW-BTC" to TradingState("KRW-BTC", pendingSellUuid = "o-5"))
        recorded("o-6")

        reconciler.reconcileAll()

        assertEquals(emptyList<String>(), logged(Level.ERROR))
        assertEquals(1, logged(Level.INFO).count { "all recorded" in it }, logged(Level.INFO).toString())
    }

    // 기록부터 읽으면, 그 사이 끝난 매도(기록과 pending 해제가 한 트랜잭션)가 기록 없음으로 보인다.
    @Test
    fun `the exchange is read before pending and pending before trade records`() = runBlocking {
        coEvery { client.getClosedOrders("done", any(), any()) } returns listOf(order("o-1"))
        recorded("o-1")

        reconciler.reconcileAll()

        coVerifyOrder {
            client.getClosedOrders("done", any(), any())
            states.loadStates(1L, Exchange.UPBIT)
            orders.findRecordedOrderIds(1L, "UPBIT", any())
        }
    }

    @Test
    fun `only the first eight unrecorded fills are listed, with the rest counted`() = runBlocking {
        coEvery { client.getClosedOrders("done", any(), any()) } returns (1..11).map { order("o-$it") }
        recorded()

        reconciler.reconcileAll()

        val error = logged(Level.ERROR).single()
        assertTrue("11건" in error && "o-8 외 3건" in error && "o-9" !in error, error)
        assertTrue(logged(Level.WARN).single().contains("o-11"), "나머지 uuid 는 로그 파일에 남는다")
    }

    // 한 사용자의 조회 실패는 다음 사용자를 막지 않는다. 모두 실패면 대조가 꺼진 것이라 ERROR 다.
    @Test
    fun `a failed lookup skips that user with a warning, and a run where every user failed raises one error`() = runBlocking {
        every { botStates.findByExchange("UPBIT") } returns Flux.just(BotStateEntity(userId = 1L, exchange = "UPBIT"))
        coEvery { client.getClosedOrders("done", any(), any()) } throws IllegalStateException("500")

        reconciler.reconcileAll()

        assertEquals(1, logged(Level.WARN).size)
        assertEquals(1, logged(Level.ERROR).count { "모두 실패" in it }, logged(Level.ERROR).toString())
    }

    private fun row(id: Long, status: String = "PENDING", identifier: String? = "ctb-$id", uuid: String? = null, age: Long = 2 * 3600, note: String? = null) =
        OrderRowEntity(
            id = id, userId = 1L, exchange = "UPBIT", market = "KRW-BTC", side = "BUY", clientOrderId = identifier, exchangeOrderId = uuid,
            status = status, note = note, createdAt = now.minusSeconds(age),
        )

    private fun stale(vararg rows: OrderRowEntity) {
        coEvery { client.getClosedOrders("done", any(), any()) } returns emptyList()
        every { orders.findStaleUnfinished(1L, "UPBIT", 50) } returns Flux.fromArray(rows)
    }

    private fun exchange(identifier: String, state: String, executed: String = "0", uuid: String = "u-$identifier") {
        coEvery { client.getOrderByIdentifier(identifier) } returns
            Order(uuid = uuid, identifier = identifier, side = "bid", market = "KRW-BTC", state = state, executedVolume = executed)
    }

    // 분명한 증거로만 닫는다 — 취소·체결 0, 최근 줄의 식별자 404. 나머지는 그대로 두고 본 것만 남긴다(다음 날 뒤로 밀린다).
    @Test
    fun `stale rows are closed only on clear evidence and the rest are noted`() = runBlocking {
        stale(
            row(1, status = "OPEN", uuid = "u-ctb-1"), row(2), row(3), row(4, age = 8L * 24 * 3600), row(5), row(6, identifier = null, uuid = "u-6"),
            row(7, status = "OPEN", uuid = "u-7"),
        )
        exchange("ctb-1", "cancel")
        exchange("ctb-2", "done")
        coEvery { client.getOrderByIdentifier("ctb-3") } returns null
        coEvery { client.getOrderByIdentifier("ctb-4") } returns null
        exchange("ctb-5", "wait")
        // 주문번호가 있는 줄은 거래소가 받은 주문이다 — 식별자로 안 보여도 접수 안 됨으로 닫지 않는다.
        coEvery { client.getOrderByIdentifier("ctb-7") } returns null

        reconciler.reconcileAll()

        verify { orders.closeStale(1L, "CANCELLED", "cancel", "reconcile (was OPEN): cancel without a fill") }
        verify { orders.closeStale(2L, "CANCELLED", "done", "reconcile (was PENDING): done without a fill") }
        verify { orders.closeStale(3L, "NOT_PLACED", null, "reconcile (was PENDING): identifier not found") }
        verify { orders.markUnresolved(4L, "reconcile: identifier not found, too old to conclude") }
        verify { orders.markUnresolved(5L, "reconcile: exchange state wait") }
        verify { orders.markUnresolved(6L, "reconcile: no identifier") }
        verify { orders.markUnresolved(7L, "reconcile: identifier not found but the exchange accepted the order") }
        coVerify(exactly = 0) { client.getOrder(any()) }
        assertEquals(emptyList<String>(), logged(Level.ERROR))
    }

    // 매수 주문을 받아들이면 엔진은 식별자를 지우고 uuid 만 든다 — 줄에는 식별자만 남아도 조회로 받은 uuid 로 엔진 것임을 안다.
    @Test
    fun `rows the engine still holds are skipped, by the row keys or by the uuid the lookup returns`() = runBlocking {
        stale(row(1), row(2, identifier = "ctb-2", uuid = "u-held"), row(3))
        coEvery { states.loadStates(1L, Exchange.UPBIT) } returns mapOf(
            "KRW-BTC" to TradingState("KRW-BTC", pendingBuyUuid = "u-ctb-1"),
            "KRW-ETH" to TradingState("KRW-ETH", pendingSellUuid = "u-held"),
        )
        exchange("ctb-1", "cancel")
        exchange("ctb-3", "cancel")

        reconciler.reconcileAll()

        verify(exactly = 0) { orders.closeStale(1L, any(), any(), any()) }
        coVerify(exactly = 0) { client.getOrderByIdentifier("ctb-2") }
        verify(exactly = 1) { orders.closeStale(3L, "CANCELLED", any(), any()) }
        verify(exactly = 0) { orders.markUnresolved(any(), any()) }
    }

    // 잔고 복구 경로는 식별자 없이 새 FILLED 줄을 넣어, 대기 줄이 따로 남는다. 기록은 있으니 알리지 않는다.
    @Test
    fun `a filled order is alerted once with its row id unless another row recorded the fill`() = runBlocking {
        stale(row(1), row(2), row(3, note = "reconcile: filled, no record u-ctb-3"))
        exchange("ctb-1", "done", executed = "0.1")
        exchange("ctb-2", "done", executed = "0.1")
        exchange("ctb-3", "done", executed = "0.1")
        every { orders.findByExchangeOrderId(1L, "UPBIT", "u-ctb-1") } returns Mono.empty()
        every { orders.findByExchangeOrderId(1L, "UPBIT", "u-ctb-2") } returns
            Mono.just(row(9, status = "FILLED", identifier = null, uuid = "u-ctb-2"))
        every { orders.findByExchangeOrderId(1L, "UPBIT", "u-ctb-3") } returns Mono.empty()

        reconciler.reconcileAll()
        reconciler.reconcileAll()

        val errors = logged(Level.ERROR)
        assertEquals(1, errors.size, errors.toString())
        assertTrue("id=1" in errors.single() && "u-ctb-1" in errors.single(), errors.single())
        verify { orders.markUnresolved(1L, "reconcile: filled, no record u-ctb-1") }
        verify { orders.markUnresolved(2L, "reconcile: fill recorded as order 9") }
        verify(exactly = 0) { orders.closeStale(any(), any(), any(), any()) }
    }

    // 키가 만료됐으면 줄마다 같은 실패를 내지 않는다. 오래된 줄 점검의 실패는 체결 대조를 막지 않고 사용자 실패로도 세지 않는다.
    @Test
    fun `a failed lookup moves that row back and stops the stale check for that user without touching the fill check`() = runBlocking {
        stale(row(1), row(2))
        coEvery { client.getOrderByIdentifier("ctb-1") } throws IllegalStateException("500")
        exchange("ctb-2", "cancel")

        reconciler.reconcileAll()

        coVerify(exactly = 0) { client.getOrderByIdentifier("ctb-2") }
        verify { orders.markUnresolved(1L, "reconcile: lookup failed (IllegalStateException)") }
        assertEquals(1, logged(Level.WARN).count { "stopped at order row 1" in it }, logged(Level.WARN).toString())
        assertEquals(emptyList<String>(), logged(Level.ERROR))
        assertEquals(1, logged(Level.INFO).count { "no bot fills to check" in it })
    }

    // 이미 알린 미기록 체결의 표시가 조회 실패로 지워지면 재시작 뒤 다시 알린다.
    @Test
    fun `a failed lookup keeps the note of an already alerted fill`() = runBlocking {
        stale(row(1, note = "reconcile: filled, no record u-ctb-1"))
        coEvery { client.getOrderByIdentifier("ctb-1") } throws IllegalStateException("500")

        reconciler.reconcileAll()

        verify { orders.markUnresolved(1L, "reconcile: filled, no record u-ctb-1") }
    }

    // 키가 거절됐으면 줄 조회도 거절되고, 그 조회는 인증 오류를 ERROR(Discord)로 남긴다 — 매일 울리지 않게 건너뛴다.
    @Test
    fun `a rejected key skips the stale check`() = runBlocking {
        coEvery { client.getClosedOrders("done", any(), any()) } throws UpbitApiException(401, "expired_access_key", "expired", "{}")
        every { orders.findStaleUnfinished(1L, "UPBIT", 50) } returns Flux.just(row(1))

        reconciler.reconcileAll()

        coVerify(exactly = 0) { client.getOrderByIdentifier(any()) }
        assertEquals(1, logged(Level.WARN).count { "key was rejected" in it }, logged(Level.WARN).toString())
    }

    @Test
    fun `a cancelled run is not counted as a failed user`() {
        coEvery { client.getClosedOrders("done", any(), any()) } throws CancellationException("shutdown")

        assertThrows(CancellationException::class.java) { runBlocking { reconciler.reconcileAll() } }
        coVerify(exactly = 0) { orders.findStaleUnfinished(any(), any(), any()) }
    }

    @Test
    fun `a failed fill check still runs the stale check and still counts as a failed user`() = runBlocking {
        coEvery { client.getClosedOrders("done", any(), any()) } throws IllegalStateException("500")
        every { orders.findStaleUnfinished(1L, "UPBIT", 50) } returns Flux.just(row(1))
        exchange("ctb-1", "cancel")

        reconciler.reconcileAll()

        verify { orders.closeStale(1L, "CANCELLED", any(), any()) }
        assertEquals(1, logged(Level.ERROR).count { "모두 실패" in it }, logged(Level.ERROR).toString())
    }

    @Test
    fun `the next run is at 09-05 KST, today if it is still ahead and tomorrow otherwise`() {
        assertEquals(java.time.Duration.ofMinutes(1), untilNextDailyRun(Instant.parse("2026-11-19T00:04:00Z")))
        assertEquals(java.time.Duration.ofHours(24), untilNextDailyRun(Instant.parse("2026-11-19T00:05:00Z")))
        assertEquals(java.time.Duration.ofHours(9).plusMinutes(5), untilNextDailyRun(Instant.parse("2026-11-19T15:00:00Z")))
        assertEquals(
            java.time.Duration.ofHours(24).minusSeconds(1),
            untilNextDailyRun(Instant.parse("2026-11-19T00:05:01Z")),
        )
    }

    @Test
    fun `starting the reconciler waits for the next run instead of running at once`() = runBlocking {
        val runs = java.util.concurrent.atomic.AtomicInteger()
        every { botStates.findByExchange("UPBIT") } answers { runs.incrementAndGet(); Flux.empty() }
        val asked = mutableListOf<Instant>()
        val waiting = OrderReconciler(
            botStates, users, secrets, { client }, states, orders, Clock.fixed(now, ZoneOffset.UTC),
            OrderReconciler.Timings(untilNextRun = { asked += it; java.time.Duration.ofHours(1) }),
        )

        waiting.start()
        kotlinx.coroutines.delay(100)
        waiting.stop()

        assertEquals(0, runs.get(), "기동 직후에는 대조하지 않는다")
        assertEquals(listOf(now), asked)
    }

    @Test
    fun `the loop keeps running after a failed run and stops when the bean is destroyed`() = runBlocking {
        val runs = java.util.concurrent.atomic.AtomicInteger()
        every { botStates.findByExchange("UPBIT") } answers {
            if (runs.incrementAndGet() == 1) throw IllegalStateException("db down") else Flux.empty()
        }
        val looping = OrderReconciler(
            botStates, users, secrets, { client }, states, orders, Clock.fixed(now, ZoneOffset.UTC),
            OrderReconciler.Timings(untilNextRun = { java.time.Duration.ofMillis(20) }),
        )

        looping.start()
        kotlinx.coroutines.withTimeout(5_000) { while (runs.get() < 3) kotlinx.coroutines.delay(5) }
        looping.stop()
        val stoppedAt = runs.get()
        kotlinx.coroutines.delay(100)

        // 취소는 다음 중단점에서 보인다 — 이미 시작한 한 번은 끝날 수 있다.
        assertTrue(runs.get() <= stoppedAt + 1, "멈춘 뒤에는 돌지 않는다")
        assertEquals(1, logged(Level.WARN).count { "trying again" in it })
    }

    @Test
    fun `a user without both keys is skipped with an info line and not counted, and a decrypt failure is skipped with a warning while the next user still runs`() = runBlocking {
        every { botStates.findByExchange("UPBIT") } returns
            Flux.just(BotStateEntity(userId = 2L, exchange = "UPBIT"), BotStateEntity(userId = 3L, exchange = "UPBIT"), BotStateEntity(userId = 1L, exchange = "UPBIT"))
        every { users.findById(2L) } returns Mono.just(UserEntity(id = 2L, username = "nokey", password = "x", upbitAccessKey = "a"))
        every { users.findById(3L) } returns Mono.just(UserEntity(id = 3L, username = "bad", password = "x", upbitAccessKey = "a", upbitSecretKey = "s"))
        every { secrets.decryptUserSecrets(match { it.id == 3L }) } throws IllegalStateException("decrypt failed")
        coEvery { client.getClosedOrders("done", any(), any()) } returns listOf(order("o-1"))
        recorded()

        reconciler.reconcileAll()

        assertEquals(1, logged(Level.WARN).size, logged(Level.WARN).toString())
        assertEquals(1, logged(Level.ERROR).size)
        assertEquals(1, logged(Level.INFO).count { "no Upbit keys" in it })
    }

    // 키 없는 사용자는 시도가 아니다 — 그 사용자가 있어도 시도한 사용자가 모두 실패면 ERROR 다.
    @Test
    fun `keyless users do not hide a run where every attempted user failed`() = runBlocking {
        every { botStates.findByExchange("UPBIT") } returns Flux.just(BotStateEntity(userId = 2L, exchange = "UPBIT"), BotStateEntity(userId = 1L, exchange = "UPBIT"))
        every { users.findById(2L) } returns Mono.just(UserEntity(id = 2L, username = "nokey", password = "x"))
        coEvery { client.getClosedOrders("done", any(), any()) } throws IllegalStateException("401")

        reconciler.reconcileAll()

        assertEquals(1, logged(Level.ERROR).count { "모두 실패" in it }, logged(Level.ERROR).toString())
    }
}
