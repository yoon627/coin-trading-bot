package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.client.UpbitApiException
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.OrderTrade
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * 주문 전송 결과가 불명확한 경우(#227). 응답을 못 받은 주문을 "미전송"으로 보면 매수는 다음 tick 에 한 번 더 나가고,
 * 매도는 청산 기록 없이 사라진다. identifier 를 주문 전에 durable 로 남기고, 결과를 identifier 조회로 확정한다.
 */
class PositionManagerUnknownOrderTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val clock = MutableClock(Instant.parse("2026-09-28T00:00:00Z"))
    private val manager = PositionManager(upbit, TradingProperties(maxInvestAmount = 100_000.0), stateService, 1L, clock = clock)

    /** upsert 가 호출된 시점의 스냅샷 — 인자는 계속 바뀌는 같은 객체라 호출 순간을 복사해 둔다. */
    private val saved = mutableListOf<TradingState>()
    private val sent = mutableListOf<OrderRequest>()

    private val logger = LoggerFactory.getLogger(PositionManager::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setup() {
        coEvery { stateService.upsert(any(), any()) } coAnswers { saved += secondArg<TradingState>().copy() }
        logger.addAppender(logs)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(logs)
    }

    private fun errors() = logs.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }

    /** 잔고 흔적 알림만 — 경과시간 알림 등 다른 ERROR 에도 identifier 가 찍힌다. */
    private fun traceAlerts(identifier: String) = errors().count { identifier in it && "자동 처리하지 않습니다" in it }

    private fun krw(balance: String = "1000000") = Account(currency = "KRW", balance = balance)

    private fun btc(balance: String, avg: String = "50000000") = Account(currency = "BTC", balance = balance, avgBuyPrice = avg)

    private fun placeOrderAnswers(block: suspend (OrderRequest) -> Order) {
        coEvery { upbit.placeOrder(any()) } coAnswers { firstArg<OrderRequest>().also { sent += it }.let { block(it) } }
    }

    private fun filled(uuid: String, volume: String) = Order(
        uuid = uuid, state = "done", executedVolume = volume, paidFee = "50",
        trades = listOf(OrderTrade(price = "50000000", volume = volume, funds = "100000")),
    )

    private fun advance(seconds: Long) {
        clock.now = clock.now.plus(Duration.ofSeconds(seconds))
    }

    // --- 매수 ---

    @Test
    fun `an unanswered buy keeps the identifier that was recorded before sending`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        var durableAtSend: String? = null
        placeOrderAnswers {
            durableAtSend = saved.lastOrNull()?.pendingBuyIdentifier
            throw RuntimeException("ReadTimeoutException")
        }
        val state = TradingState("KRW-BTC")

        assertNull(manager.buy("KRW-BTC", state, 50_000_000.0, "test"))

        val identifier = sent.single().identifier
        assertNotNull(identifier)
        assertEquals(identifier, durableAtSend) // 보내기 전에 이미 durable 이었다
        assertEquals(identifier, state.pendingBuyIdentifier)
        assertNull(state.pendingBuyUuid)
        assertEquals("test", state.pendingBuyStrategy)
    }

    @Test
    fun `the next tick does not buy again and confirms the order found by identifier`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        val identifier = state.pendingBuyIdentifier!!

        assertNull(manager.buy("KRW-BTC", state, 50_000_000.0, "test"))
        assertEquals(1, sent.size)

        coEvery { upbit.getOrderByIdentifier(identifier) } returns filled("u-1", "0.002")
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))
        val record = manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertEquals("u-1", record?.exchangeOrderId)
        assertTrue(state.position)
        assertFalse(state.hasPendingBuy())
    }

    @Test
    fun `a buy the exchange never saw is released after two misses and sixty seconds`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        val identifier = state.pendingBuyIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(10)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(identifier, state.pendingBuyIdentifier) // 두 번 못 찾았지만 아직 60초 전

        advance(60)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertFalse(state.hasPendingBuy())
        assertNull(saved.last().pendingBuyIdentifier) // 해제가 durable 에도 내려갔다

        placeOrderAnswers { Order(uuid = "u-2") }
        coEvery { upbit.getOrder("u-2") } returns Order(uuid = "u-2", state = "cancel")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        assertNotEquals(identifier, sent.last().identifier) // identifier 는 다시 쓰지 않는다
    }

    @Test
    fun `a missing buy with new coins is neither recorded nor released and alerts once`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        val identifier = state.pendingBuyIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002")) // 주문 전엔 없던 코인
        logs.list.clear()

        repeat(3) {
            assertNull(manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0))
            advance(70)
        }
        // 흔적이 사라져도(그 사이 누가 팔았을 수 있다) 이 주문과 구분할 수 없으니 풀지 않는다.
        coEvery { upbit.getAccounts() } returns listOf(krw())
        repeat(3) {
            manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
            advance(70)
        }

        assertEquals(identifier, state.pendingBuyIdentifier)
        assertFalse(state.position)
        assertEquals(1, sent.size)
        assertEquals(1, traceAlerts(identifier))
    }

    @Test
    fun `a miss is counted only when both the order lookup and the balance were seen`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        val identifier = state.pendingBuyIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        // 조회 실패가 끼면 처음부터 다시 센다 — 첫 404 로부터 80초가 지났어도 끊김 없는 404 는 1회뿐이다.
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(70)
        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("order lookup down")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(1, state.reconcileFailureCount)
        advance(10)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(identifier, state.pendingBuyIdentifier)

        // 잔고를 못 본 경우도 같다.
        advance(70)
        coEvery { upbit.getAccounts() } throws RuntimeException("accounts down")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(10)
        coEvery { upbit.getAccounts() } returns listOf(krw())
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(identifier, state.pendingBuyIdentifier)
        // 간헐 장애는 404 사이에서도 halt 카운터에 쌓인다 — 되돌리면 해제도 halt 도 없이 매매가 멈춘다.
        assertEquals(2, state.reconcileFailureCount)

        advance(70)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertFalse(state.hasPendingBuy())
        assertEquals(0, state.reconcileFailureCount) // 확정되면 다음 주문으로 넘기지 않는다
    }

    @Test
    fun `a sell lookup failure also restarts the miss count`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(70)
        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("order lookup down")
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(10)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertEquals(identifier, state.pendingSellIdentifier) // 첫 404 로부터 80초지만 끊김 없는 404 는 1회
    }

    @Test
    fun `an order answered without a uuid is kept pending by identifier`() = runTest {
        // 역직렬화된 빈 응답은 uuid 가 "" 다 — 그걸 채택하면 체결 확인이 null 이 되어 주문이 무산으로 풀린다.
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.001"))
        placeOrderAnswers { Order() }
        val buying = TradingState("KRW-BTC")
        val selling = held()

        manager.buy("KRW-BTC", buying, 50_000_000.0, "test")
        manager.sell("KRW-BTC", selling, 52_000_000.0, SellReason.STOP_LOSS)

        assertNotNull(buying.pendingBuyIdentifier)
        assertNull(buying.pendingBuyUuid)
        assertNotNull(selling.pendingSellIdentifier)
        assertNull(selling.pendingSellUuid)
    }

    @Test
    fun `a rejected buy is released at once but an ambiguous client error keeps it`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { throw UpbitApiException(400, "insufficient_funds_bid", null, "") }
        val rejected = TradingState("KRW-BTC")

        manager.buy("KRW-BTC", rejected, 50_000_000.0, "test")

        assertFalse(rejected.hasPendingBuy())
        assertNull(saved.last().pendingBuyIdentifier)

        placeOrderAnswers { throw UpbitApiException(400, "duplicated_identifier", null, "") }
        val ambiguous = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", ambiguous, 50_000_000.0, "test")
        assertNotNull(ambiguous.pendingBuyIdentifier)
    }

    @Test
    fun `a buy is not sent when its identifier cannot be recorded first`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        coEvery { stateService.upsert(any(), any()) } throws RuntimeException("db down")
        val state = TradingState("KRW-BTC")

        assertNull(manager.buy("KRW-BTC", state, 50_000_000.0, "test"))

        coVerify(exactly = 0) { upbit.placeOrder(any()) }
        assertFalse(state.hasPendingBuy())
        assertNull(state.pendingBuyStrategy)
    }

    @Test
    fun `the uuid of a buy in flight is recorded even if the tick is cancelled`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { delay(300); Order(uuid = "u-7") }
        coEvery { upbit.getOrder("u-7") } returns Order(uuid = "u-7", state = "wait")
        val state = TradingState("KRW-BTC")

        val job = launch { manager.buy("KRW-BTC", state, 50_000_000.0, "test") }
        advanceTimeBy(100) // 응답을 기다리는 중
        job.cancel()
        advanceUntilIdle()

        assertEquals("u-7", state.pendingBuyUuid)
        assertNull(state.pendingBuyIdentifier)
        assertEquals("u-7", saved.last().pendingBuyUuid)
    }

    @Test
    fun `a stopping engine does not start a new order`() = runTest {
        coEvery { upbit.getAccounts() } coAnswers { currentCoroutineContext().cancel(); listOf(krw()) }
        val state = TradingState("KRW-BTC")

        launch { manager.buy("KRW-BTC", state, 50_000_000.0, "test") }
        advanceUntilIdle()

        coVerify(exactly = 0) { upbit.placeOrder(any()) }
        assertFalse(state.hasPendingBuy())
    }

    @Test
    fun `a buy known only by identifier after a restart is confirmed by identifier`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { Order(uuid = "u-12") }
        // 선기록은 성공, uuid 기록은 실패 → durable 에는 identifier 만 남는다.
        var upserts = 0
        coEvery { stateService.upsert(any(), any()) } coAnswers {
            if (upserts++ == 0) saved += secondArg<TradingState>().copy() else throw RuntimeException("db down")
        }
        coEvery { upbit.getOrder("u-12") } throws RuntimeException("api down")
        manager.buy("KRW-BTC", TradingState("KRW-BTC"), 50_000_000.0, "test")
        val durable = saved.single()
        assertNull(durable.pendingBuyUuid)
        val identifier = durable.pendingBuyIdentifier!!

        coEvery { stateService.upsert(any(), any()) } coAnswers { saved += secondArg<TradingState>().copy() }
        coEvery { upbit.getOrderByIdentifier(identifier) } returns filled("u-12", "0.002")
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))
        val restarted = PositionManager(upbit, TradingProperties(), stateService, 1L, clock = clock)
        val record = restarted.reconcilePendingBuy("KRW-BTC", durable.copy(), 50_000_000.0)

        assertEquals("u-12", record?.exchangeOrderId)
    }

    // --- 매도 ---

    private fun held() = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.001)

    @Test
    fun `an unanswered sell keeps the identifier and the position and does not sell twice`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("ReadTimeoutException") }
        val state = held()

        assertNull(manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS))

        assertEquals(sent.single().identifier, state.pendingSellIdentifier)
        assertEquals(sent.single().identifier, saved.first().pendingSellIdentifier) // 보내기 전 기록
        assertEquals(0.001, state.pendingSellPriorVolume)
        assertNotNull(state.pendingSellSince)
        assertTrue(state.position)

        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a sell found by identifier is recorded with the exchange uuid`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!

        coEvery { upbit.getOrderByIdentifier(identifier) } returns filled("s-1", "0.001")
        coEvery { upbit.getAccounts() } returns listOf(krw())
        val record = manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertEquals("s-1", record?.exchangeOrderId)
        assertFalse(state.position)
        assertFalse(state.hasPendingSell())
    }

    @Test
    fun `a sell found by identifier later is recorded at the price it was decided at`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        assertEquals(52_000_000.0, saved.first().pendingSellTriggerPrice) // 보내기 전 선기록에 실린다(재시작에도 남는다)

        coEvery { upbit.getOrderByIdentifier(state.pendingSellIdentifier!!) } returns filled("s-2", "0.001")
        coEvery { upbit.getAccounts() } returns listOf(krw())
        val record = manager.reconcilePendingSell("KRW-BTC", state, 45_000_000.0)

        assertEquals(52_000_000.0, record!!.price)
        assertEquals(52_000.0, record.totalAmount, 1e-6)
    }

    @Test
    fun `a missing sell whose coins are gone is neither recorded nor released`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw()) // 주문 전 0.001 → 0
        logs.list.clear()

        repeat(3) {
            assertNull(manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0))
            advance(70)
        }

        assertEquals(identifier, state.pendingSellIdentifier)
        assertTrue(state.position)
        assertEquals(1, traceAlerts(identifier))
    }

    @Test
    fun `a sell the exchange never saw is released after two misses and sixty seconds`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(30)
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        assertEquals(identifier, state.pendingSellIdentifier)

        advance(40)
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        assertFalse(state.hasPendingSell())
        assertTrue(state.position) // 코인은 그대로 — 다음 tick 에 다시 판다
        assertNull(saved.last().pendingSellIdentifier)
    }

    @Test
    fun `the uuid of a sell in flight is recorded even if the tick is cancelled`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { delay(300); Order(uuid = "s-7") }
        coEvery { upbit.getOrder("s-7") } returns Order(uuid = "s-7", state = "wait")
        val state = held()

        val job = launch { manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS) }
        advanceTimeBy(100)
        job.cancel()
        advanceUntilIdle()

        assertEquals("s-7", state.pendingSellUuid)
        assertNull(state.pendingSellIdentifier)
        assertEquals("s-7", saved.last().pendingSellUuid)
    }

    @Test
    fun `a sell is still sent when its identifier cannot be recorded first`() = runTest {
        // 기록 장애가 손절을 막으면 안 된다 — 메모리 identifier 가 같은 프로세스의 이중 매도는 막는다.
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        coEvery { stateService.upsert(any(), any()) } throws RuntimeException("db down")
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()

        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)

        assertEquals(1, sent.size)
        assertTrue(state.pendingPersistFailed)
        assertNotNull(state.pendingSellIdentifier)
    }

    // --- 확정 규칙의 관측 가능한 계약 (#235) ---
    // 규칙을 PositionManager 밖으로 옮겨도 아래가 그대로여야 한다. 기존 테스트가 우연히 가리지 못하던 것만 고정한다.

    /** 응답을 못 받아 identifier 만 남은 매수. */
    private suspend fun unknownBuy(): Pair<TradingState, String> {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        return state to state.pendingBuyIdentifier!!
    }

    @Test
    fun `an unknown sell never counts toward the halt`() = runTest {
        // 매도는 경과시간 알림이 사람을 부른다 — halt 는 신규 매수만 막는 플래그라 매도 판정은 세지 않는다.
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!

        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("order lookup down")
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } throws RuntimeException("accounts down")
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertEquals(0, state.reconcileFailureCount)
        assertFalse(state.halted)
    }

    @Test
    fun `finding a buy by identifier clears the halt counter even before it fills`() = runTest {
        val (state, identifier) = unknownBuy()
        state.reconcileFailureCount = 3
        coEvery { upbit.getOrderByIdentifier(identifier) } returns Order(uuid = "u-wait", state = "wait", executedVolume = "0")

        assertNull(manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0))

        assertEquals("u-wait", state.pendingBuyUuid) // 아직 체결 전 — 이어받은 uuid 로 다음 tick 에 확정한다
        assertEquals(0, state.reconcileFailureCount) // 주문 상태를 봤으니 조회 장애 카운트는 끊긴다
    }

    @Test
    fun `a released sell resyncs the position while the lock ceiling still sees the order`() = runTest {
        // 재시작 뒤처럼 position=false 로 복원된 행. 해제 전에 동기화해야 잔고 해석이 이 주문의 락 상한을 쓴다
        // (clear 뒤에 동기화하면 상한이 0 이 되어 locked 가 빠진다) — 지금 동작을 그대로 고정한다.
        val state = TradingState("KRW-BTC").apply {
            beginSellOrder("ctb-restored", SellReason.STOP_LOSS, since = clock.instant(), volume = 0.001, triggerPrice = 52_000_000.0, priorVolume = 0.0005)
        }
        coEvery { upbit.getOrderByIdentifier("ctb-restored") } returns null
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.0005", locked = "0.0005", avgBuyPrice = "50000000"))

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(70)
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertFalse(state.hasPendingSell())
        assertTrue(state.position)
        assertEquals(0.001, state.holdVolume, 1e-12)
        assertTrue(logs.list.any { it.level == Level.WARN && "Sell order ctb-restored for KRW-BTC was never placed" in it.formattedMessage })
    }

    @Test
    fun `the balance is read only when the exchange does not know the order`() = runTest {
        val (state, identifier) = unknownBuy()

        clearMocks(upbit, answers = false)
        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("order lookup down")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        coVerify(exactly = 0) { upbit.getAccounts() } // 조회가 실패하면 흔적을 보지 않는다

        clearMocks(upbit, answers = false)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        coVerify(exactly = 1) { upbit.getAccounts() } // 404 면 흔적 판정 한 번
    }

    @Test
    fun `an unknown buy is released exactly sixty seconds after the first miss`() = runTest {
        val (state, identifier) = unknownBuy()
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(59)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(identifier, state.pendingBuyIdentifier)

        advance(1)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertFalse(state.hasPendingBuy())
        assertTrue(logs.list.any { it.level == Level.WARN && "Buy order $identifier for KRW-BTC was never placed" in it.formattedMessage })
    }
}
