package com.trading.bot.marketdata

import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory

/**
 * 시세를 받아야 하는 업비트 마켓(내부 표기) = 돌고 있는 엔진들의 활성 종목 합집합. 엔진이 시작·종목 정리·정지 때 자기 몫을
 * 올리고 내린다 — 관리자의 시작·정지·재시작·복원 경로를 따로 잇지 않아도 모두 따라간다.
 */
class MarketSubscription {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byOwner = ConcurrentHashMap<Any, Set<String>>()
    private val warned = ConcurrentHashMap.newKeySet<String>()
    private val current = MutableStateFlow<Set<String>>(emptySet())

    val markets: StateFlow<Set<String>> = current.asStateFlow()

    @Synchronized
    fun update(owner: Any, tickers: Collection<String>) {
        byOwner[owner] = tickers.mapNotNullTo(mutableSetOf()) { normalized(it) }
        publish()
    }

    @Synchronized
    fun remove(owner: Any) {
        if (byOwner.remove(owner) != null) publish()
    }

    private fun publish() {
        current.value = byOwner.values.flatten().toSortedSet()
    }

    // 형식이 틀린 코드는 구독 메시지를 만들다 던져 모든 마켓을 끊는다. 없는·상장 폐지 코드는 업비트가 조용히 무시한다.
    private fun normalized(ticker: String): String? {
        if (MarketPair.isValid(Exchange.UPBIT, ticker)) return MarketPair.normalize(Exchange.UPBIT, ticker)
        if (warned.add(ticker)) log.warn("시세 구독에서 형식이 틀린 종목 코드를 뺍니다: {}", ticker)
        return null
    }
}
