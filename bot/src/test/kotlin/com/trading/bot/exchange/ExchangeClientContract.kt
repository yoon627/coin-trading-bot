package com.trading.bot.exchange

import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * 엔진이 [ExchangeClient] 에 기대는 값의 의미. 거래소 구현마다 이 suite 를 상속해 자기 fake API 로 given 을 채운다.
 * 각 테스트는 given 을 부른 순서대로 클라이언트를 한 번씩 호출한다.
 */
abstract class ExchangeClientContract {
    protected abstract val client: ExchangeClient

    /** 계약을 확인할 종목. '-' 뒤가 보유 행의 currency 다. */
    protected abstract val market: String

    /** 주문 요청은 엔진처럼 이 규칙으로 만든다. */
    protected abstract val rules: MarketRules

    protected abstract val unknownOrderPolicy: UnknownOrderPolicy

    protected abstract fun givenAccounts(cashKrw: Long, heldVolume: String, avgBuyPrice: Long)

    /** [newestTradingDate] 부터 하루씩 과거로 [count] 개의 일봉. */
    protected abstract fun givenDayCandles(newestTradingDate: LocalDate, count: Int)

    protected abstract fun givenTicker(tradePrice: Double)

    protected abstract fun givenOrderAccepted(uuid: String)

    /** 체결이 끝난 주문. [fills] 는 (가격, 수량). */
    protected abstract fun givenFilledOrder(uuid: String, fills: List<Pair<Long, String>>)

    protected abstract fun givenOpenOrder(uuid: String)

    protected abstract fun givenIdentifierKnown(identifier: String, uuid: String)

    protected abstract fun givenIdentifierNeverPlaced(identifier: String)

    private val currency get() = market.substringAfter("-")

    @Test
    fun `accounts carry a KRW cash row and a held row keyed by the part of the ticker after the dash`() = runTest {
        givenAccounts(cashKrw = 1_000_000, heldVolume = "3", avgBuyPrice = 50_000)

        val accounts = client.getAccounts()

        assertEquals(1_000_000.0, accounts.single { it.currency == "KRW" }.balanceDouble())
        val held = accounts.single { it.currency == currency }
        assertEquals(3.0, held.balanceDouble())
        assertEquals(50_000.0, held.avgBuyPriceDouble())
    }

    @Test
    fun `day candles come newest first and stamp each trading day at 00 00 UTC`() = runTest {
        val newest = LocalDate.of(2026, 10, 16)
        givenDayCandles(newest, count = 3)

        val candles = client.getDayCandles(market, 3)

        assertEquals(
            listOf(newest, newest.minusDays(1), newest.minusDays(2)).map { it.atStartOfDay() },
            candles.map { LocalDateTime.parse(it.candleDateTimeUtc) },
        )
    }

    @Test
    fun `ticker returns the last trade price of the market`() = runTest {
        givenTicker(tradePrice = 71_500.0)

        assertEquals(71_500.0, client.getTicker(market).single().tradePrice)
    }

    @Test
    fun `an accepted order is answered with the exchange order id`() = runTest {
        givenOrderAccepted("ord-1")
        val size = checkNotNull(rules.buySize(investAmount = 1_000_000.0, price = 50_000.0))

        val order = client.placeOrder(rules.buyOrderRequest(market, size, "ctb-1"))

        assertEquals("ord-1", order.uuid)
    }

    @Test
    fun `a filled order is terminal and its trades give the volume-weighted fill price`() = runTest {
        givenFilledOrder("ord-2", listOf(100L to "1", 200L to "3"))

        val order = client.getOrder("ord-2")

        assertTrue(order.state in ORDER_STATES, order.state)
        assertTrue(order.isTerminal(), order.state)
        assertEquals(4.0, order.executedVolume?.toDouble())
        assertEquals(175.0, order.filledVwap())
    }

    @Test
    fun `an order still working is wait`() = runTest {
        givenOpenOrder("ord-3")

        assertEquals("wait", client.getOrder("ord-3").state)
    }

    @Test
    fun `an identifier the exchange knows returns that order`() = runTest {
        givenIdentifierKnown("ctb-known", uuid = "ord-4")

        val order = client.getOrderByIdentifier("ctb-known")

        assertNotNull(order)
        assertEquals("ord-4", order!!.uuid)
        assertTrue(order.state in ORDER_STATES, order.state)
    }

    /** client order id 가 없는 거래소의 null 은 미접수 확정이 아니라 이 계약을 지키지 않는다. */
    @Test
    fun `null from an identifier lookup means the order was never placed`() = runTest {
        assumeTrue(unknownOrderPolicy.supportsClientOrderId)
        givenIdentifierNeverPlaced("ctb-missing")

        assertNull(client.getOrderByIdentifier("ctb-missing"))
    }

    private companion object {
        val ORDER_STATES = setOf("wait", "done", "cancel")
    }
}
