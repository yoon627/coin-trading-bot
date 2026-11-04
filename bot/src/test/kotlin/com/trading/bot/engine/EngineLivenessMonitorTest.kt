package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.common.domain.Exchange
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** 멈춤 한 번에 ERROR 한 번 — 60초마다 같은 알림이 나가면 Discord 가 묻히고, 회복을 알리지 않으면 사람이 계속 기다린다. */
class EngineLivenessMonitorTest {

    private val now = Instant.parse("2026-11-04T03:00:00Z")
    private val manager = mockk<UserTradingManager>()
    private val engine = mockk<TradingEngine>()
    private val engines = mutableMapOf(4L to engine)
    private var stalled = true
    // TradingManagers 가 만들 때 manager.exchange 를 읽으므로 setup 뒤에 만든다.
    private val monitor by lazy { EngineLivenessMonitor(TradingManagers(listOf(manager)), Clock.fixed(now, ZoneOffset.UTC)) }

    private val logger = LoggerFactory.getLogger(EngineLivenessMonitor::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setup() {
        every { manager.exchange } returns Exchange.UPBIT
        every { manager.liveEngines() } answers { engines.toMap() }
        every { engine.isLoopAlive() } returns true
        every { engine.isStalled(now) } answers { stalled }
        every { engine.lastProgressAt() } returns now.minusSeconds(400)
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(appender)
    }

    private fun lines(level: Level) = appender.list.filter { it.level == level }.map { it.formattedMessage }

    @Test
    fun `같은 멈춤은 한 번만 알리고 회복하면 INFO, 다시 멈추면 또 알린다`() {
        repeat(3) { monitor.check() }
        assertEquals(1, lines(Level.ERROR).size)
        val alert = lines(Level.ERROR).single()
        assert(alert.contains("[ENGINE STALLED]") && alert.contains("user 4") && alert.contains("UPBIT")) { alert }

        stalled = false
        monitor.check()
        monitor.check()
        assertEquals(1, lines(Level.INFO).count { it.contains("recovered") })

        stalled = true
        monitor.check()
        assertEquals(2, lines(Level.ERROR).size)
    }

    /** 죽은 루프는 엔진이 끝나는 순간 이미 알렸다. 회복할 수 없으니 회복 INFO 도 내지 않는다. */
    @Test
    fun `죽은 엔진은 알리지 않고 멈춤 상태도 조용히 지운다`() {
        monitor.check()
        // 실제 엔진은 끝난 루프를 멈춤으로 보지 않는다(isStalled 가 생존을 먼저 본다).
        every { engine.isLoopAlive() } returns false
        stalled = false
        monitor.check()
        monitor.check()

        assertEquals(1, lines(Level.ERROR).size)
        assertEquals(0, lines(Level.INFO).size)
    }

    /** 교체된 엔진이 옛 엔진의 알림 상태를 물려받지 않는다. 정지·교체로 목록에서 빠지면 조용히 지운다. */
    @Test
    fun `목록에서 빠진 엔진의 상태는 지우고 새 엔진은 처음부터 본다`() {
        monitor.check()
        val replacement = mockk<TradingEngine>()
        every { replacement.isLoopAlive() } returns true
        every { replacement.isStalled(now) } returns true
        every { replacement.lastProgressAt() } returns now.minusSeconds(400)
        engines[4L] = replacement
        monitor.check()

        assertEquals(2, lines(Level.ERROR).size)
        assertEquals(0, lines(Level.INFO).size)
    }

    /** 정지된 엔진이 같은 인스턴스로 다시 돌면(reload 실패 뒤 resume) 그 멈춤은 새 멈춤이다. */
    @Test
    fun `목록에서 빠졌다 돌아온 같은 엔진의 멈춤은 다시 알린다`() {
        monitor.check()
        engines.clear()
        monitor.check()
        engines[4L] = engine
        monitor.check()

        assertEquals(2, lines(Level.ERROR).size)
        assertEquals(0, lines(Level.INFO).size)
    }
}
