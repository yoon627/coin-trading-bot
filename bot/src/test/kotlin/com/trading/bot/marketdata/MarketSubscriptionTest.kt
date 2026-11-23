package com.trading.bot.marketdata

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 시세를 받을 업비트 마켓은 돌고 있는 엔진들이 올린 종목의 합집합이다. 엔진은 자기 몫만 올리고 내린다. */
class MarketSubscriptionTest {

    private val subscription = MarketSubscription()
    private val engineA = Any()
    private val engineB = Any()

    @Test
    fun `the markets are the union of every owner's tickers in the internal form`() {
        subscription.update(engineA, listOf("KRW-BTC", "KRW-ETH"))
        subscription.update(engineB, listOf("KRW-ETH", "KRW-XRP"))

        assertThat(subscription.markets.value).containsExactlyInAnyOrder("BTC/KRW", "ETH/KRW", "XRP/KRW")
    }

    @Test
    fun `an owner's update replaces its own share and removing it drops only what no other owner holds`() {
        subscription.update(engineA, listOf("KRW-BTC", "KRW-ETH"))
        subscription.update(engineB, listOf("KRW-ETH"))

        subscription.update(engineA, listOf("KRW-BTC"))
        assertThat(subscription.markets.value).containsExactlyInAnyOrder("BTC/KRW", "ETH/KRW")

        subscription.remove(engineB)
        assertThat(subscription.markets.value).containsExactly("BTC/KRW")
        subscription.remove(engineA)
        assertThat(subscription.markets.value).isEmpty()
    }

    // 형식이 틀린 코드가 구독 메시지에 들어가면 메시지를 만들다 던져 모든 마켓이 끊긴다.
    @Test
    fun `a malformed code is left out`() {
        subscription.update(engineA, listOf("KRW-BTC", "krw btc", "BTC"))

        assertThat(subscription.markets.value).containsExactly("BTC/KRW")
    }
}
