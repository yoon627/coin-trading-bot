package com.trading.bot.characterization

import ch.qos.logback.classic.Level
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.bot.engine.DailyResetManager
import com.trading.bot.engine.PositionManager
import com.trading.bot.engine.TradeExecutionService
import com.trading.bot.engine.TradingEngine
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Upbit 봇 매니저의 복원·시작·정지·pending 해제와 엔진 배선을 고정한다. bot_state 는 exchange "UPBIT" 로 읽고 쓴다.
 * 복원은 autoStart 일 때만, access key 만 확인하고, 복호화 → 상태 로드 → 엔진 등록 → 기동 순서이며 실패하면 1·2·4·8초 간격으로 5회까지 한다.
 * 시작은 access·secret key 를 둘 다 요구하고 기동 전에 running=true 를 저장하며, 도는 엔진에 다른 목록을 주면 CONFLICT 다.
 * 정지는 running=false 를 저장하고, pending 해제는 엔진도 DB 행도 멈췄을 때만 받는다. 엔진은 복호화된 키로 서명하는 Upbit 클라이언트,
 * TradeExecutionService.commitFill, KST 시계의 DailyResetManager 로 만든다. SmartLifecycle phase 는 0, 종료 예산은 25초다.
 */
class UserTradingManagerCharacterizationTest {

    private val userRepository = mockk<UserRepository>()
    private val botStateRepository = mockk<BotStateRepository>()
    private val userSecretsService = mockk<UserSecretsService>()
    private val tradingStateService = mockk<TradingStateService>(relaxed = true)
    private val tradeExecutionService = mockk<TradeExecutionService>(relaxed = true)
    private val engine = mockk<TradingEngine>(relaxed = true)
    private val strategy = mockk<TradingStrategy> { every { name } returns "combined" }
    private lateinit var manager: UserTradingManager

    private fun newManager(autoStart: Boolean = true, webClient: WebClient = mockk(relaxed = true)) = UserTradingManager(
        userRepository, botStateRepository, tradeExecutionService, mockk<DiscordNotifier>(relaxed = true),
        strategy, TradingProperties(autoStart = autoStart), webClient,
        userSecretsService, mockk<MarketDataStore>(relaxed = true), tradingStateService,
    )

    @BeforeEach
    fun setup() {
        manager = spyk(newManager())
        every { manager.createEngine(any()) } returns engine
        every { engine.isRunning() } returns false
        every { engine.unpersistedSells() } returns emptyList()
    }

    private fun row(running: Boolean) =
        BotStateEntity(id = 10L, userId = 1L, exchange = "UPBIT", running = running, strategy = "combined", tickers = "KRW-BTC,KRW-ETH")

    private fun user(accessKey: String? = "enc-ak", secretKey: String? = "enc-sk") =
        UserEntity(id = 1L, username = "u1", password = "p", upbitAccessKey = accessKey, upbitSecretKey = secretKey)

    private fun restorable(user: UserEntity) {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(row(running = true))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(row(running = true))
        every { userRepository.findById(1L) } returns Mono.just(user)
    }

    /** 공개 복원 경로로 엔진을 등록하고 도는 상태로 둔다. */
    private suspend fun registerRunningEngine() {
        restorable(user())
        every { userSecretsService.decryptUserSecrets(any()) } answers { firstArg() }
        coEvery { tradingStateService.loadStates(1L) } returns emptyMap()
        manager.restoreAllRunningBots()
        assertSame(engine, manager.getEngine(1L))
        every { engine.isRunning() } returns true
    }

    @Test
    fun `restore reads UPBIT rows, decrypts, loads states, registers the engine and starts it in that order`() = runTest {
        val stored = user()
        val decrypted = stored.copy(upbitAccessKey = "ak", upbitSecretKey = "sk")
        val states = mapOf("KRW-BTC" to TradingState("KRW-BTC"))
        restorable(stored)
        every { userSecretsService.decryptUserSecrets(stored) } returns decrypted
        coEvery { tradingStateService.loadStates(1L) } returns states
        var registeredAtStart: TradingEngine? = null
        every { engine.start(any(), any()) } answers { registeredAtStart = manager.getEngine(1L) }

        manager.restoreAllRunningBots()

        coVerifyOrder {
            botStateRepository.findByRunningTrueAndExchange("UPBIT")
            botStateRepository.findByUserIdAndExchange(1L, "UPBIT")
            userRepository.findById(1L)
            userSecretsService.decryptUserSecrets(stored)
            tradingStateService.loadStates(1L)
            manager.createEngine(decrypted)
            engine.start(listOf("KRW-BTC", "KRW-ETH"), states)
        }
        assertSame(engine, registeredAtStart)
    }

    @Test
    fun `restore skips a user without an Upbit access key before decrypting anything`() = runTest {
        restorable(user(accessKey = null))

        manager.restoreAllRunningBots()

        verify(exactly = 0) { userSecretsService.decryptUserSecrets(any()) }
        verify(exactly = 0) { manager.createEngine(any()) }
        verify(exactly = 0) { engine.start(any(), any()) }
    }

    @Test
    fun `restore checks only the access key, a missing secret key still restores`() = runTest {
        val stored = user(secretKey = null)
        restorable(stored)
        every { userSecretsService.decryptUserSecrets(stored) } returns stored
        coEvery { tradingStateService.loadStates(1L) } returns emptyMap()

        manager.restoreAllRunningBots()

        verify(exactly = 1) { engine.start(listOf("KRW-BTC", "KRW-ETH"), emptyMap()) }
    }

    @Test
    fun `restore retries a failing query 5 times with 1, 2, 4 and 8 second waits`() = runTest {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.error(RuntimeException("db down"))

        val start = currentTime
        manager.restoreAllRunningBots()

        verify(exactly = 5) { botStateRepository.findByRunningTrueAndExchange("UPBIT") }
        assertEquals(1_000L + 2_000L + 4_000L + 8_000L, currentTime - start)
    }

    @Test
    fun `startup restore runs only when autoStart is on`() {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.empty()

        newManager(autoStart = false).restoreOnStartup()
        verify(exactly = 0) { botStateRepository.findByRunningTrueAndExchange(any()) }

        newManager(autoStart = true).restoreOnStartup()
        verify(timeout = 5_000) { botStateRepository.findByRunningTrueAndExchange("UPBIT") }
    }

    @Test
    fun `start requires both the access key and the secret key`() = runTest {
        every { userRepository.findById(1L) } returns Mono.just(user(secretKey = null))
        val noSecret = manager.startBot(1L, listOf("KRW-BTC"))
        every { userRepository.findById(1L) } returns Mono.just(user(accessKey = null))
        val noAccess = manager.startBot(1L, listOf("KRW-BTC"))

        val expected = mapOf("error" to "Upbit API keys not configured. Set them via /api/user/keys")
        assertEquals(expected, noSecret)
        assertEquals(expected, noAccess)
        verify(exactly = 0) { userSecretsService.decryptUserSecrets(any()) }
        verify(exactly = 0) { manager.createEngine(any()) }
    }

    @Test
    fun `start saves a new UPBIT row with running true before starting the engine`() = runTest {
        val stored = user()
        val states = mapOf("KRW-BTC" to TradingState("KRW-BTC"))
        every { userRepository.findById(1L) } returns Mono.just(stored)
        every { userSecretsService.decryptUserSecrets(stored) } returns stored
        coEvery { tradingStateService.loadStates(1L) } returns states
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.empty()
        val saved = slot<BotStateEntity>()
        every { botStateRepository.save(capture(saved)) } answers { Mono.just(firstArg()) }

        val result = manager.startBot(1L, listOf("KRW-BTC"))

        assertEquals(mapOf("status" to "started", "strategy" to "combined"), result)
        coVerifyOrder {
            botStateRepository.findByUserIdAndExchange(1L, "UPBIT")
            botStateRepository.save(any())
            engine.start(listOf("KRW-BTC"), states)
        }
        assertNull(saved.captured.id)
        assertEquals(1L, saved.captured.userId)
        assertEquals("UPBIT", saved.captured.exchange)
        assertTrue(saved.captured.running)
        assertEquals("KRW-BTC", saved.captured.tickers)
        assertEquals("combined", saved.captured.strategy)
    }

    @Test
    fun `starting a running bot with other tickers is a conflict and saves nothing`() = runTest {
        registerRunningEngine()
        every { engine.getUserTickers() } returns listOf("KRW-BTC", "KRW-ETH")

        val result = manager.startBot(1L, listOf("KRW-SOL"))

        assertEquals("Bot is already running with [KRW-BTC, KRW-ETH] — stop it before changing tickers", result["error"])
        assertEquals(UserTradingManager.CONFLICT_CODE, result["code"])
        verify(exactly = 0) { botStateRepository.save(any()) }
        verify(exactly = 1) { engine.start(any(), any()) } // 복원 때의 한 번뿐이다
    }

    @Test
    fun `stop halts the engine and then saves the existing UPBIT row with running false`() = runTest {
        registerRunningEngine()
        val saved = slot<BotStateEntity>()
        every { botStateRepository.save(capture(saved)) } answers { Mono.just(saved.captured) }

        val result = manager.stopBot(1L)

        assertEquals("stopped", result["status"])
        // 행 조회 키의 "UPBIT" 는 strict mock 이 고정한다. 다른 값이면 stub 이 없어 실패한다.
        coVerifyOrder {
            engine.stop()
            botStateRepository.findByUserIdAndExchange(1L, "UPBIT")
            botStateRepository.save(any())
        }
        assertFalse(saved.captured.running)
        assertEquals(10L, saved.captured.id) // 새 행이 아니라 읽은 행을 고쳐 쓴다
        assertNull(manager.getEngine(1L))
    }

    @Test
    fun `clearing a pending is refused while the engine runs`() = runTest {
        registerRunningEngine()

        val result = manager.clearPending(1L, "KRW-BTC", TradeSide.BUY)

        assertEquals("Stop the bot before clearing a pending order", result["error"])
        assertEquals(UserTradingManager.CONFLICT_CODE, result["code"])
        coVerify(exactly = 0) { tradingStateService.loadState(any(), any()) }
        coVerify(exactly = 0) { tradingStateService.upsert(any(), any()) }
    }

    @Test
    fun `clearing a pending is refused without an engine when the UPBIT row is still running`() = runTest {
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(row(running = true))

        val result = manager.clearPending(1L, "KRW-BTC", TradeSide.BUY)

        assertEquals("Stop the bot before clearing a pending order", result["error"])
        assertEquals(UserTradingManager.CONFLICT_CODE, result["code"])
        verify(exactly = 1) { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") }
        coVerify(exactly = 0) { tradingStateService.loadState(any(), any()) }
    }

    @Test
    fun `a stopped bot's pending is cleared from the row read by user and ticker`() = runTest {
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(row(running = false))
        coEvery { tradingStateService.loadState(1L, "KRW-BTC") } returns
            TradingState("KRW-BTC", pendingBuyIdentifier = "ctb-1", pendingBuyStrategy = "combined")
        val written = slot<TradingState>()
        coEvery { tradingStateService.upsert(1L, capture(written)) } returns Unit

        val before = TradingDay.of(LocalDateTime.now(TradingDay.KST))
        val result = manager.clearPending(1L, "KRW-BTC", TradeSide.BUY)

        assertEquals("cleared", result["status"])
        coVerify(exactly = 1) { tradingStateService.loadState(any(), any()) }
        coVerify(exactly = 1) { tradingStateService.loadState(1L, "KRW-BTC") }
        coVerify(exactly = 1) { tradingStateService.upsert(any(), any()) }
        assertEquals("KRW-BTC", written.captured.ticker)
        assertFalse(written.captured.hasPendingBuy())
        // 매수 해제는 체결이 남겼을 진입 흔적을 대신 남긴다.
        assertEquals("combined", written.captured.entryStrategy)
        assertTrue(written.captured.boughtToday)
        assertTrue(written.captured.buyDate!! >= before, "buyDate=${written.captured.buyDate}")
    }

    @Test
    fun `createEngine signs with the decrypted keys and commits fills through TradeExecutionService`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(json("""{"uuid":"u-1","state":"done","executed_volume":"0.001","paid_fee":"26"}"""))
            server.enqueue(json("""[{"currency":"BTC","balance":"0.001","locked":"0","avg_buy_price":"51000000"}]"""))
            val webClient = WebClient.builder().baseUrl(server.url("/").toString().trimEnd('/')).build()
            val secret = "decrypted-secret-key-that-is-long-enough"
            val decrypted = user(accessKey = "decrypted-access", secretKey = secret)

            val engine = newManager(webClient = webClient).createEngine(decrypted)
            val state = TradingState("KRW-BTC", pendingBuyUuid = "u-1", pendingBuyStrategy = "combined")
            privateField<PositionManager>(engine, "positionManager").reconcilePendingBuy("KRW-BTC", state, 51_000_000.0)

            coVerify(exactly = 1) {
                tradeExecutionService.commitFill(any(), match { it.exchangeOrderId == "u-1" && it.side == TradeSide.BUY && it.userId == 1L })
            }
            // 요청이 덜 나가면 무한 대기 대신 실패하도록 기다리는 시간을 제한한다.
            val requests = List(2) { server.takeRequest(5, TimeUnit.SECONDS) }
            assertEquals(listOf("/v1/order?uuid=u-1", "/v1/accounts"), requests.map { it?.path })
            requests.forEach { assertEquals("decrypted-access", accessKeyClaim(it!!, secret)) }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `createEngine resets trading days on a system clock in KST`() {
        val engine = newManager().createEngine(user())

        val clock = privateField<Clock>(privateField<DailyResetManager>(engine, "dailyResetManager"), "clock")

        assertEquals(TradingDay.KST, clock.zone)
        assertTrue(abs(clock.millis() - System.currentTimeMillis()) < 5_000)
    }

    @Test
    fun `the lifecycle phase is 0`() {
        assertEquals(0, manager.phase)
    }

    @Test
    fun `shutdown gives up on a stuck engine after 25 seconds`() {
        runBlocking { registerRunningEngine() }
        coEvery { engine.stop() } coAnswers { awaitCancellation() }

        capturingLogs(UserTradingManager::class.java) { logs ->
            val start = System.nanoTime()
            manager.stop()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            // 운영 호스트의 부하를 감안해 상한은 넉넉히 둔다. 하한이 예산을 고정한다.
            assertTrue(elapsedMs in 25_000 until 40_000, "elapsed=$elapsedMs")
            assertTrue(
                logs.list.any { it.level == Level.WARN && "25000ms 예산 초과" in it.formattedMessage },
                logs.list.map { it.formattedMessage }.toString(),
            )
        }
    }

    private fun json(body: String) = MockResponse().setBody(body).addHeader("Content-Type", "application/json")

    private fun accessKeyClaim(request: RecordedRequest, secret: String): Any? {
        val token = request.getHeader("Authorization")!!.removePrefix("Bearer ")
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.toByteArray())).build()
            .parseSignedClaims(token).payload["access_key"]
    }

    /** 배선은 생성자 안에서만 보이므로 엔진이 쥔 협력자를 꺼내 본다. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
}
