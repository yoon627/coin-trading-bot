package com.trading.bot.engine

import com.trading.common.domain.Exchange
import kotlinx.coroutines.CancellationException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/** 등록된 매니저를 거래소로 찾는다. 거래소마다 매니저는 하나다. */
@Component
class TradingManagers private constructor(
    private val byExchange: Map<Exchange, UserTradingManager>,
) {
    @Autowired
    constructor(managers: List<UserTradingManager>) : this(index(managers))

    fun get(exchange: Exchange): UserTradingManager = byExchange[exchange] ?: throw UnsupportedExchangeException(exchange)

    fun find(exchange: Exchange): UserTradingManager? = byExchange[exchange]

    fun all(): List<UserTradingManager> = byExchange.values.toList()

    /**
     * 사용자 설정은 모든 거래소 엔진이 함께 읽으므로 전부 다시 읽힌다. 한 매니저가 실패해도 나머지 엔진은 새 설정을 받아야 해서
     * 끝까지 부른 뒤 첫 실패를 던진다.
     */
    suspend fun reloadAll(userId: Long) {
        var first: Exception? = null
        for (manager in byExchange.values) {
            try {
                manager.reloadUserRuntime(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val prior = first
                if (prior == null) first = e else prior.addSuppressed(e)
            }
        }
        first?.let { throw it }
    }

    internal companion object {
        /** 키와 매니저의 [UserTradingManager.exchange] 가 맞는지·겹치는지 보지 않고 묶는다. 목 매니저로 컨트롤러를 만드는 테스트용이다. */
        fun of(vararg entries: Pair<Exchange, UserTradingManager>): TradingManagers = TradingManagers(linkedMapOf(*entries))

        private fun index(managers: List<UserTradingManager>): Map<Exchange, UserTradingManager> {
            val indexed = linkedMapOf<Exchange, UserTradingManager>()
            for (manager in managers) {
                check(indexed.put(manager.exchange, manager) == null) { "${manager.exchange} 거래소 매니저가 둘 이상 등록됐다" }
            }
            return indexed
        }
    }
}

/** 요청한 거래소의 매니저가 등록돼 있지 않다. 요청 오류라 400 으로 나간다. */
class UnsupportedExchangeException(val exchange: Exchange) :
    ResponseStatusException(HttpStatus.BAD_REQUEST, "$exchange 거래소는 아직 지원하지 않는다")
