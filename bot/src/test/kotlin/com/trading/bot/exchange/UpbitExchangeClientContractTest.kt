package com.trading.bot.exchange

import com.trading.bot.client.UpbitAuthProvider
import com.trading.bot.client.UpbitClientImpl
import java.time.LocalDate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.springframework.web.reactive.function.client.WebClient

class UpbitExchangeClientContractTest : ExchangeClientContract() {

    private val server = MockWebServer().apply { start() }

    override val client: ExchangeClient = UpbitClientImpl(
        WebClient.builder().baseUrl(server.url("/").toString().trimEnd('/')).build(),
        UpbitAuthProvider(accessKey = "test-key", secretKey = "test-secret-key-that-is-long-enough"),
    )

    override val market = "KRW-BTC"
    override val rules: MarketRules = UpbitMarketRules
    override val unknownOrderPolicy = UnknownOrderPolicy.UPBIT

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun respond(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body).addHeader("Content-Type", "application/json"))
    }

    override fun givenAccounts(cashKrw: Long, heldVolume: String, avgBuyPrice: Long) = respond(
        """[{"currency":"KRW","balance":"$cashKrw","locked":"0","avg_buy_price":"0","unit_currency":"KRW"},
            {"currency":"BTC","balance":"$heldVolume","locked":"0","avg_buy_price":"$avgBuyPrice","unit_currency":"KRW"}]""",
    )

    override fun givenDayCandles(newestTradingDate: LocalDate, count: Int) = respond(
        (0 until count).joinToString(",", "[", "]") { back ->
            val day = newestTradingDate.minusDays(back.toLong())
            """{"market":"KRW-BTC","candle_date_time_utc":"${day}T00:00:00","candle_date_time_kst":"${day}T09:00:00",
               "opening_price":94000000,"high_price":96000000,"low_price":93000000,"trade_price":95000000,
               "candle_acc_trade_price":100000000,"candle_acc_trade_volume":1.5}"""
        },
    )

    override fun givenTicker(tradePrice: Double) = respond("""[{"market":"KRW-BTC","trade_price":$tradePrice}]""")

    override fun givenOrderAccepted(uuid: String) = respond("""{"uuid":"$uuid","side":"bid","ord_type":"price","state":"wait"}""")

    override fun givenFilledOrder(uuid: String, fills: List<Pair<Long, String>>) {
        val executed = fills.sumOf { it.second.toBigDecimal() }
        val trades = fills.joinToString(",", "[", "]") { (price, volume) ->
            """{"market":"KRW-BTC","price":"$price","volume":"$volume","funds":"${volume.toBigDecimal() * price.toBigDecimal()}"}"""
        }
        respond("""{"uuid":"$uuid","state":"done","executed_volume":"${executed.toPlainString()}","trades":$trades}""")
    }

    override fun givenOpenOrder(uuid: String) = respond("""{"uuid":"$uuid","state":"wait","executed_volume":"0"}""")

    override fun givenIdentifierKnown(identifier: String, uuid: String) =
        respond("""{"uuid":"$uuid","identifier":"$identifier","state":"done","executed_volume":"0.001"}""")

    override fun givenIdentifierNeverPlaced(identifier: String) =
        respond("""{"error":{"name":"order_not_found","message":"주문을 찾지 못했습니다."}}""", status = 404)
}
