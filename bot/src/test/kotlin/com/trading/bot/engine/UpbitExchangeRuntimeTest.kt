package com.trading.bot.engine

import com.trading.bot.client.UpbitClientImpl
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.entity.UserEntity
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import com.trading.common.strategy.TradingStrategy
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.mockk.mockk
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

/** Upbit 런타임이 매니저·엔진이 쓰던 값과 같은 값을 내는지 고정한다. 비교 기준은 생성자 기본값이다. */
class UpbitExchangeRuntimeTest {

    private val properties = TradingProperties()
    private val marketDataStore = mockk<MarketDataStore>(relaxed = true)
    private val dailyCandleCache = mockk<DailyCandleCache>()

    private fun runtime(webClient: WebClient = mockk(relaxed = true)) =
        UpbitExchangeRuntime(webClient, properties, marketDataStore, dailyCandleCache)

    private fun user(accessKey: String?, secretKey: String?) =
        UserEntity(id = 1L, username = "u1", password = "p", upbitAccessKey = accessKey, upbitSecretKey = secretKey)

    @Test
    fun `upbit keeps the UPBIT key, the engine phase and a 25 second stop budget`() {
        val runtime = runtime()
        assertEquals(Exchange.UPBIT, runtime.exchange)
        assertEquals("UPBIT", runtime.exchange.name)
        assertEquals(100, runtime.lifecyclePhase)
        assertEquals(Duration.ofSeconds(25), runtime.stopBudget)
        assertSame(properties, runtime.profile)
    }

    @Test
    fun `start needs both keys while restore needs only the access key`() {
        val runtime = runtime()
        val values = listOf(null, "", "  ", "k")
        for (accessKey in values) {
            for (secretKey in values) {
                val user = user(accessKey, secretKey)
                assertEquals(accessKey == "k" && secretKey == "k", runtime.canStart(user), "start $accessKey/$secretKey")
                assertEquals(accessKey == "k", runtime.canRestore(user), "restore $accessKey/$secretKey")
            }
        }
        assertTrue(runtime.canRestore(user("k", null)))
        assertFalse(runtime.canStart(user("k", null)))
    }

    @Test
    fun `parts are the defaults the engine used to be built with`() {
        val parts = runtime().partsFor(user("ak", "sk"))

        assertTrue(parts.client is UpbitClientImpl, parts.client.javaClass.name)
        assertEquals(Exchange.UPBIT, parts.exchange)
        assertSame(properties, parts.profile)
        assertSame(marketDataStore, parts.marketDataStore)
        assertSame(dailyCandleCache, parts.dailyCandleCache)
        assertTrue(parts.fillAccounting is BalanceFillAccounting)
        assertSame(parts.client, field(parts.fillAccounting, "client"))

        val positionDefaults = PositionManager(parts.client, properties, mockk(relaxed = true), 1L)
        assertSame(field(positionDefaults, "rules"), parts.rules)
        assertSame(field(positionDefaults, "calendar"), parts.calendar)
        assertSame(field(positionDefaults, "orderGate"), parts.orderGate)
        assertEquals(field(positionDefaults, "fillPoll"), parts.fillPoll)
        assertSame(field(positionDefaults, "errorClassifier"), parts.errorClassifier)
        assertEquals(field(field(positionDefaults, "unknownOrders")!!, "policy"), parts.unknownOrderPolicy)
        assertEquals(field(positionDefaults, "displayName"), parts.displayName)
        assertTrue(field(positionDefaults, "fillAccounting") is BalanceFillAccounting)

        val engineDefaults = TradingEngine(
            parts.client, positionDefaults, DailyResetManager(properties), mockk<TradingStrategy>(relaxed = true), properties,
        )
        assertEquals(field(engineDefaults, "exchange"), parts.exchange)
        assertSame(field(engineDefaults, "calendar"), parts.calendar)
        assertSame(field(engineDefaults, "rules"), parts.rules)
        assertSame(field(DailyResetManager(properties), "calendar"), parts.calendar)
    }

    @Test
    fun `query and engine clients sign with the given user's keys`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            repeat(2) { server.enqueue(json("""[{"currency":"KRW","balance":"1000","locked":"0","avg_buy_price":"0"}]""")) }
            val webClient = WebClient.builder().baseUrl(server.url("/").toString().trimEnd('/')).build()
            val secret = "decrypted-secret-key-that-is-long-enough"
            val user = user("decrypted-access", secret)
            val runtime = runtime(webClient)

            runtime.queryClient(user).getAccounts()
            runtime.partsFor(user).client.getAccounts()

            // 요청이 덜 나가면 무한 대기 대신 실패하도록 기다리는 시간을 제한한다.
            val requests = List(2) { server.takeRequest(5, TimeUnit.SECONDS) }
            assertEquals(listOf("/v1/accounts", "/v1/accounts"), requests.map { it?.path })
            requests.forEach { assertEquals("decrypted-access", accessKeyClaim(it!!, secret)) }
        } finally {
            server.shutdown()
        }
    }

    private fun json(body: String) = MockResponse().setBody(body).addHeader("Content-Type", "application/json")

    private fun accessKeyClaim(request: RecordedRequest, secret: String): Any? {
        val token = request.getHeader("Authorization")!!.removePrefix("Bearer ")
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.toByteArray())).build()
            .parseSignedClaims(token).payload["access_key"]
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
