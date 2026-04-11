package com.trading.bot.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.domain.RealtimePrice
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.time.Duration

@Service
class PriceCacheService(
    private val redisTemplate: ReactiveRedisTemplate<String, String>?,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val PRICE_KEY_PREFIX = "price:"
        private val PRICE_TTL = Duration.ofSeconds(5)
    }

    fun cachePrice(price: RealtimePrice): Mono<Boolean> {
        if (redisTemplate == null) return Mono.just(false)
        val key = "$PRICE_KEY_PREFIX${price.market}"
        val value = objectMapper.writeValueAsString(price)
        return redisTemplate.opsForValue().set(key, value, PRICE_TTL)
    }

    fun getCachedPrice(ticker: String): Mono<RealtimePrice> {
        if (redisTemplate == null) return Mono.empty()
        val key = "$PRICE_KEY_PREFIX$ticker"
        return redisTemplate.opsForValue().get(key)
            .map { json -> objectMapper.readValue(json, RealtimePrice::class.java) }
    }
}
