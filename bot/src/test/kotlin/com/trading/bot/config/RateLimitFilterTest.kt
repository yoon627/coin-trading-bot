package com.trading.bot.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RateLimitFilterTest {

    @Test
    fun `filter passes through when redis is null`() {
        val filter = RateLimitFilter(null)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/bot/status").build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        // Should have called chain.filter (pass-through)
        io.mockk.verify { chain.filter(exchange) }
    }

    @Test
    fun `filter skips actuator endpoints`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/actuator/health").build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        io.mockk.verify { chain.filter(exchange) }
        // Should NOT call redis
        io.mockk.verify(exactly = 0) { redisTemplate.opsForValue() }
    }

    @Test
    fun `filter applies stricter rate limit to auth endpoints`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.increment(any()) } returns Mono.just(31L) // exceeds auth limit (30)
        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/login").build()
        )
        val chain = mockk<WebFilterChain>()

        filter.filter(exchange, chain).block()

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.response.statusCode)
    }

    @Test
    fun `filter skips price stream endpoints`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/prices/stream").build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        io.mockk.verify { chain.filter(exchange) }
    }

    @Test
    fun `filter skips tide-app static bundle paths`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val filter = RateLimitFilter(redisTemplate)
        for (path in listOf("/tide-app/api.js", "/tide-app/screens.jsx", "/tide-app/tokens.css", "/tide-app/ui.jsx")) {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build())
            val chain = mockk<WebFilterChain>()
            every { chain.filter(exchange) } returns Mono.empty()

            filter.filter(exchange, chain).block()

            io.mockk.verify { chain.filter(exchange) }
        }
    }

    @Test
    fun `filter allows requests within rate limit`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.increment(any()) } returns Mono.just(5L) // 5th request
        every { redisTemplate.expire(any(), any<Duration>()) } returns Mono.just(true)

        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/bot/status").build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        io.mockk.verify { chain.filter(exchange) }
    }

    @Test
    fun `filter returns 429 when rate limit exceeded`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.increment(any()) } returns Mono.just(61L) // exceeded

        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/bot/status").build()
        )
        val chain = mockk<WebFilterChain>()

        filter.filter(exchange, chain).block()

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.response.statusCode)
    }

    @Test
    fun `filter sets expire on first request`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.increment(any()) } returns Mono.just(1L) // first request
        every { redisTemplate.expire(any(), any<Duration>()) } returns Mono.just(true)

        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/trades").build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        io.mockk.verify { redisTemplate.expire(any(), Duration.ofMinutes(1)) }
    }

    @Test
    fun `filter uses X-Forwarded-For client ip for rate limit key`() {
        // Caddy(reverse proxy) 뒤에선 remoteAddress 가 Caddy 컨테이너 IP 하나로 뭉치므로,
        // 실제 client 식별은 Caddy 가 부여한 X-Forwarded-For 로 해야 IP별 rate limit 이 동작한다.
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        val keySlot = slot<String>()
        every { valueOps.increment(capture(keySlot)) } returns Mono.just(1L)
        every { redisTemplate.expire(any(), any<Duration>()) } returns Mono.just(true)

        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/login")
                .header("X-Forwarded-For", "203.0.113.5")
                .build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        assertTrue(
            keySlot.captured.contains("203.0.113.5"),
            "rate limit key 에 XFF client IP 가 반영돼야 함: ${keySlot.captured}",
        )
    }

    @Test
    fun `filter keys non-auth requests by client ip and ignores a client-supplied X-User-Id`() {
        // 이 헤더를 설정하는 서버 구성요소는 없다 — 클라이언트가 보낸 값을 키로 쓰면 값만 바꿔 버킷을 무한히 새로 받는다.
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        val keys = mutableListOf<String>()
        every { valueOps.increment(capture(keys)) } returns Mono.just(1L)
        every { redisTemplate.expire(any(), any<Duration>()) } returns Mono.just(true)
        val filter = RateLimitFilter(redisTemplate)

        for (spoofed in listOf("alice", "bob")) {
            val exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/trades")
                    .header("X-Forwarded-For", "203.0.113.5")
                    .header("X-User-Id", spoofed)
                    .build()
            )
            val chain = mockk<WebFilterChain>()
            every { chain.filter(exchange) } returns Mono.empty()
            filter.filter(exchange, chain).block()
        }

        assertEquals(1, keys.map { it.substringBeforeLast(':') }.distinct().size, "헤더 값이 달라도 같은 버킷이어야 함: $keys")
        assertTrue(keys.all { it.contains("203.0.113.5") }, "버킷 키는 client IP: $keys")
    }

    // 인증(30/min)과 일반 API(60/min)가 카운터 하나를 나눠 쓰면, 같은 IP 의 폴링이 1분에 30회를 넘을 때(한 화면은
    // 약 20/min 이라 탭 2개·NAT 공유에서) 같은 분의 로그아웃·재로그인이 자기 한도를 하나도 안 썼는데 429 가 된다.
    @Test
    fun `api traffic does not consume the auth bucket of the same client`() {
        val filter = RateLimitFilter(null)
        fun send(method: String, path: String): HttpStatus? {
            val request = if (method == "POST") MockServerHttpRequest.post(path) else MockServerHttpRequest.get(path)
            val exchange = MockServerWebExchange.from(request.header("X-Forwarded-For", "203.0.113.9").build())
            val chain = mockk<WebFilterChain>()
            every { chain.filter(exchange) } returns Mono.empty()
            filter.filter(exchange, chain).block()
            return exchange.response.statusCode as HttpStatus?
        }

        repeat(40) { assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, send("GET", "/api/trades"), "일반 API 40회는 60 한도 안") }

        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, send("POST", "/api/auth/login"), "인증 버킷은 일반 API 호출에 소모되지 않는다")
    }

    // 위 재현은 벽시계에 묶여 있다(41요청 사이 분이 바뀌면 카운터가 비어 회귀를 놓친다) — 키 자체를 단정해 시간과 무관하게,
    // 운영이 실제로 타는 Redis 경로에서 분리를 고정한다.
    @Test
    fun `auth and api requests of one client use different redis keys`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        val keys = mutableListOf<String>()
        every { valueOps.increment(capture(keys)) } returns Mono.just(1L)
        every { redisTemplate.expire(any(), any<Duration>()) } returns Mono.just(true)
        val filter = RateLimitFilter(redisTemplate)

        for (request in listOf(MockServerHttpRequest.get("/api/trades"), MockServerHttpRequest.post("/api/auth/login"))) {
            val exchange = MockServerWebExchange.from(request.header("X-Forwarded-For", "203.0.113.9").build())
            val chain = mockk<WebFilterChain>()
            every { chain.filter(exchange) } returns Mono.empty()
            filter.filter(exchange, chain).block()
        }

        assertTrue(keys[0].startsWith("ratelimit:api:203.0.113.9:"), keys.toString())
        assertTrue(keys[1].startsWith("ratelimit:auth:203.0.113.9:"), keys.toString())
    }

    @Test
    fun `filter takes first ip from X-Forwarded-For chain`() {
        // XFF 가 "client, proxy.." 체인일 때 원 client(첫 항목)를 식별자로 사용.
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        val keySlot = slot<String>()
        every { valueOps.increment(capture(keySlot)) } returns Mono.just(1L)
        every { redisTemplate.expire(any(), any<Duration>()) } returns Mono.just(true)

        val filter = RateLimitFilter(redisTemplate)
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/login")
                .header("X-Forwarded-For", "203.0.113.5, 10.0.0.1")
                .build()
        )
        val chain = mockk<WebFilterChain>()
        every { chain.filter(exchange) } returns Mono.empty()

        filter.filter(exchange, chain).block()

        assertTrue(
            keySlot.captured.contains("203.0.113.5"),
            "체인의 첫 IP 를 써야 함: ${keySlot.captured}",
        )
    }

    // ── Redis 장애 강등 (#229) ──
    // Redis 가 실패하거나 응답하지 않아도 API 는 in-memory 카운터로 계속 판정된다. 하류(컨트롤러)의 오류·지연은
    // Redis 장애가 아니다 — 강등으로 잡으면 요청이 두 번 처리된다.

    private class MutableClock(var now: Instant = Instant.parse("2026-09-30T00:00:10Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    /** `increment` 호출마다 [answer] 가 돌려주는 Mono 를 쓰는 템플릿. 호출 수는 [calls] 로 센다. */
    private fun redis(calls: AtomicInteger = AtomicInteger(), answer: () -> Mono<Long>): ReactiveRedisTemplate<String, String> {
        val template = mockk<ReactiveRedisTemplate<String, String>>()
        val ops = mockk<ReactiveValueOperations<String, String>>()
        every { template.opsForValue() } returns ops
        every { ops.increment(any()) } answers { calls.incrementAndGet(); answer() }
        every { template.expire(any(), any<Duration>()) } returns Mono.just(true)
        return template
    }

    private class Sent(val exchange: MockServerWebExchange, val chainCalls: AtomicInteger)

    private fun exchange() = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/bot/status").header("X-Forwarded-For", "203.0.113.7").build(),
    )

    private fun send(filter: RateLimitFilter, downstream: () -> Mono<Void> = { Mono.empty() }): Sent {
        val exchange = exchange()
        val calls = AtomicInteger()
        filter.filter(exchange) { calls.incrementAndGet(); downstream() }.block(Duration.ofSeconds(5))
        return Sent(exchange, calls)
    }

    private fun <T> logsWhile(level: Level, block: () -> T): Pair<List<String>, T> {
        val logger = LoggerFactory.getLogger(RateLimitFilter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            val result = block()
            appender.list.filter { it.level == level }.map { it.formattedMessage } to result
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private val down = RedisConnectionFailureException("redis down")

    @Test
    fun `하류가 Redis 타임아웃보다 느려도 요청은 한 번만 처리되고 강등하지 않는다`() {
        val filter = RateLimitFilter(redis { Mono.just(5L) }, redisTimeout = Duration.ofMillis(50))

        val (warns, sent) = logsWhile(Level.WARN) {
            send(filter) { Mono.delay(Duration.ofMillis(200)).then() }
        }

        assertEquals(1, sent.chainCalls.get(), "하류가 두 번 불리면 요청이 두 번 처리된다")
        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, sent.exchange.response.statusCode)
        assertTrue(warns.isEmpty(), "느린 하류는 Redis 장애가 아니다: $warns")
    }

    @Test
    fun `Redis 가 실패하면 in-memory 로 판정해 요청을 통과시킨다`() {
        val filter = RateLimitFilter(redis { Mono.error(down) })

        val sent = send(filter)

        assertEquals(1, sent.chainCalls.get())
        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, sent.exchange.response.statusCode)
    }

    @Test
    fun `Redis 가 응답하지 않으면 타임아웃 뒤 in-memory 로 판정한다`() {
        val filter = RateLimitFilter(redis { Mono.never() }, redisTimeout = Duration.ofMillis(50))
        val started = System.nanoTime()

        val sent = send(filter)

        assertEquals(1, sent.chainCalls.get())
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(2), "Lettuce 명령 타임아웃(기본 60s)까지 매달리면 안 된다")
    }

    @Test
    fun `Redis 연결 획득이 막혀도 요청 스레드는 타임아웃만큼만 기다린다`() {
        // 공유 연결이 없으면 Lettuce 는 구독한 스레드에서 동기로 connect 한다(`Mono.fromSupplier`) — 그 스레드가 이벤트 루프면
        // 같은 루프의 다른 요청까지 멈춘다. 구독을 worker 로 넘겼는지를 막히는 supplier 로 본다.
        val filter = RateLimitFilter(
            redis { Mono.fromSupplier { Thread.sleep(1_000); 1L } },
            redisTimeout = Duration.ofMillis(50),
        )
        val started = System.nanoTime()

        val sent = send(filter)

        assertEquals(1, sent.chainCalls.get())
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofMillis(700), "호출 스레드가 connect 만큼 묶였다")
    }

    @Test
    fun `타임아웃으로 끊은 Redis 호출은 interrupt 하지 않고 늦은 오류도 ERROR 로 남기지 않는다`() {
        // worker 를 interrupt 하면 Lettuce 가 진행 중인 connect 를 버려 주인 없는 연결이 남고, 늦은 오류가 Reactor 의
        // onErrorDropped ERROR(스택 포함)로 찍혀 "장애 한 번에 WARN 한 줄" 이 깨진다.
        val finished = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val filter = RateLimitFilter(
            redis {
                Mono.fromSupplier<Long> {
                    try {
                        Thread.sleep(200)
                    } catch (e: InterruptedException) {
                        interrupted.set(true)
                        throw e
                    } finally {
                        finished.countDown()
                    }
                    throw RedisConnectionFailureException("late")
                }
            },
            redisTimeout = Duration.ofMillis(50),
        )
        val operators = LoggerFactory.getLogger("reactor.core.publisher.Operators") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        operators.addAppender(appender)
        try {
            assertEquals(1, send(filter).chainCalls.get())
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            Thread.sleep(100) // 늦은 오류가 연산자에 닿을 시간
            assertFalse(interrupted.get(), "타임아웃이 진행 중인 호출을 interrupt 했다")
            assertTrue(appender.list.none { it.level == Level.ERROR }, appender.list.map { it.formattedMessage }.toString())
        } finally {
            operators.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `강등 중에도 같은 한도를 적용한다`() {
        val filter = RateLimitFilter(redis { Mono.error(down) }, clock = MutableClock())

        repeat(60) { assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, send(filter).exchange.response.statusCode, "${it + 1}번째는 한도 안") }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, send(filter).exchange.response.statusCode)
    }

    @Test
    fun `강등 뒤 지연 동안은 Redis 를 부르지 않고, 지나면 한 요청이 다시 시도해 성공하면 돌아온다`() {
        val clock = MutableClock()
        val calls = AtomicInteger()
        var redisUp = false
        val filter = RateLimitFilter(redis(calls) { if (redisUp) Mono.just(1L) else Mono.error(down) }, clock = clock)

        send(filter) // 실패 → 강등
        clock.now = clock.now.plusSeconds(10)
        send(filter) // 지연 중
        assertEquals(1, calls.get(), "지연 중에는 Redis 를 부르지 않는다")

        clock.now = clock.now.plusSeconds(21)
        send(filter) // 지연이 지남 → 다시 시도 → 실패 → 지연을 다시 건다
        assertEquals(2, calls.get())
        send(filter)
        assertEquals(2, calls.get(), "다시 건 지연 중")

        redisUp = true
        clock.now = clock.now.plusSeconds(31)
        send(filter) // 다시 시도 → 성공 → 복귀
        send(filter)
        assertEquals(4, calls.get(), "복귀 뒤에는 요청마다 Redis 로 판정한다")
    }

    @Test
    fun `지연이 지난 뒤 다시 시도하는 요청은 하나뿐이고 나머지는 결과를 기다리지 않는다`() {
        val clock = MutableClock()
        val calls = AtomicInteger()
        val probe = Sinks.one<Long>()
        var mode = "fail"
        val filter = RateLimitFilter(
            redis(calls) { if (mode == "fail") Mono.error(down) else probe.asMono() },
            clock = clock,
            redisTimeout = Duration.ofSeconds(5),
        )
        send(filter) // 강등
        clock.now = clock.now.plusSeconds(31)
        mode = "pending"

        val inFlight = filter.filter(exchange()) { Mono.empty() }.subscribe()
        assertEquals(2, calls.get(), "한 요청이 Redis 를 다시 시도 중")
        val started = System.nanoTime()
        send(filter)
        assertEquals(2, calls.get(), "시도 중에는 다른 요청이 Redis 를 부르지 않는다")
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(1), "시도 결과를 기다리지 않는다")

        probe.tryEmitValue(1L)
        inFlight.dispose()
    }

    @Test
    fun `강등 전에 나간 요청이 늦게 성공해도 강등을 풀지 않는다`() {
        val clock = MutableClock()
        val calls = AtomicInteger()
        val late = Sinks.one<Long>()
        var mode = "late"
        val filter = RateLimitFilter(
            redis(calls) { if (mode == "late") late.asMono() else Mono.error(down) },
            clock = clock,
            redisTimeout = Duration.ofSeconds(5),
        )

        val (infos, _) = logsWhile(Level.INFO) {
            val beforeOutage = filter.filter(exchange()) { Mono.empty() }.toFuture()
            mode = "fail"
            send(filter) // 강등
            late.tryEmitValue(1L) // 장애 전 요청의 늦은 성공
            beforeOutage.get(2, TimeUnit.SECONDS) // 늦은 성공이 처리된 뒤에 다음 요청을 보낸다
            send(filter)
        }

        assertEquals(2, calls.get(), "늦은 성공이 복귀시켰다면 마지막 요청이 Redis 를 불렀다")
        assertTrue(infos.none { it.contains("복귀") }, infos.toString())
    }

    @Test
    fun `강등 전에 나간 요청이 늦게 실패해도 WARN 을 더 남기거나 지연을 늘리지 않는다`() {
        val clock = MutableClock()
        val calls = AtomicInteger()
        val first = Sinks.one<Long>()
        val second = Sinks.one<Long>()
        val pending = ArrayDeque(listOf(first, second))
        var redisUp = false
        val filter = RateLimitFilter(
            redis(calls) { if (redisUp) Mono.just(1L) else pending.removeFirst().asMono() },
            clock = clock,
            redisTimeout = Duration.ofSeconds(5),
        )

        val (warns, _) = logsWhile(Level.WARN) {
            val a = filter.filter(exchange()) { Mono.empty() }.toFuture()
            val b = filter.filter(exchange()) { Mono.empty() }.toFuture()
            first.tryEmitError(down) // 강등
            a.get(2, TimeUnit.SECONDS)
            clock.now = clock.now.plusSeconds(20)
            second.tryEmitError(down) // 강등 전에 나간 요청의 늦은 실패
            b.get(2, TimeUnit.SECONDS)
        }

        assertEquals(1, warns.size, warns.toString())
        redisUp = true
        clock.now = clock.now.plusSeconds(11) // 첫 실패로부터 31초
        send(filter)
        assertEquals(3, calls.get(), "늦은 실패가 지연을 늘렸다면 아직 다시 시도하지 않는다")
    }

    @Test
    fun `다시 시도하던 요청이 취소돼도 다음 요청이 다시 시도할 수 있다`() {
        val clock = MutableClock()
        val calls = AtomicInteger()
        var mode = "fail"
        val filter = RateLimitFilter(
            redis(calls) { when (mode) { "fail" -> Mono.error(down); "hang" -> Mono.never(); else -> Mono.just(1L) } },
            clock = clock,
            redisTimeout = Duration.ofSeconds(5),
        )
        send(filter) // 강등
        clock.now = clock.now.plusSeconds(31)
        mode = "hang"
        filter.filter(exchange()) { Mono.empty() }.subscribe().dispose() // 클라이언트가 끊겨 시도가 취소됨

        mode = "up"
        send(filter)

        assertEquals(3, calls.get(), "취소된 시도가 자리를 계속 잡고 있으면 영영 다시 시도하지 못한다")
    }

    @Test
    fun `강등은 장애 한 번에 WARN 한 줄, 복귀는 INFO 한 줄만 남긴다`() {
        val clock = MutableClock()
        var redisUp = false
        val filter = RateLimitFilter(redis { if (redisUp) Mono.just(1L) else Mono.error(down) }, clock = clock)

        val (warns, _) = logsWhile(Level.WARN) {
            repeat(5) { send(filter) }
            clock.now = clock.now.plusSeconds(31)
            send(filter) // 다시 시도도 실패
        }
        redisUp = true
        clock.now = clock.now.plusSeconds(31)
        val (infos, _) = logsWhile(Level.INFO) { repeat(3) { send(filter) } }

        assertEquals(1, warns.size, warns.toString())
        assertEquals(1, infos.count { it.contains("복귀") }, infos.toString())
    }

    @Test
    fun `하류 오류는 Redis 장애로 보지 않는다`() {
        val calls = AtomicInteger()
        val filter = RateLimitFilter(redis(calls) { Mono.just(5L) })
        val chainCalls = AtomicInteger()

        val (warns, error) = logsWhile(Level.WARN) {
            assertThrows(IllegalStateException::class.java) {
                filter.filter(exchange()) { chainCalls.incrementAndGet(); Mono.error(IllegalStateException("boom")) }.block(Duration.ofSeconds(5))
            }
        }

        assertEquals("boom", error.message)
        assertEquals(1, chainCalls.get(), "하류 오류로 요청을 다시 처리하면 안 된다")
        assertTrue(warns.isEmpty(), warns.toString())
        send(filter)
        assertEquals(2, calls.get(), "강등되지 않았으니 다음 요청도 Redis 로 판정한다")
    }
}
