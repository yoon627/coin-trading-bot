package com.trading.bot.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.domain.RealtimePrice
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.time.Duration

class PriceCacheServiceTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @Test
    fun `cachePrice returns false when redis is null`() {
        val service = PriceCacheService(null, objectMapper)
        val price = RealtimePrice("KRW-BTC", 50000000.0, 0.01, 1e12)

        val result = service.cachePrice(price).block()
        assertFalse(result!!)
    }

    @Test
    fun `getCachedPrice returns empty when redis is null`() {
        val service = PriceCacheService(null, objectMapper)

        val result = service.getCachedPrice("KRW-BTC").block()
        assertNull(result)
    }

    @Test
    fun `cachePrice stores price with TTL`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.set(any(), any(), any<Duration>()) } returns Mono.just(true)

        val service = PriceCacheService(redisTemplate, objectMapper)
        val price = RealtimePrice("KRW-BTC", 50000000.0, 0.01, 1e12)

        val result = service.cachePrice(price).block()
        assertTrue(result!!)

        verify { valueOps.set("price:KRW-BTC", any(), Duration.ofSeconds(5)) }
    }

    @Test
    fun `getCachedPrice returns price when cached`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps

        val price = RealtimePrice("KRW-BTC", 50000000.0, 0.01, 1e12)
        val json = objectMapper.writeValueAsString(price)
        every { valueOps.get("price:KRW-BTC") } returns Mono.just(json)

        val service = PriceCacheService(redisTemplate, objectMapper)
        val result = service.getCachedPrice("KRW-BTC").block()

        assertNotNull(result)
        assertEquals("KRW-BTC", result!!.market)
        assertEquals(50000000.0, result.tradePrice)
    }

    @Test
    fun `getCachedPrice returns empty when not in cache`() {
        val redisTemplate = mockk<ReactiveRedisTemplate<String, String>>()
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.get("price:KRW-ETH") } returns Mono.empty()

        val service = PriceCacheService(redisTemplate, objectMapper)
        val result = service.getCachedPrice("KRW-ETH").block()

        assertNull(result)
    }
}
