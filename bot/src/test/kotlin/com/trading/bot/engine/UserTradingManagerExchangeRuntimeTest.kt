package com.trading.bot.engine

import ch.qos.logback.classic.Level
import com.trading.bot.characterization.capturingLogs
import com.trading.bot.client.UpbitClientImpl
import com.trading.bot.domain.TradeSide
import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.exchange.FillPollPolicy
import com.trading.bot.exchange.UnknownOrderPolicy
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import com.trading.common.strategy.TradingStrategy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** Upbit 가 아닌 런타임으로 만든 매니저는 그 거래소의 bot_state 행만 읽고 쓰고, 키 검사·정지 예산·부품을 런타임에서 받는다. */
class UserTradingManagerExchangeRuntimeTest {

    private val userRepository = mockk<UserRepository>()
    private val botStateRepository = mockk<BotStateRepository>()
    private val userSecretsService = mockk<UserSecretsService>()
    private val tradingStateService = mockk<TradingStateService>(relaxed = true)
    private val engine = mockk<TradingEngine>(relaxed = true)
    private val strategy = mockk<TradingStrategy> { every { name } returns "combined" }
    private val client = mockk<ExchangeClient>()

    // 매니저 생성자의 TradingProperties(autoStart=true, KRW-BTC)와 일부러 다르게 둔다.
    private val runtimeProfile = TradingProperties(tickers = "KRX-005930,KRX-000660", autoStart = false)
    private val parts = ExchangeParts(
        exchange = Exchange.KIS_PAPER,
        client = client,
        rules = mockk(relaxed = true),
        calendar = mockk(relaxed = true),
        orderGate = mockk(relaxed = true),
        fillPoll = FillPollPolicy(attempts = 1, delayMs = 0),
        fillAccounting = mockk(relaxed = true),
        errorClassifier = mockk(relaxed = true),
        unknownOrderPolicy = UnknownOrderPolicy(1, Duration.ZERO, supportsClientOrderId = false),
        profile = runtimeProfile,
        marketDataStore = null,
        dailyCandleCache = null,
        displayName = "KIS 모의",
    )

    private val runtime = object : ExchangeRuntime {
        override val exchange = Exchange.KIS_PAPER
        override val profile = runtimeProfile
        override val stopBudget: Duration = Duration.ofMillis(300)
        override val lifecyclePhase = 7

        // Upbit 키가 없어도 시작·복원하므로 매니저가 Upbit 키를 보지 않는지 드러난다.
        override fun canStart(user: UserEntity) = true
        override fun canRestore(user: UserEntity) = true
        override fun partsFor(user: UserEntity) = parts
        override fun queryClient(user: UserEntity) = client
    }

    private fun newManager() = UserTradingManager(
        userRepository, botStateRepository, mockk(relaxed = true), mockk(relaxed = true),
        strategy, TradingProperties(autoStart = true), mockk(relaxed = true),
        userSecretsService, mockk(relaxed = true), tradingStateService,
        runtime = runtime,
        manualInterventions = savingInterventions(tradingStateService),
    )

    private val manager = spyk(newManager()).also { every { it.createEngine(any()) } returns engine }

    private fun row(exchange: String, running: Boolean, userId: Long = 1L) =
        BotStateEntity(id = 10L * userId, userId = userId, exchange = exchange, running = running, tickers = "KRX-005930")

    private val user = UserEntity(id = 1L, username = "u1", password = "p")

    private fun verifyNoUpbitRowTouched() {
        verify(exactly = 0) { botStateRepository.findByRunningTrueAndExchange("UPBIT") }
        verify(exactly = 0) { botStateRepository.findByUserIdAndExchange(any(), "UPBIT") }
        verify(exactly = 0) { botStateRepository.save(match { it.exchange == "UPBIT" }) }
    }

    @Test
    fun `restore reads only the runtime's running rows`() = runTest {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(row("UPBIT", running = true, userId = 2L))
        every { botStateRepository.findByRunningTrueAndExchange("KIS_PAPER") } returns Flux.just(row("KIS_PAPER", running = true))
        every { botStateRepository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Mono.just(row("KIS_PAPER", running = true))
        every { userRepository.findById(1L) } returns Mono.just(user)
        every { userSecretsService.decryptUserSecrets(user) } returns user
        coEvery { tradingStateService.loadStates(1L) } returns emptyMap()

        manager.restoreAllRunningBots()

        verify(exactly = 1) { engine.start(listOf("KRX-005930"), emptyMap()) }
        verify(exactly = 0) { userRepository.findById(2L) }
        verifyNoUpbitRowTouched()
    }

    @Test
    fun `start saves a new row under the runtime's exchange`() = runTest {
        every { userRepository.findById(1L) } returns Mono.just(user)
        every { userSecretsService.decryptUserSecrets(user) } returns user
        coEvery { tradingStateService.loadStates(1L) } returns emptyMap()
        every { botStateRepository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Mono.empty()
        val saved = slot<BotStateEntity>()
        every { botStateRepository.save(capture(saved)) } answers { Mono.just(firstArg()) }

        val result = manager.startBot(1L, listOf("KRX-005930"))

        assertEquals("started", result["status"])
        assertNull(saved.captured.id)
        assertEquals("KIS_PAPER", saved.captured.exchange)
        assertTrue(saved.captured.running)
        verifyNoUpbitRowTouched()
    }

    @Test
    fun `stop marks only the runtime's row stopped`() = runTest {
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(row("UPBIT", running = true))
        every { botStateRepository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Mono.just(row("KIS_PAPER", running = true))
        val saved = slot<BotStateEntity>()
        every { botStateRepository.save(capture(saved)) } answers { Mono.just(firstArg()) }

        val result = manager.stopBot(1L)

        assertEquals("not_running", result["status"])
        assertEquals("KIS_PAPER", saved.captured.exchange)
        assertEquals(10L, saved.captured.id)
        assertFalse(saved.captured.running)
        verifyNoUpbitRowTouched()
    }

    @Test
    fun `clearing a pending ignores a running UPBIT row and checks the runtime's row`() = runTest {
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(row("UPBIT", running = true))
        every { botStateRepository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Mono.just(row("KIS_PAPER", running = false))
        coEvery { tradingStateService.loadState(1L, "KRX-005930", Exchange.KIS_PAPER) } returns null

        assertEquals(mapOf("status" to "not_pending"), manager.clearPending(1L, "KRX-005930", TradeSide.BUY))

        every { botStateRepository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Mono.just(row("KIS_PAPER", running = true))
        val refused = manager.clearPending(1L, "KRX-005930", TradeSide.BUY)

        assertEquals(UserTradingManager.CONFLICT_CODE, refused["code"])
        verifyNoUpbitRowTouched()
    }

    @Test
    fun `phase and stop budget come from the runtime`() {
        assertEquals(7, manager.phase)
        every { botStateRepository.findByRunningTrueAndExchange("KIS_PAPER") } returns Flux.just(row("KIS_PAPER", running = true))
        every { botStateRepository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Mono.just(row("KIS_PAPER", running = true))
        every { userRepository.findById(1L) } returns Mono.just(user)
        every { userSecretsService.decryptUserSecrets(user) } returns user
        coEvery { tradingStateService.loadStates(1L) } returns emptyMap()
        runBlocking { manager.restoreAllRunningBots() }
        assertSame(engine, manager.getEngine(1L))
        coEvery { engine.stop() } coAnswers { awaitCancellation() }

        capturingLogs(UserTradingManager::class.java) { logs ->
            val start = System.nanoTime()
            manager.stop()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            assertTrue(elapsedMs in 300 until 10_000, "elapsed=$elapsedMs")
            assertTrue(
                logs.list.any { it.level == Level.WARN && "300ms 예산 초과" in it.formattedMessage },
                logs.list.map { it.formattedMessage }.toString(),
            )
        }
    }

    @Test
    fun `createEngine assembles the engine from the runtime's parts`() {
        val engine = newManager().createEngine(user)

        assertSame(client, field(engine, "upbitClient"))
        assertEquals(Exchange.KIS_PAPER, field(engine, "exchange"))
        assertSame(parts.rules, field(engine, "rules"))
        assertSame(parts.calendar, field(engine, "calendar"))
        assertSame(parts.profile, field(engine, "tradingProperties"))
        assertNull(field(engine, "marketDataStore"))
        assertNull(field(engine, "dailyCandleCache"))

        val positionManager = field(engine, "positionManager")!!
        assertSame(client, field(positionManager, "upbitClient"))
        assertSame(parts.profile, field(positionManager, "tradingProperties"))
        listOf("rules", "calendar", "orderGate", "fillPoll", "fillAccounting", "errorClassifier", "displayName").forEach { name ->
            assertSame(field(parts, name), field(positionManager, name), name)
        }
        assertSame(parts.unknownOrderPolicy, field(field(positionManager, "unknownOrders")!!, "policy"))

        val dailyResetManager = field(engine, "dailyResetManager")!!
        assertSame(parts.calendar, field(dailyResetManager, "calendar"))
        assertSame(parts.profile, field(dailyResetManager, "tradingProperties"))
    }

    @Test
    fun `auto start and default tickers come from the runtime profile`() = runTest {
        // spy 는 하위 클래스라 private 필드를 읽으려면 원래 인스턴스가 필요하다.
        val plain = newManager()
        plain.restoreOnStartup()
        assertNull(field(plain, "restoreJob"))

        assertEquals(listOf("KRX-005930", "KRX-000660"), manager.getStatus(1L)["default_tickers"])

        every { userRepository.findById(1L) } returns Mono.just(user)
        every { userSecretsService.decryptUserSecrets(user) } returns user
        coEvery { tradingStateService.loadStates(1L) } returns emptyMap()
        every { botStateRepository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Mono.empty()
        val saved = slot<BotStateEntity>()
        every { botStateRepository.save(capture(saved)) } answers { Mono.just(firstArg()) }

        assertEquals("started", manager.startBot(1L, null)["status"])
        assertEquals("KRX-005930,KRX-000660", saved.captured.tickers)
    }

    @Test
    fun `the default upbit runtime hands the injected market data store and candle cache to the engine`() {
        val properties = TradingProperties()
        val marketDataStore = mockk<MarketDataStore>(relaxed = true)
        val dailyCandleCache = mockk<DailyCandleCache>()
        val upbitManager = UserTradingManager(
            userRepository, botStateRepository, mockk(relaxed = true), mockk(relaxed = true),
            strategy, properties, mockk(relaxed = true),
            userSecretsService, marketDataStore, tradingStateService,
            dailyCandleCache = dailyCandleCache,
            manualInterventions = savingInterventions(tradingStateService),
        )

        val engine = upbitManager.createEngine(user.copy(upbitAccessKey = "ak", upbitSecretKey = "sk"))

        assertEquals(Exchange.UPBIT, field(engine, "exchange"))
        assertTrue(field(engine, "upbitClient") is UpbitClientImpl)
        assertSame(properties, field(engine, "tradingProperties"))
        assertSame(marketDataStore, field(engine, "marketDataStore"))
        assertSame(dailyCandleCache, field(engine, "dailyCandleCache"))
    }

    @Test
    fun `query client comes from the runtime while createUpbitClient stays an Upbit client`() {
        val manager = newManager()
        val upbitUser = user.copy(upbitAccessKey = "ak", upbitSecretKey = "sk")

        assertSame(client, manager.queryClient(upbitUser))
        assertTrue(manager.createUpbitClient(upbitUser) is UpbitClientImpl)
    }

    /** 저장한 행을 다음 조회가 돌려주는 bot_state 한 행짜리 DB. */
    private fun storedRow(initial: BotStateEntity?): MutableList<BotStateEntity> {
        val saves = mutableListOf<BotStateEntity>()
        var current = initial
        every { botStateRepository.findByUserIdAndExchange(1L, "KIS_PAPER") } answers { Mono.justOrEmpty(current) }
        every { botStateRepository.save(any()) } answers {
            current = firstArg()
            saves += firstArg<BotStateEntity>()
            Mono.just(firstArg())
        }
        return saves
    }

    // 켜도 도는 엔진에 반영되지 않으면 스위치가 켜진 것처럼 보이면서 매수가 계속 나간다.
    @Test
    fun `an engine built before the switch reads it on every buy`() = runTest {
        val plain = newManager()
        @Suppress("UNCHECKED_CAST")
        val entriesPaused = field(field(plain.createEngine(user), "positionManager")!!, "entriesPaused") as () -> Boolean
        storedRow(row("KIS_PAPER", running = true))

        // 저장된 값을 읽지 않은 채 뜬 엔진은 사지 않는다.
        assertTrue(entriesPaused())
        plain.loadEntriesPause(1L)
        assertFalse(entriesPaused())
        plain.setEntriesPaused(1L, true)
        assertTrue(entriesPaused())
        plain.setEntriesPaused(1L, false)
        assertFalse(entriesPaused())
    }

    @Test
    fun `pausing saves the flag on this exchange's row and status shows it`() = runTest {
        val saves = storedRow(row("KIS_PAPER", running = false))

        val result = manager.setEntriesPaused(1L, true)

        val saved = saves.single()
        assertTrue(saved.entriesPaused)
        assertFalse(saved.running)
        assertNotNull(saved.entriesPausedChangedAt)
        assertEquals(true, result["entries_paused"])
        assertEquals(true, manager.getStatus(1L)["entries_paused"])
        assertEquals(saved.entriesPausedChangedAt.toString(), manager.getStatus(1L)["entries_paused_changed_at"])
        // 같은 값은 다시 쓰지 않아 바뀐 시각이 그대로다.
        manager.setEntriesPaused(1L, true)
        assertEquals(1, saves.size)
        verifyNoUpbitRowTouched()
    }

    @Test
    fun `a failed save leaves the switch as it was`() = runTest {
        every { botStateRepository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Mono.just(row("KIS_PAPER", running = true))
        every { botStateRepository.save(any()) } returns Mono.error(RuntimeException("db down"))
        manager.loadEntriesPause(1L)

        assertThrows(BotControlPersistFailedException::class.java) { runBlocking { manager.setEntriesPaused(1L, true) } }

        assertFalse(manager.entriesPaused(1L))
    }

    @Test
    fun `a save that never finishes times out and leaves the switch as it was`() = runTest {
        every { botStateRepository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Mono.just(row("KIS_PAPER", running = true))
        every { botStateRepository.save(any()) } returns Mono.never()
        manager.loadEntriesPause(1L)

        val error = runCatching { manager.setEntriesPaused(1L, true) }.exceptionOrNull()

        assertTrue(error is BotControlPersistFailedException, error.toString())
        assertFalse(manager.entriesPaused(1L))
    }

    // 상태 조회는 lock 없이 읽으므로 그사이 저장된 값을 덮으면 엔진이 중지를 놓친다.
    @Test
    fun `a status read that started before a pause does not undo it`() = runTest {
        val saves = storedRow(row("KIS_PAPER", running = false))
        every { botStateRepository.findByUserIdAndExchange(1L, "KIS_PAPER") } answers {
            val stale = row("KIS_PAPER", running = false)
            runBlocking { manager.setEntriesPaused(1L, true) }
            Mono.just(stale)
        } andThenAnswer { Mono.justOrEmpty(saves.lastOrNull()) }

        manager.loadEntriesPause(1L)

        assertTrue(manager.entriesPaused(1L))
        assertEquals(true, manager.getStatus(1L)["entries_paused"])
    }

    @Test
    fun `pausing a user who never started a bot creates a stopped row`() = runTest {
        val saves = storedRow(null)

        manager.setEntriesPaused(1L, true)

        val saved = saves.single()
        assertEquals(1L, saved.userId)
        assertEquals("KIS_PAPER", saved.exchange)
        assertFalse(saved.running)
        assertTrue(saved.entriesPaused)
    }

    @Test
    fun `a stopped user's saved pause shows in status after a restart`() = runTest {
        val changedAt = Instant.parse("2026-11-07T00:00:00Z")
        storedRow(row("KIS_PAPER", running = false).copy(entriesPaused = true, entriesPausedChangedAt = changedAt))

        manager.loadEntriesPause(1L)

        assertEquals(true, manager.getStatus(1L)["entries_paused"])
        assertEquals("2026-11-07T00:00:00Z", manager.getStatus(1L)["entries_paused_changed_at"])
    }

    @Test
    fun `status says unknown when the saved pause cannot be read`() = runTest {
        every { botStateRepository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Mono.error(RuntimeException("db down"))

        manager.loadEntriesPause(1L)

        assertTrue(manager.getStatus(1L).containsKey("entries_paused"))
        assertNull(manager.getStatus(1L)["entries_paused"])
    }

    @Test
    fun `start, stop and restore keep a saved pause and load it before the engine runs`() = runTest {
        val saves = storedRow(row("KIS_PAPER", running = false).copy(entriesPaused = true))
        every { userRepository.findById(1L) } returns Mono.just(user)
        every { userSecretsService.decryptUserSecrets(user) } returns user
        coEvery { tradingStateService.loadStates(1L, any()) } returns emptyMap()
        val pausedAtEngineStart = mutableListOf<Boolean>()
        coEvery { engine.start(any(), any()) } answers { pausedAtEngineStart += manager.entriesPaused(1L) }
        every { engine.isRunning() } returns false

        manager.startBot(1L, null)
        manager.stopBot(1L)
        every { botStateRepository.findByRunningTrueAndExchange("KIS_PAPER") } returns Flux.just(row("KIS_PAPER", running = true))
        val restarted = spyk(newManager()).also { every { it.createEngine(any()) } returns engine }
        coEvery { engine.start(any(), any()) } answers { pausedAtEngineStart += restarted.entriesPaused(1L) }
        saves.last().let { every { botStateRepository.findByUserIdAndExchange(1L, "KIS_PAPER") } returns Mono.just(it.copy(running = true)) }
        restarted.restoreAllRunningBots()

        assertEquals(listOf(true, false), saves.map { it.running })
        assertTrue(saves.all { it.entriesPaused }, saves.toString())
        assertEquals(listOf(true, true), pausedAtEngineStart)
    }

    @Test
    fun `pausing is refused once shutdown has begun`() = runTest {
        storedRow(row("KIS_PAPER", running = false))
        manager.stop()

        val result = manager.setEntriesPaused(1L, true)

        assertTrue(result.containsKey("error"), result.toString())
        verify(exactly = 0) { botStateRepository.save(any()) }
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
