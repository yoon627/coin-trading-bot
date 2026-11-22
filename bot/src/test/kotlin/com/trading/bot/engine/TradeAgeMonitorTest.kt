package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** 체결 시각이 호스트 시계와 어긋나면(미래로 5초 넘게, 또는 받는 체결이 모두 30초 넘게 낡음) 1분에 한 번 WARN 한다. */
class TradeAgeMonitorTest {

    private var now = Instant.parse("2026-11-20T03:00:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
    private val logger = LoggerFactory.getLogger(TradeAgeMonitor::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setup() {
        logger.addAppender(logs)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(logs)
    }

    private fun warns() = logs.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    private fun advance(seconds: Long) {
        now = now.plusSeconds(seconds)
    }

    @Test
    fun `trades from more than 5 s in the future warn at most once a minute`() {
        val monitor = TradeAgeMonitor(clock, userId = 1L)

        monitor.observe(now.plusSeconds(4))
        assertEquals(emptyList<String>(), warns())
        monitor.observe(now.plusSeconds(6))
        advance(30)
        monitor.observe(now.plusSeconds(6))
        assertEquals(1, warns().size, warns().toString())
        assertTrue("ahead" in warns().single(), warns().toString())

        advance(31)
        monitor.observe(now.plusSeconds(6))
        assertEquals(2, warns().size, warns().toString())
    }

    @Test
    fun `a minute in which every trade is stale warns, one with a fresh trade does not`() {
        val monitor = TradeAgeMonitor(clock, userId = 1L)

        repeat(7) { monitor.observe(now.minus(Duration.ofSeconds(40))); advance(10) }
        assertEquals(1, warns().size, warns().toString())
        assertTrue("stale" in warns().single(), warns().toString())

        val mixed = TradeAgeMonitor(clock, userId = 2L)
        repeat(7) { i -> mixed.observe(if (i == 3) now else now.minus(Duration.ofSeconds(40))); advance(10) }
        assertEquals(1, warns().size, warns().toString())
    }
}
